package com.src.billing_library

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.src.billing_library.model.BillingQueryResult
import com.src.billing_library.model.connection.BillingConnectionResult
import com.src.billing_library.model.product.BillingProduct
import com.src.billing_library.model.product.BillingProductType
import com.src.billing_library.model.purchase.PurchaseUpdate
import com.src.billing_library.model.purchase.PurchasedItem
import com.src.billing_library.model.product.BillingProductDetail
import com.src.billing_library.model.product.BillingProductDetail.Companion.toBillingProductDetail
import com.src.billing_library.model.purchase.PurchaseRecord
import com.src.billing_library.model.purchase.PurchaseRecord.Companion.toPurchaseRecords
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private typealias ProductId = String

class GooglePlayBillingLibrary(
    context: Context,
    // Optional: truyền vào nếu muốn track Adjust, bỏ qua nếu không cần
    private val adjustTracker: AdjustIapTracker? = null
) : BillingLibrary {

    companion object {
        private const val TAG = "GooglePlayBillingLibrar"
    }

    private val detailProducts = HashMap<ProductId, ProductDetails>()
    private val billingProducts = HashMap<ProductId, BillingProduct>()

    private var purchaseUpdateListener: ((PurchaseUpdate) -> Unit)? = null

    override fun setPurchaseUpdateListener(listener: ((PurchaseUpdate) -> Unit)?) {
        purchaseUpdateListener = listener
    }

    private fun emitUpdate(update: PurchaseUpdate) {
        purchaseUpdateListener?.invoke(update)
    }

    private val billingClient: BillingClient by lazy {
        BillingClient.newBuilder(context).setListener(purchasesListener).enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        ).build()
    }

    private val purchasesListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                handleSuccessfulPurchases(purchases)
            }

            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                emitUpdate(PurchaseUpdate.AlreadyOwned)

            BillingClient.BillingResponseCode.USER_CANCELED ->
                emitUpdate(PurchaseUpdate.UserCanceled)

            else -> emitUpdate(PurchaseUpdate.Error)
        }
    }

    private fun handleSuccessfulPurchases(purchases: List<Purchase>?) {
        Log.d(TAG, "handleSuccessfulPurchases: $purchases")
        if (purchases.isNullOrEmpty()) {
            // OK with no purchases — item likely already owned from a previous session
            emitUpdate(PurchaseUpdate.AlreadyOwned)
            return
        }
        val purchaseRecords = purchases.flatMap { it.toPurchaseRecords() }

        // Side effects: track + consume/acknowledge for each purchased record
        purchaseRecords.forEach { purchaseRecord ->
            Log.d(TAG, "purchaseRecord: $purchaseRecord")
            if (purchaseRecord.isPurchased) {
                val billingProductDetail =
                    detailProducts[purchaseRecord.productId]?.toBillingProductDetail()
                adjustTracker?.trackPurchase(
                    purchaseRecord,
                    billingProductDetail
                )
                val isConsume =
                    billingProducts[purchaseRecord.productId]?.type == BillingProductType.CONSUMABLE
                consumeOrAcknowledge(purchaseRecord, isConsume)
            }
        }

        // Emit once after all side effects are done
        val succeededRecords = purchaseRecords
            .filter { it.isPurchased }
            .map {
                PurchasedItem(
                    it,
                    detailProducts[it.productId]?.toBillingProductDetail()
                )
            }

        if (succeededRecords.isNotEmpty()) {
            emitUpdate(PurchaseUpdate.Succeeded(succeededRecords))
        } else {
            // All purchases are PENDING — Google Play will call this listener again
            // when payment is confirmed. No action needed here.
            Log.d(TAG, "All purchases PENDING, waiting for payment confirmation")
        }
    }

    override suspend fun connect(): BillingConnectionResult {
        val deferred = CompletableDeferred<BillingConnectionResult>()
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingServiceDisconnected() {
                deferred.complete(BillingConnectionResult.Disconnected)
            }

            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    deferred.complete(BillingConnectionResult.Connected)
                } else {
                    deferred.complete(BillingConnectionResult.Failed)
                }
            }
        })
        return deferred.await()
    }

    override suspend fun queryProductDetailsAndPurchases(
        products: List<BillingProduct>
    ): BillingQueryResult = coroutineScope {
        products.forEach { billingProducts[it.productId] = it }

        val productDetailsDeferred = async { fetchProductDetails(products) }
        val purchasesDeferred = async { fetchPurchases() }

        val fetchedDetailProducts = productDetailsDeferred.await()
        val purchaseRecords = purchasesDeferred.await()

        detailProducts.putAll(fetchedDetailProducts)
        val billingProductDetails = fetchedDetailProducts.values.map { it.toBillingProductDetail() }

        purchaseRecords.forEach { purchaseRecord ->
            if (purchaseRecord.isPurchased) {
                val isConsume =
                    billingProducts[purchaseRecord.productId]?.type == BillingProductType.CONSUMABLE
                consumeOrAcknowledge(purchaseRecord, isConsume)
            }
        }

        if (adjustTracker != null) {
            val productDetailMap =
                fetchedDetailProducts.mapValues { it.value.toBillingProductDetail() }
            adjustTracker.trackPurchases(
                purchases = purchaseRecords,
                productDetails = productDetailMap
            )
        }

        BillingQueryResult(billingProductDetails, purchaseRecords)
    }

    private suspend fun fetchProductDetails(products: List<BillingProduct>): Map<ProductId, ProductDetails> {
        val all = mutableListOf<ProductDetails>()
        val subsIds = products.filter { it.type == BillingProductType.SUBS }.map { it.productId }
        val inAppIds = products.filter { it.type != BillingProductType.SUBS }.map { it.productId }
        if (subsIds.isNotEmpty()) {
            val params = subsIds.map {
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(it)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            }
            val deferred = CompletableDeferred<List<ProductDetails>>()
            billingClient.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(params).build()
            ) { _, details -> deferred.complete(details.productDetailsList) }
            all.addAll(deferred.await())
        }
        if (inAppIds.isNotEmpty()) {
            val params = inAppIds.map {
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(it)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            }
            val deferred = CompletableDeferred<List<ProductDetails>>()
            billingClient.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(params).build()
            ) { _, details -> deferred.complete(details.productDetailsList) }
            all.addAll(deferred.await())
        }
        return all.associateBy { it.productId }
    }

    private suspend fun fetchPurchases(): List<PurchaseRecord> {
        val all = mutableListOf<Purchase>()

        val subsDeferred = CompletableDeferred<List<Purchase>>()
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS).build()
        ) { _, list -> subsDeferred.complete(list) }
        all.addAll(subsDeferred.await())

        val inAppDeferred = CompletableDeferred<List<Purchase>>()
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP).build()
        ) { _, list -> inAppDeferred.complete(list) }
        all.addAll(inAppDeferred.await())

        return all.flatMap { it.toPurchaseRecords() }
    }

    private fun consumeOrAcknowledge(
        purchase: PurchaseRecord,
        isConsume: Boolean
    ) {
        val purchaseToken = purchase.purchaseToken
        val isAcknowledged = purchase.isAcknowledged
        if (isConsume) {
            Log.d(TAG, "purchaseRecord is Consume")
            // consumeAsync implicitly acknowledges — always call for consumables
            val consumeParams = ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build()
            billingClient.consumeAsync(consumeParams) { _, _ -> }
        } else if (!isAcknowledged) {
            // Only acknowledge once for SUBS / NON_CONSUMABLE
            Log.d(TAG, "purchaseRecord is Acknowledged")
            val params =
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()
            billingClient.acknowledgePurchase(params) { billingResult ->
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.e(TAG, "acknowledgePurchase failed: ${billingResult.debugMessage}")
                }
            }
        }
    }

    override fun purchase(activity: Activity, billingProductDetail: BillingProductDetail) {
        val productDetail: ProductDetails = detailProducts[billingProductDetail.productId] ?: run {
            emitUpdate(PurchaseUpdate.Error)
            return
        }

        val productDetailsParamsList = when {
            billingProductDetail.isSubscription() -> {
                val offer = billingProductDetail.bestSubscriptionOffer() ?: run {
                    Log.e(TAG, "No subscription offer found for: ${billingProductDetail.productId}")
                    emitUpdate(PurchaseUpdate.Error)
                    return
                }
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetail)
                        .setOfferToken(offer.offerToken)
                        .build()
                )
            }

            else -> listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetail)
                    .build()
            )
        }

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()
        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    override fun endConnection() {
        billingClient.endConnection()
    }
}