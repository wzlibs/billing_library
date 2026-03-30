package com.src.billing_library.model.purchase

import com.src.billing_library.model.product.BillingProductDetail

data class PurchasedItem(
    val record: PurchaseRecord,
    val productDetail: BillingProductDetail?
)