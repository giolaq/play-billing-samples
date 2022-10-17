/*
 * Copyright (C) 2021 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sample.android.trivialdrivesample

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import com.sample.android.trivialdrivesample.billing.AmazonAppstoreIAPDataSource
import com.sample.android.trivialdrivesample.billing.BillingDataSource
import com.sample.android.trivialdrivesample.billing.IBillingDataSource
import com.sample.android.trivialdrivesample.db.GameStateModel
import kotlinx.coroutines.GlobalScope


class TrivialDriveApplication : Application() {
    lateinit var appContainer: AppContainer

    // Container of objects shared across the whole app
    inner class AppContainer(private val billingDataSource: IBillingDataSource) {
        private val applicationScope = GlobalScope
        private val gameStateModel = GameStateModel(this@TrivialDriveApplication)
        val trivialDriveRepository = TrivialDriveRepository(
            this.billingDataSource,
            gameStateModel,
            applicationScope
        )
    }

    private fun getInstallerPackageName(): String? {
        try {
            val packageName: String = packageName
            val pm: PackageManager = packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = pm.getInstallSourceInfo(packageName)
                return info.installingPackageName
            }
            @Suppress("DEPRECATION")
            return pm.getInstallerPackageName(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
        }
        return ""
    }
    override fun onCreate() {
        super.onCreate()


        val installerPackageName = getInstallerPackageName()

        val billingDataSource =  if (installerPackageName == "com.amazon.venezia" || installerPackageName.isNullOrEmpty()) {
            AmazonAppstoreIAPDataSource.getInstance(
                this@TrivialDriveApplication,
                GlobalScope,
                TrivialDriveRepository.INAPP_SKUS,
                TrivialDriveRepository.SUBSCRIPTION_SKUS,
                TrivialDriveRepository.AUTO_CONSUME_SKUS
            )
        } else {
            BillingDataSource.getInstance(
            this@TrivialDriveApplication,
                GlobalScope,
            TrivialDriveRepository.INAPP_SKUS,
            TrivialDriveRepository.SUBSCRIPTION_SKUS,
            TrivialDriveRepository.AUTO_CONSUME_SKUS
        )
        }

        appContainer = AppContainer(billingDataSource as IBillingDataSource)
    }
}
