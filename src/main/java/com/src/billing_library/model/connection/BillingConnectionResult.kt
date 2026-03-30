package com.src.billing_library.model.connection

sealed class BillingConnectionResult {
    object Connected : BillingConnectionResult()
    object Disconnected : BillingConnectionResult()
    object Failed : BillingConnectionResult()
}
