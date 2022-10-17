package com.sample.android.trivialdrivesample.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow


interface IBillingDataSource {

    fun getNewPurchases(): SharedFlow<List<String>>

    suspend fun refreshPurchases()

    fun launchBillingFlow(activity: Activity?, sku: String, vararg upgradeSkusVarargs: String)

    fun isPurchased(sku: String): Flow<Boolean>

    fun canPurchase(sku: String): Flow<Boolean>


    fun getSkuTitle(sku: String): Flow<String>

    fun getSkuPrice(sku: String): Flow<String>

    fun getSkuDescription(sku: String): Flow<String>

    fun getBillingFlowInProcess(): Flow<Boolean>

    suspend fun consumeInappPurchase(sku: String)

    fun getConsumedPurchases(): SharedFlow<List<String>>

}