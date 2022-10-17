package com.sample.android.trivialdrivesample.billing

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.amazon.device.iap.PurchasingListener
import com.amazon.device.iap.PurchasingService
import com.amazon.device.iap.model.*
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.consumePurchase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val SKU_DETAILS_REQUERY_TIME = 1000L * 60L * 60L * 4L // 4 hours

class AmazonAppstoreIAPDataSource private constructor(
    application: Application,
    private val defaultScope: CoroutineScope,
    knownInappSKUs: Array<String>?,
    knownSubscriptionSKUs: Array<String>?,
    autoConsumeSKUs: Array<String>?
) : LifecycleObserver, PurchasingListener, IBillingDataSource {

    private enum class SkuState {
        SKU_STATE_UNPURCHASED, SKU_STATE_PENDING, SKU_STATE_PURCHASED, SKU_STATE_PURCHASED_AND_ACKNOWLEDGED
    }

    // known SKUs (used to query sku data and validate responses)
    private val knownInappSKUs: List<String>?
    private val knownSubscriptionSKUs: List<String>?

    // SKUs to auto-consume
    private val knownAutoConsumeSKUs: MutableSet<String>

    // when was the last successful SkuDetailsResponse?
    private var skuDetailsResponseTime = -SKU_DETAILS_REQUERY_TIME

    // Flows that are mostly maintained so they can be transformed into observables.
    private val skuStateMap: MutableMap<String, MutableStateFlow<SkuState>> = HashMap()
    private val skuDetailsMap: MutableMap<String, MutableStateFlow<Product?>> = HashMap()

    // Observables that are used to communicate state.
    private val purchaseConsumptionInProcess: MutableSet<String> = HashSet()
    private val newPurchaseFlow = MutableSharedFlow<List<String>>(extraBufferCapacity = 1)
    private val purchaseConsumedFlow = MutableSharedFlow<List<String>>()
    private val billingFlowInProcess = MutableStateFlow(false)

    /**
     * This is a flow that is used to observe consumed purchases.
     * @return Flow that contains skus of the consumed purchases.
     */
    override fun getConsumedPurchases() = purchaseConsumedFlow.asSharedFlow()

    /**
     * Returns whether or not the user has purchased a SKU. It does this by returning
     * a Flow that returns true if the SKU is in the PURCHASED state and
     * the Purchase has been acknowledged.
     * @return a Flow that observes the SKUs purchase state
     */
    override fun isPurchased(sku: String): Flow<Boolean> {
        val skuStateFLow = skuStateMap[sku]!!
        return skuStateFLow.map { skuState -> skuState == SkuState.SKU_STATE_PURCHASED_AND_ACKNOWLEDGED }
    }

    // There's lots of information in SkuDetails, but our app only needs a few things, since our
    // goods never go on sale, have introductory pricing, etc. You can add to this for your app,
    // or create your own class to pass the information across.
    /**
     * The title of our SKU from SkuDetails.
     * @param sku to get the title from
     * @return title of the requested SKU as an observable Flow<String>
    </String> */
    override fun getSkuTitle(sku: String): Flow<String> {
        val skuDetailsFlow = skuDetailsMap[sku]!!
        return skuDetailsFlow.mapNotNull { skuDetails ->
            skuDetails?.title
        }
    }

    override fun getSkuPrice(sku: String): Flow<String> {
        val skuDetailsFlow = skuDetailsMap[sku]!!
        return skuDetailsFlow.mapNotNull { skuDetails ->
            skuDetails?.price
        }
    }

    override fun getSkuDescription(sku: String): Flow<String> {
        val skuDetailsFlow = skuDetailsMap[sku]!!
        return skuDetailsFlow.mapNotNull { skuDetails ->
            skuDetails?.description
        }
    }

    /**
     * Consumes an in-app purchase. Interested listeners can watch the purchaseConsumed LiveEvent.
     * To make things easy, you can send in a list of SKUs that are auto-consumed by the
     * BillingDataSource.
     */
    override suspend fun consumeInappPurchase(sku: String) {
        setSkuState(sku, SkuState.SKU_STATE_UNPURCHASED)
    }

    /**
     * Returns a Flow that reports if a billing flow is in process, meaning that
     * launchBillingFlow has returned BillingResponseCode.OK and onPurchasesUpdated hasn't yet
     * been called.
     * @return Flow that indicates the known state of the billing flow.
     */
    override fun getBillingFlowInProcess(): Flow<Boolean> {
        return billingFlowInProcess.asStateFlow()
    }

    /**
     * Returns whether or not the user can purchase a SKU. It does this by returning
     * a Flow combine transformation that returns true if the SKU is in the UNSPECIFIED state, as
     * well as if we have skuDetails for the SKU. (SKUs cannot be purchased without valid
     * SkuDetails.)
     * @return a Flow that observes the SKUs purchase state
     */
    override fun canPurchase(sku: String): Flow<Boolean> {
        val skuDetailsFlow = skuDetailsMap[sku]!!
        val skuStateFlow = skuStateMap[sku]!!

        return skuStateFlow.combine(skuDetailsFlow) { skuState, skuDetails ->
            skuState == SkuState.SKU_STATE_UNPURCHASED && skuDetails != null
        }
    }

    /**
     * Launch the billing flow. This will launch an external Activity for a result, so it requires
     * an Activity reference. For subscriptions, it supports upgrading from one SKU type to another
     * by passing in SKUs to be upgraded.
     *
     * @param activity active activity to launch our billing flow from
     * @param sku SKU (Product ID) to be purchased
     * @param upgradeSkusVarargs SKUs that the subscription can be upgraded from
     * @return true if launch is successful
     */
    override fun launchBillingFlow(activity: Activity?, sku: String, vararg upgradeSkusVarargs: String) {
        val skuDetails = skuDetailsMap[sku]?.value
        if (null != skuDetails) {
            defaultScope.launch {
                PurchasingService.purchase(skuDetails.sku)
            }
        } else {
            Log.e(TAG, "SkuDetails not found for: $sku")
        }
    }

    /**
     * Creates a Flow object for every known SKU so the state and SKU details can be observed
     * in other layers. The repository is responsible for mapping this data in ways that are more
     * useful for the application.
     */
    private fun initializeFlows() {
        addSkuFlows(knownInappSKUs)
        addSkuFlows(knownSubscriptionSKUs)
    }

    /**
     * Calls the billing client functions to query sku details for both the inapp and subscription
     * SKUs. SKU details are useful for displaying item names and price lists to the user, and are
     * required to make a purchase.
     */
    private fun querySkuDetailsAsync() {
        if (!knownInappSKUs.isNullOrEmpty()) {
            val skuDetailsResult = PurchasingService.getProductData(
                knownInappSKUs.toHashSet()
            )
        }
        if (!knownSubscriptionSKUs.isNullOrEmpty()) {
            val skuDetailsResult = PurchasingService.getProductData(
                knownSubscriptionSKUs.toHashSet()
            )
        }
    }

    /**
     * Called by initializeFlows to create the various Flow objects we're planning to emit.
     * @param skuList a List<String> of SKUs representing purchases and subscriptions.
    </String> */
    private fun addSkuFlows(skuList: List<String>?) {
        for (sku in skuList!!) {
            val skuState = MutableStateFlow(SkuState.SKU_STATE_UNPURCHASED)
            val details = MutableStateFlow<Product?>(null)
            details.subscriptionCount.map { count -> count > 0 } // map count into active/inactive flag
                .distinctUntilChanged() // only react to true<->false changes
                .onEach { isActive -> // configure an action
                    if (isActive && (SystemClock.elapsedRealtime() - skuDetailsResponseTime > SKU_DETAILS_REQUERY_TIME)) {
                        skuDetailsResponseTime = SystemClock.elapsedRealtime()
                        Log.v(TAG, "Skus not fresh, requerying")
                        querySkuDetailsAsync()
                    }
                }
                .launchIn(defaultScope) // launch it
            skuStateMap[sku] = skuState
            skuDetailsMap[sku] = details
        }
    }

    override fun getNewPurchases() = newPurchaseFlow.asSharedFlow()

    /*
      GPBLv3 now queries purchases synchronously, simplifying this flow. This only gets active
      purchases.
   */
    override suspend fun refreshPurchases() {
        Log.d(TAG, "Refreshing purchases.")
       // PurchasingService.getPurchaseUpdates(true)
    }


    /**
     * Goes through each purchase and makes sure that the purchase state is processed and the state
     * is available through Flows. Verifies signature and acknowledges purchases. PURCHASED isn't
     * returned until the purchase is acknowledged.
     *
     * https://developer.android.com/google/play/billing/billing_library_releases_notes#2_0_acknowledge
     *
     * Developers can choose to acknowledge purchases from a server using the
     * Google Play Developer API. The server has direct access to the user database,
     * so using the Google Play Developer API for acknowledgement might be more reliable.
     *
     * If the purchase token is not acknowledged within 3 days,
     * then Google Play will automatically refund and revoke the purchase.
     * This behavior helps ensure that users are not charged unless the user has successfully
     * received access to the content.
     * This eliminates a category of issues where users complain to developers
     * that they paid for something that the app is not giving to them.
     *
     * If a skusToUpdate list is passed-into this method, any purchases not in the list of
     * purchases will have their state set to UNPURCHASED.
     *
     * @param purchases the List of purchases to process.
     * @param skusToUpdate a list of skus that we want to update the state from --- this allows us
     * to set the state of non-returned SKUs to UNPURCHASED.
     */
    private fun processPurchaseList(purchases: List<Receipt>?, skusToUpdate: List<String>?) {
        val updatedSkus = HashSet<String>()
        if (null != purchases) {
            for (purchase in purchases) {
                for (sku in purchase.sku) {
                    val skuStateFlow = skuStateMap[sku.toString()]
                    if (null == skuStateFlow) {
                        Log.e(
                            TAG,
                            "Unknown SKU " + sku + ". Check to make " +
                                    "sure SKU matches SKUS in the Play developer console."
                        )
                        continue
                    }
                    updatedSkus.add(sku.toString())
                }
                // only set the purchased state after we've validated the signature.
                setSkuStateFromPurchase(purchase)
                var isConsumable = false
                defaultScope.launch {
                    for (sku in purchase.sku) {
                        if (knownAutoConsumeSKUs.contains(sku.toString())) {
                            isConsumable = true
                        } else {
                            if (isConsumable) {
                                Log.e(
                                    TAG, "Purchase cannot contain a mixture of consumable" +
                                            "and non-consumable items: " + purchase.sku.toString()
                                )
                                isConsumable = false
                                break
                            }
                        }
                    }
                    if (isConsumable) {
                        consumePurchase(purchase.receiptId, purchase.sku)
                        newPurchaseFlow.tryEmit(purchases.map { it.sku })
                    } else {
                        PurchasingService.notifyFulfillment(purchase.receiptId, FulfillmentResult.FULFILLED)
                        setSkuState(purchase.sku, SkuState.SKU_STATE_PURCHASED_AND_ACKNOWLEDGED)
                        newPurchaseFlow.tryEmit(purchases.map { it.sku })
                    }
                }
            }
        }
        if (null != skusToUpdate) {
            for (sku in skusToUpdate) {
                if (!updatedSkus.contains(sku)) {
                    setSkuState(sku, SkuState.SKU_STATE_UNPURCHASED)
                }
            }
        }
    }

    /**
     * Internal call only. Assumes that all signature checks have been completed and the purchase
     * is ready to be consumed. If the sku is already being consumed, does nothing.
     * @param purchase purchase to consume
     */
    private fun consumePurchase(receiptId: String, productSku: String) {
        // weak check to make sure we're not already consuming the sku
        if (purchaseConsumptionInProcess.contains(productSku)) {
            // already consuming
            return
        }
        purchaseConsumptionInProcess.add(productSku)
        if (receiptId.isNotEmpty()) {
            val consumePurchaseResult =
                PurchasingService.notifyFulfillment(receiptId, FulfillmentResult.FULFILLED)
        }

        purchaseConsumptionInProcess.remove(productSku)

    }

    /**
     * Calling this means that we have the most up-to-date information for a Sku in a purchase
     * object. This uses the purchase state (Pending, Unspecified, Purchased) along with the
     * acknowledged state.
     * @param purchase an up-to-date object to set the state for the Sku
     */
    private fun setSkuStateFromPurchase(purchase: Receipt) {
        for (purchaseSku in purchase.sku) {
            val skuStateFlow = skuStateMap[purchaseSku.toString()]
            if (null == skuStateFlow) {
                Log.e(
                    TAG,
                    "Unknown SKU " + purchaseSku + ". Check to make " +
                            "sure SKU matches SKUS in the Play developer console."
                )
            } else {

                skuStateFlow.tryEmit(SkuState.SKU_STATE_PURCHASED_AND_ACKNOWLEDGED)

            }
        }
    }


    /**
     * It's recommended to requery purchases during onResume.
     */
    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun resume() {
        Log.d(TAG, "ON_RESUME")
        // this just avoids an extra purchase refresh after we finish a billing flow
        if (!billingFlowInProcess.value) {
            defaultScope.launch {
                refreshPurchases()
            }
        }
    }


    /**
     * Since we (mostly) are getting sku states when we actually make a purchase or update
     * purchases, we keep some internal state when we do things like acknowledge or consume.
     * @param sku product ID to change the state of
     * @param newSkuState the new state of the sku.
     */
    private fun setSkuState(sku: String, newSkuState: SkuState) {
        val skuStateFlow = skuStateMap[sku]
        skuStateFlow?.tryEmit(newSkuState)
            ?: Log.e(
                TAG,
                "Unknown SKU " + sku + ". Check to make " +
                        "sure SKU matches SKUS in the Play developer console."
            )
    }

    override fun onUserDataResponse(userDataResponse: UserDataResponse) {
        TODO("Not yet implemented")
    }

    override fun onProductDataResponse(productDataResponse: ProductDataResponse) {
        val requestStatus = productDataResponse.requestStatus
        val debugMessage = productDataResponse.toString()
        val skuDetailsList = productDataResponse.productData
        when (requestStatus) {
            ProductDataResponse.RequestStatus.SUCCESSFUL -> {
                Log.i(TAG, "onSkuDetailsResponse: $debugMessage")
                if (skuDetailsList == null || skuDetailsList.isEmpty()) {
                    Log.e(
                        TAG,
                        "onSkuDetailsResponse: " +
                                "Found null or empty SkuDetails. " +
                                "Check to see if the SKUs you requested are correctly published " +
                                "in the Amazon Appstore"
                    )
                } else {
                    for (skuDetails in skuDetailsList) {
                        val (id, product) = skuDetails
                        val sku = product.sku
                        val detailsMutableFlow = skuDetailsMap[sku]
                        detailsMutableFlow?.tryEmit(product)
                            ?: Log.e(TAG, "Unknown sku: $sku")
                    }
                }
            }
            ProductDataResponse.RequestStatus.NOT_SUPPORTED,
            ProductDataResponse.RequestStatus.FAILED ->
                Log.wtf(TAG, "onSkuDetailsResponse: $debugMessage")
            else -> Log.wtf(TAG, "onSkuDetailsResponse: $debugMessage")
        }
        if (requestStatus == ProductDataResponse.RequestStatus.SUCCESSFUL) {
            skuDetailsResponseTime = SystemClock.elapsedRealtime()
        } else {
            skuDetailsResponseTime = -SKU_DETAILS_REQUERY_TIME
        }
    }


    override fun onPurchaseResponse(purchaseResponse: PurchaseResponse) {
        if (purchaseResponse.requestStatus == PurchaseResponse.RequestStatus.SUCCESSFUL) {
            defaultScope.launch { billingFlowInProcess.emit(true) }
        } else {
            Log.e(TAG, "Billing failed: + $purchaseResponse")
        }

        //if (purchaseResponse.receipt.productType == ProductType.CONSUMABLE) {
            if (purchaseResponse.requestStatus == PurchaseResponse.RequestStatus.SUCCESSFUL) {
                Log.d(TAG, "Consumption successful. Emitting sku.")
                defaultScope.launch {
                    purchaseConsumedFlow.emit(listOf(purchaseResponse.receipt.sku.toString()))
                }
                // Since we've consumed the purchase
                // for (sku in purchase.skus) {
                setSkuState(purchaseResponse.receipt.sku.toString(), SkuState.SKU_STATE_UNPURCHASED)
            } else {
                Log.e(
                    TAG,
                    "Error while consuming: $purchaseResponse"
                )
            }
        //}
        PurchasingService.getPurchaseUpdates(true)

    }

    override fun onPurchaseUpdatesResponse(purchaseUpdatesResponse: PurchaseUpdatesResponse) {
        val billingResult = purchaseUpdatesResponse.requestStatus
        if (billingResult != PurchaseUpdatesResponse.RequestStatus.SUCCESSFUL) {
            Log.e(TAG, "Problem getting purchases: $billingResult")
        } else {
            processPurchaseList(purchaseUpdatesResponse.receipts, knownInappSKUs)
        }
    }

    companion object {
        private val TAG = "TrivialDrive:" + AmazonAppstoreIAPDataSource::class.java.simpleName

        @Volatile
        private var sInstance: AmazonAppstoreIAPDataSource? = null
        private val handler = Handler(Looper.getMainLooper())

        // Standard boilerplate double check locking pattern for thread-safe singletons.
        @JvmStatic
        fun getInstance(
            application: Application,
            defaultScope: CoroutineScope,
            knownInappSKUs: Array<String>?,
            knownSubscriptionSKUs: Array<String>?,
            autoConsumeSKUs: Array<String>?
        ) = sInstance ?: synchronized(this) {
            sInstance ?: AmazonAppstoreIAPDataSource(
                application,
                defaultScope,
                knownInappSKUs,
                knownSubscriptionSKUs,
                autoConsumeSKUs
            )
                .also { sInstance = it }
        }
    }


    /**
     * Our constructor.  Since we are a singleton, this is only used internally.
     * @param application Android application class.
     * @param knownInappSKUs SKUs of in-app purchases the source should know about
     * @param knownSubscriptionSKUs SKUs of subscriptions the source should know about
     */
    init {
        this.knownInappSKUs = if (knownInappSKUs == null) {
            ArrayList()
        } else {
            listOf(*knownInappSKUs)
        }
        this.knownSubscriptionSKUs = if (knownSubscriptionSKUs == null) {
            ArrayList()
        } else {
            listOf(*knownSubscriptionSKUs)
        }
        knownAutoConsumeSKUs = HashSet()
        if (autoConsumeSKUs != null) {
            knownAutoConsumeSKUs.addAll(listOf(*autoConsumeSKUs))
        }
        initializeFlows()

        PurchasingService.registerListener(application, this)

    }
}