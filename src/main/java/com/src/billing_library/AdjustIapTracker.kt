package com.src.billing_library

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustEvent
import com.src.billing_library.model.product.BillingProductDetail
import com.src.billing_library.model.product.PricingPhase
import com.src.billing_library.model.purchase.PurchaseRecord
import java.util.Calendar

/**
 * Tracks IAP revenue to Adjust for ad-spend decision-making.
 *
 * Two tracking paths:
 * 1. [trackPurchase] — called immediately after a successful purchase (billing flow).
 *    Logs first payment only. Skips if price = 0 (trial/free intro). Deduped by cycleKey(token, purchaseTime).
 *
 * 2. [trackPurchases] — called on app open via queryPurchasesAsync.
 *    Logs the subscription cycle the user is CURRENTLY in (cycleStart <= now < cycleEnd).
 *    Skips the first cycle (already handled by trackPurchase).
 *    Does NOT catch up missed past cycles — if user was offline during cycle 2 and opens app
 *    in cycle 3, cycle 2 is skipped and only cycle 3 is logged.
 *    Deduped per cycle by cycleKey(token, cycleStart). Skips trial/free phases (price = 0).
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
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Track the initial purchase immediately after the billing flow completes.
     * Skips if price = 0 (trial or free introductory period).
     * Deduped — safe to call multiple times for the same purchase.
     */
    fun trackPurchase(
        purchase: PurchaseRecord,
        billingProductDetail: BillingProductDetail?,
    ) {
        val detail = billingProductDetail ?: return
        val (price, currency) = firstPurchasePrice(detail)

        if (price <= 0.0) {
            Log.d(TAG, "trackPurchase SKIP (trial/free): productId=${purchase.productId}")
            return
        }

        val key = cycleKey(purchase.purchaseToken, purchase.purchaseTime)
        if (isAlreadyLogged(key)) return

        sendToAdjust(price, currency, purchase, detail.productType, adjustDedupId = key)
        markLogged(key)
    }

    // -------------------------------------------------------------------------
    // Subscription renewals
    // -------------------------------------------------------------------------

    /**
     * Track subscription auto-renewal on app open.
     * Only logs the cycle the user is currently in. Missed past cycles are skipped.
     */
    fun trackPurchases(
        purchases: List<PurchaseRecord>,
        productDetails: Map<String, BillingProductDetail>
    ) {
        val now = System.currentTimeMillis()
        purchases.filter { it.isPurchased }.forEach { purchase ->
            val detail = productDetails[purchase.productId] ?: return@forEach
            if (detail.isSubscription()) {
                trackCurrentRenewalCycle(purchase, detail, now)
            }
            // Inapp one-time: no renewal cycles, nothing to do here
        }
    }

    private fun trackCurrentRenewalCycle(
        purchase: PurchaseRecord,
        detail: BillingProductDetail,
        now: Long
    ) {
        val offer = detail.bestSubscriptionOffer() ?: return
        val currentCycle = findCurrentCycle(purchase.purchaseTime, offer.pricingPhases, now) ?: return

        if (currentCycle.price <= 0.0) {
            Log.d(TAG, "trackRenewal SKIP (trial/free): productId=${purchase.productId}")
            return
        }

        val key = cycleKey(purchase.purchaseToken, currentCycle.cycleStart)
        if (isAlreadyLogged(key)) return

        sendToAdjust(
            price = currentCycle.price,
            currency = currentCycle.currency,
            purchase = purchase,
            productType = detail.productType,
            adjustDedupId = key
        )
        markLogged(key)
    }

    /**
     * Walk through pricingPhases to find the billing cycle that [now] falls into.
     * Skips the first cycle (purchaseTime) — that is handled by [trackPurchase].
     *
     * Returns null if:
     * - [now] is still within the first cycle (user hasn't renewed yet)
     * - No cycle boundary matches (e.g. subscription has ended)
     */
    private fun findCurrentCycle(
        purchaseTime: Long,
        pricingPhases: List<PricingPhase>,
        now: Long
    ): CycleInfo? {
        var cycleStart = purchaseTime
        var isFirstCycle = true

        for (phase in pricingPhases) {
            val period = parsePeriod(phase.billingPeriod) ?: break
            // recurrenceMode: 1 = INFINITE_RECURRING, 2 = FINITE_RECURRING, 3 = NON_RECURRING
            val maxCycles = if (phase.recurrenceMode == 1) Int.MAX_VALUE else phase.billingCycleCount

            for (i in 0 until maxCycles) {
                val cycleEnd = addPeriod(cycleStart, period)

                if (isFirstCycle) {
                    // Skip — trackPurchase already handled this cycle
                    isFirstCycle = false
                    cycleStart = cycleEnd
                    continue
                }

                if (cycleStart > now) return null       // gone past now, sub is in the future

                if (now < cycleEnd) {
                    // now falls within [cycleStart, cycleEnd) → this is the current active cycle
                    return CycleInfo(
                        cycleStart = cycleStart,
                        price = phase.priceAmountMicros / 1_000_000.0,
                        currency = phase.priceCurrencyCode
                    )
                }

                cycleStart = cycleEnd
            }
        }

        return null
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private data class CycleInfo(
        val cycleStart: Long,
        val price: Double,
        val currency: String
    )

    private fun isAlreadyLogged(key: String): Boolean {
        val logged = prefs.getBoolean(key, false)
        if (logged) Log.d(TAG, "SKIP (already logged): $key")
        return logged
    }

    private fun markLogged(key: String) {
        prefs.edit { putBoolean(key, true) }
    }

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