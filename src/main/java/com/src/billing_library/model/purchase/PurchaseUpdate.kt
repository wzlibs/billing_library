package com.src.billing_library.model.purchase

sealed class PurchaseUpdate {
    data class Succeeded(
        val purchases: List<PurchasedItem>
    ) : PurchaseUpdate()

    object AlreadyOwned : PurchaseUpdate()

    object UserCanceled : PurchaseUpdate()

    object Error : PurchaseUpdate()
}
