package com.src.billing_library.model.product

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.src.billing_library.model.product.OneTimePurchaseOfferDetails.Companion.toOneTimePurchaseOfferDetails
import com.src.billing_library.model.product.SubscriptionOfferDetail.Companion.toSubscriptionOfferDetails
import java.util.Locale

data class BillingProductDetail(
    val productId: String,
    val name: String,
    val productType: String,
    val description: String,
    val oneTimePurchaseOfferDetails: OneTimePurchaseOfferDetails?,
    val subscriptionOfferDetails: List<SubscriptionOfferDetail>?
) {

    companion object {
        fun ProductDetails.toBillingProductDetail(): BillingProductDetail {
            return BillingProductDetail(
                productId = productId,
                name = name,
                description = description,
                productType = productType,
                oneTimePurchaseOfferDetails = oneTimePurchaseOfferDetails?.toOneTimePurchaseOfferDetails(),
                subscriptionOfferDetails = subscriptionOfferDetails?.toSubscriptionOfferDetails()
            )
        }
    }

    fun getFormattedPrice(): String {
        return if (isSubscription()) {
            basePlanOffer()?.recurringPhase()?.formattedPrice ?: ""
        } else {
            oneTimePurchaseOfferDetails?.formattedPrice ?: ""
        }
    }

    fun isSubscription(): Boolean = productType == BillingClient.ProductType.SUBS

    fun getRecurringPricePerDay(days: Int): String {
        val micros = getRecurringPriceAmountMicros()
        val currency = getRecurringPriceCurrencyCode()
        if (micros == 0L) return String.format(Locale.US, "%.2f %s", 0.0, currency)
        val perDay = micros / 1_000_000.0 / days
        return String.format(Locale.US, "%.2f %s", perDay, currency)
    }

    /** Returns the recurring (base) price in micros — excludes trial/intro phases. */
    fun getRecurringPriceAmountMicros(): Long {
        return if (isSubscription()) {
            basePlanOffer()?.recurringPhase()?.priceAmountMicros ?: 0
        } else {
            oneTimePurchaseOfferDetails?.priceAmountMicros ?: 0
        }
    }

    /** Returns the recurring (base) price currency code — excludes trial/intro phases. */
    fun getRecurringPriceCurrencyCode(): String {
        return if (isSubscription()) {
            basePlanOffer()?.recurringPhase()?.priceCurrencyCode ?: ""
        } else {
            oneTimePurchaseOfferDetails?.priceCurrencyCode ?: ""
        }
    }

    private fun basePlanOffer(): SubscriptionOfferDetail? {
        return subscriptionOfferDetails?.find { it.offerId == null }
    }

    /**
     * Picks the best offer for a default purchase flow, in priority order:
     * 1. Free trial — offer with any phase where priceAmountMicros == 0
     *    (recurrenceMode can be 2=FINITE or 3=NON_RECURRING, both are valid trial shapes)
     * 2. Introductory/promo offer — offer with a finite-recurring (mode 2) paid phase
     * 3. Base plan — offer with offerId == null
     */
    fun bestSubscriptionOffer(): SubscriptionOfferDetail? {
        val offers = subscriptionOfferDetails ?: return null

        // 1. Free trial: any phase with price = 0 (trial phase, regardless of recurrenceMode)
        val freeTrial = offers.firstOrNull { offer ->
            offer.pricingPhases.any { it.priceAmountMicros == 0L }
        }
        if (freeTrial != null) return freeTrial

        // 2. Intro price: finite-recurring phase with actual price (mode 2, price > 0)
        val introOffer = offers.firstOrNull { offer ->
            offer.pricingPhases.any { it.recurrenceMode == 2 && it.priceAmountMicros > 0 }
        }
        if (introOffer != null) return introOffer

        // 3. Base plan (offerId == null)
        return basePlanOffer()
    }

    override fun toString(): String {
        return "BillingProductDetail(" +
                "productId='$productId', " +
                "name='$name', " +
                "productType='$productType', " +
                "description='$description', " +
                "oneTimePurchaseOfferDetails=$oneTimePurchaseOfferDetails, " +
                "subscriptionOfferDetails=${
                    subscriptionOfferDetails?.joinToString(
                        prefix = "[",
                        postfix = "]"
                    )
                }" +
                ")"
    }

}