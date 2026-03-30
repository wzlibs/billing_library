package com.src.billing_library

import android.app.Activity
import com.src.billing_library.model.BillingQueryResult
import com.src.billing_library.model.connection.BillingConnectionResult
import com.src.billing_library.model.product.BillingProduct
import com.src.billing_library.model.purchase.PurchaseUpdate
import com.src.billing_library.model.product.BillingProductDetail
import com.src.billing_library.model.purchase.PurchaseRecord

interface BillingLibrary {

    fun setPurchaseUpdateListener(listener: ((PurchaseUpdate) -> Unit)?)

    suspend fun connect(): BillingConnectionResult

    suspend fun queryProductDetailsAndPurchases(
        products: List<BillingProduct>
    ): BillingQueryResult

    fun purchase(activity: Activity, billingProductDetail: BillingProductDetail)

    fun endConnection()

}