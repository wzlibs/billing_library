package com.src.billing_library.model.product

import com.android.billingclient.api.ProductDetails

data class OneTimePurchaseOfferDetails(
    val formattedPrice: String,
    val priceAmountMicros: Long,
    val priceCurrencyCode: String
) : PriceAble {

    companion object {
        fun ProductDetails.OneTimePurchaseOfferDetails.toOneTimePurchaseOfferDetails(): OneTimePurchaseOfferDetails {
            return OneTimePurchaseOfferDetails(
                formattedPrice = formattedPrice,
                priceAmountMicros = priceAmountMicros,
                priceCurrencyCode = priceCurrencyCode
            )
        }
    }

    override fun getReadAblePrice(): String {
        return formattedPrice
    }

}