package com.xcloak.storevia.core.di

import com.xcloak.storevia.data.cloud.CloudSalesInventoryRepository
import com.xcloak.storevia.data.cloud.CloudPartyRepository
import com.xcloak.storevia.domain.customer.CustomerPaymentOperations
import com.xcloak.storevia.domain.sale.SalesInventoryOperations
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CloudSalesInventoryModule {
    @Binds
    @Singleton
    abstract fun salesInventoryOperations(repository: CloudSalesInventoryRepository): SalesInventoryOperations

    @Binds
    @Singleton
    abstract fun customerPaymentOperations(repository: CloudPartyRepository): CustomerPaymentOperations
}
