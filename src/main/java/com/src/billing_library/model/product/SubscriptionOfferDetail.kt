package com.src.billing_library.model.product

import com.android.billingclient.api.ProductDetails
import com.src.billing_library.model.product.PricingPhase.Companion.toPricingPhases

data class SubscriptionOfferDetail(
    val offerId: String?,
    val offerToken: String,
    val offerTags: List<String>,
    val pricingPhases: List<PricingPhase>
) {
    companion object {

        private fun ProductDetails.SubscriptionOfferDetails.toSubscriptionOfferDetail(): SubscriptionOfferDetail {
            return SubscriptionOfferDetail(
                offerId = offerId,
                offerToken = offerToken,
                offerTags = offerTags,
                pricingPhases = pricingPhases.toPricingPhases()
            )
        }

        fun List<ProductDetails.SubscriptionOfferDetails>.toSubscriptionOfferDetails(): List<SubscriptionOfferDetail> {
            return map { it.toSubscriptionOfferDetail() }
        }

    }

    /**
     * Returns the recurring pricing phase (recurrenceMode == 1 = INFINITE_RECURRING).
     * This is the true base price regardless of what order Google returns the phases in.
     * Falls back to the last phase if no INFINITE_RECURRING phase exists (defensive).
     */
    fun recurringPhase(): PricingPhase? {
        return pricingPhases.firstOrNull { it.recurrenceMode == 1 }
            ?: pricingPhases.lastOrNull()
    }

    override fun toString(): String {
        return "SubscriptionOfferDetail(" +
                "offerId=$offerId, " +
                "offerToken=$offerToken, " +
                "offerTags=$offerTags, " +
                "pricingPhases=${pricingPhases.joinToString(prefix = "[", postfix = "]")}" +
                ")"
    }

}