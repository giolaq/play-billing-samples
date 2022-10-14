package com.sample.android.trivialdrivesample.billing

import androidx.lifecycle.LifecycleObserver
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

interface IBillingDataSource : LifecycleObserver, PurchasesUpdatedListener,
    BillingClientStateListener {
    // Billing client, connection, cached data
    val billingClient: BillingClient

    // known SKUs (used to query sku data and validate responses)
    val knownInappSKUs: List<String>?
    val knownSubscriptionSKUs: List<String>?

    // SKUs to auto-consume
    val knownAutoConsumeSKUs: MutableSet<String>

    // how long before the data source tries to reconnect to Google play
    var reconnectMilliseconds: Long

    // when was the last successful SkuDetailsResponse?
    var skuDetailsResponseTime: Long

    // Flows that are mostly maintained so they can be transformed into observables.
    val skuStateMap: MutableMap<String, MutableStateFlow<SkuState>>
    val skuDetailsMap: MutableMap<String, MutableStateFlow<SkuDetails?>>

    // Observables that are used to communicate state.
    val purchaseConsumptionInProcess: MutableSet<Purchase>
    val newPurchaseFlow: MutableSharedFlow<List<String>>
    val purchaseConsumedFlow: MutableSharedFlow<List<String>>
    val billingFlowInProcess: MutableStateFlow<Boolean>

    enum class SkuState {
        SKU_STATE_UNPURCHASED, SKU_STATE_PENDING, SKU_STATE_PURCHASED, SKU_STATE_PURCHASED_AND_ACKNOWLEDGED
    }

    override fun onBillingSetupFinished(billingResult: BillingResult)

    /**
     * This is a pretty unusual occurrence. It happens primarily if the Google Play Store
     * self-upgrades or is force closed.
     */
    override fun onBillingServiceDisconnected()

    /**
     * Retries the billing service connection with exponential backoff, maxing out at the time
     * specified by RECONNECT_TIMER_MAX_TIME_MILLISECONDS.
     */
    fun retryBillingServiceConnectionWithExponentialBackoff()
}