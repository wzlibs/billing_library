package com.src.billing_library.model.purchase

import com.android.billingclient.api.Purchase

data class PurchaseRecord(
    val productId: String,
    val purchaseToken: String,
    val purchaseTime: Long = 0L,
    val orderId: String?,
    val isPurchased: Boolean = false,
    val isAcknowledged: Boolean = false
) {

    companion object {
        /**
         * Google Play Billing hỗ trợ multi-product purchase (Purchase.products có thể có N phần tử).
         * purchaseToken, isPurchased, isAcknowledged là như nhau cho tất cả product trong cùng 1 purchase.
         */
        fun Purchase.toPurchaseRecords(): List<PurchaseRecord> {
            return products.map { productId ->
                PurchaseRecord(
                    productId = productId,
                    purchaseToken = purchaseToken,
                    purchaseTime = purchaseTime,
                    orderId = orderId,
                    isPurchased = purchaseState == Purchase.PurchaseState.PURCHASED,
                    isAcknowledged = isAcknowledged
                )
            }
        }
    }

}