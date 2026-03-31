package com.src.billing_library

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustEvent
import com.src.billing_library.model.product.BillingProductDetail
import com.src.billing_library.model.purchase.PurchaseRecord
import java.util.Calendar

/**
 * Tracks IAP revenue to Adjust for ad-spend decision-making.
 *
 * Two tracking paths:
 * 1. [trackPurchase] — called immediately after a successful purchase.
 *    Handles first payments only. Skips trials (price = 0). Deduped by cycleKey(token, purchaseTime).
 *
 * 2. [trackPurchases] — called on app open via queryPurchasesAsync.
 *    Handles subscription renewals by computing expected charge cycles from
 *    purchaseTime + pricingPhases. Catches up missed cycles if user was offline.
 *    Deduped per cycle by cycleKey(token, chargeTime). Skips trial/free phases (price = 0).
 *
 * @param context Application context
 * @param eventToken Adjust event token for all IAP events
 */
class AdjustIapTracker(
    context: Context,
    private val eventToken: String,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -------------------------------------------------------------------------
    // First purchase
    // -------------------------------------------------------------------------

    /**
     * Track the initial purchase immediately after the billing flow completes.
     * Safe to call multiple times — skips if already logged for this purchaseToken.
     * Skips silently if price = 0 (trial or free introductory period).
     */
    fun trackPurchase(
        purchase: PurchaseRecord,
        billingProductDetail: BillingProductDetail?,
    ) {
        val detail = billingProductDetail ?: return
        val token = purchase.purchaseToken

        val key = cycleKey(token, purchase.purchaseTime)
        if (prefs.getBoolean(key, false)) {
            Log.d(TAG, "trackPurchase SKIP (already logged): token=$token")
            return
        }

        val (price, currency) = firstPurchasePrice(detail)

        if (price <= 0.0) {
            Log.d(TAG, "trackPurchase SKIP (trial/free): productId=${purchase.productId}")
            return
        }

        sendToAdjust(price, currency, purchase, detail.productType, adjustDedupId = token)

        prefs.edit { putBoolean(key, true) }
    }

    // -------------------------------------------------------------------------
    // Subscription renewals
    // -------------------------------------------------------------------------

    /**
     * Compute and track all subscription renewal cycles that have occurred up to now.
     * Called from queryProductDetailsAndPurchases on app open.
     *
     */
    fun trackPurchases(
        purchases: List<PurchaseRecord>,
        productDetails: Map<String, BillingProductDetail>
    ) {
        val now = System.currentTimeMillis()
        purchases.filter { it.isPurchased }.forEach { purchase ->
            val detail = productDetails[purchase.productId] ?: return@forEach
            if (detail.isSubscription()) {
                trackSubscriptionRenewals(purchase, detail, now)
            }
            // Inapp one-time: no renewal cycles, nothing to do here
        }
    }

    private fun trackSubscriptionRenewals(
        purchase: PurchaseRecord,
        detail: BillingProductDetail,
        now: Long
    ) {
        val offer = detail.bestSubscriptionOffer() ?: return

        val token = purchase.purchaseToken
        var phaseStart = purchase.purchaseTime
        var reachedFuture = false

        for (phase in offer.pricingPhases) {
            if (reachedFuture) break

            val period = parsePeriod(phase.billingPeriod) ?: break
            // recurrenceMode: 1 = INFINITE_RECURRING, 2 = FINITE_RECURRING, 3 = NON_RECURRING
            val isInfinite = phase.recurrenceMode == 1
            val maxCycles = if (isInfinite) Int.MAX_VALUE else phase.billingCycleCount

            var cycleStart = phaseStart

            for (i in 0 until maxCycles) {
                if (cycleStart > now) {
                    reachedFuture = true
                    break
                }

                // Cycle 0 (cycleStart == purchaseTime) is always skipped here:
                // - If paid sub with no trial → trackPurchase already handled it
                // - If free trial phase      → price = 0, not a real charge
                // Renewals start from cycle 1 onward (cycleStart > purchaseTime).
                if (cycleStart == purchase.purchaseTime) {
                    cycleStart = addPeriod(cycleStart, period)
                    continue
                }

                if (phase.priceAmountMicros > 0) {
                    val key = cycleKey(token, cycleStart)
                    if (!prefs.getBoolean(key, false)) {
                        sendToAdjust(
                            price = phase.priceAmountMicros / 1_000_000.0,
                            currency = phase.priceCurrencyCode,
                            purchase = purchase,
                            productType = detail.productType,
                            adjustDedupId = key
                        )
                        prefs.edit { putBoolean(key, true) }
                    } else {
                        Log.d(TAG, "trackRenewal SKIP (already logged): $key")
                    }
                } else {
                    Log.d(
                        TAG,
                        "trackRenewal SKIP (price=0, trial/free): cycle=$i phase=${phase.billingPeriod}"
                    )
                }

                cycleStart = addPeriod(cycleStart, period)
            }

            // Advance phase anchor for the next phase
            if (!reachedFuture) phaseStart = cycleStart
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun firstPurchasePrice(detail: BillingProductDetail): Pair<Double, String> {
        return if (detail.isSubscription()) {
            // First phase of best offer — may be $0 for trial, caller will skip it
            val phase = detail.bestSubscriptionOffer()?.pricingPhases?.firstOrNull()
                ?: return Pair(0.0, "USD")
            Pair(phase.priceAmountMicros / 1_000_000.0, phase.priceCurrencyCode)
        } else {
            Pair(
                (detail.oneTimePurchaseOfferDetails?.priceAmountMicros ?: 0L) / 1_000_000.0,
                detail.oneTimePurchaseOfferDetails?.priceCurrencyCode ?: "USD"
            )
        }
    }

    private fun sendToAdjust(
        price: Double,
        currency: String,
        purchase: PurchaseRecord,
        productType: String,
        adjustDedupId: String
    ) {
        val event = AdjustEvent(eventToken).apply {
            setRevenue(price, currency)
            setProductId(purchase.productId)
            setPurchaseToken(purchase.purchaseToken)
            setOrderId(purchase.orderId)
            setDeduplicationId(adjustDedupId)
            addCallbackParameter("product_type", productType)
        }
        Adjust.verifyAndTrackPlayStorePurchase(event) { result ->
            Log.d(TAG, "Verification: status=${result.verificationStatus} code=${result.code} message=${result.message}")
        }
        Log.d(
            TAG,
            "TRACKED | price=$price $currency" +
                    " | productId=${purchase.productId}" +
                    " | productType=$productType" +
                    " | orderId=${purchase.orderId}" +
                    " | dedupId=$adjustDedupId" +
                    " | purchaseTime=${purchase.purchaseTime}"
        )
    }

    /**
     * Parse ISO 8601 billing period string into (years, months, days).
     * Handles: P1Y, P1M, P3M, P6M, P1W, P2W, P7D, P3D, P30D, etc.
     * Weeks (P1W) are converted to days (7D) since Calendar has no week field.
     * Returns null if the string cannot be parsed.
     */
    private fun parsePeriod(billingPeriod: String): Triple<Int, Int, Int>? {
        // ISO 8601: P[nY][nM][nW][nD]
        val regex = Regex("""^P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)W)?(?:(\d+)D)?$""")
        val match = regex.matchEntire(billingPeriod) ?: run {
            Log.e(TAG, "Failed to parse billingPeriod: $billingPeriod")
            return null
        }
        val years = match.groupValues[1].toIntOrNull() ?: 0
        val months = match.groupValues[2].toIntOrNull() ?: 0
        val weeks = match.groupValues[3].toIntOrNull() ?: 0
        val days = match.groupValues[4].toIntOrNull() ?: 0
        return Triple(years, months, weeks * 7 + days)
    }

    /** Add a calendar-aware period (years, months, days) to an epoch-millisecond timestamp. */
    private fun addPeriod(epochMs: Long, period: Triple<Int, Int, Int>): Long {
        val (years, months, days) = period
        val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMs
        if (years != 0) cal.add(Calendar.YEAR, years)
        if (months != 0) cal.add(Calendar.MONTH, months)
        if (days != 0) cal.add(Calendar.DAY_OF_MONTH, days)
        return cal.timeInMillis
    }

    private fun cycleKey(token: String, chargeTimeMs: Long) = "cycle_${token}_${chargeTimeMs}"

    companion object {
        private const val TAG = "AdjustIapTracker"
        private const val PREFS_NAME = "adjust_iap_tracker"
    }
}