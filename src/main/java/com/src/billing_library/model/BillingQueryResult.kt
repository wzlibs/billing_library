package com.src.billing_library.model

import com.src.billing_library.model.product.BillingProductDetail
import com.src.billing_library.model.purchase.PurchaseRecord

data class BillingQueryResult(
    val productDetails: List<BillingProductDetail>,
    val purchaseRecords: List<PurchaseRecord>
)
