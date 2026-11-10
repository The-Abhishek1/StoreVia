package com.xcloak.storevia.data.prefs

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest

object StorageModeRouter {
    fun <T> route(
        mode: StorageMode?,
        local: () -> T,
        cloud: () -> T,
    ): T = if (mode == StorageMode.CLOUD) cloud() else local()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> observe(
        mode: Flow<StorageMode?>,
        local: () -> Flow<T>,
        cloud: () -> Flow<T>,
    ): Flow<T> = mode.distinctUntilChanged().flatMapLatest { selected ->
        route(selected, local, cloud)
    }

    suspend fun <T> routeSuspend(
        mode: StorageMode?,
        local: suspend () -> T,
        cloud: suspend () -> T,
    ): T = if (mode == StorageMode.CLOUD) cloud() else local()
}
