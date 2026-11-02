package com.xcloak.storevia.core.di

import com.xcloak.storevia.core.access.Access
import com.xcloak.storevia.core.access.AuditSink
import com.xcloak.storevia.core.access.StaffSession
import com.xcloak.storevia.data.audit.AuditLogger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AccessModule {
    @Binds abstract fun access(impl: StaffSession): Access
    @Binds abstract fun auditSink(impl: AuditLogger): AuditSink
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SeriesModule {
    @Binds abstract fun series(impl: com.xcloak.storevia.domain.sale.PrefsInvoiceSeries): com.xcloak.storevia.domain.sale.InvoiceSeries
}
