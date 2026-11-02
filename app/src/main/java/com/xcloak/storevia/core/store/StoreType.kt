package com.xcloak.storevia.core.store

enum class StoreType {
    GENERAL,
    MEDICAL,
    WHOLESALER;

    companion object {
        fun fromName(value: String?): StoreType = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: GENERAL
    }
}

data class StoreFeatureSet(
    val supportsExpiryTracking: Boolean = true,
    val supportsBatchTracking: Boolean = true,
    val supportsKhata: Boolean = true,
    val supportsSupplierLedger: Boolean = true,
    val supportsWholesale: Boolean = false,
    val supportsPrescriptionWorkflow: Boolean = false,
    val supportsCloudSync: Boolean = false,
    val supportsMultiStoreAdmin: Boolean = false,
)

object StoreTypeConfig {
    fun of(type: StoreType): StoreFeatureSet = when (type) {
        StoreType.GENERAL -> StoreFeatureSet(
            supportsExpiryTracking = false,
            supportsBatchTracking = false,
            supportsKhata = true,
            supportsSupplierLedger = true,
            supportsWholesale = false,
            supportsPrescriptionWorkflow = false,
            supportsCloudSync = true,
            supportsMultiStoreAdmin = false,
        )
        StoreType.MEDICAL -> StoreFeatureSet(
            supportsExpiryTracking = true,
            supportsBatchTracking = true,
            supportsKhata = true,
            supportsSupplierLedger = true,
            supportsWholesale = false,
            supportsPrescriptionWorkflow = false,
            supportsCloudSync = true,
            supportsMultiStoreAdmin = false,
        )
        StoreType.WHOLESALER -> StoreFeatureSet(
            supportsExpiryTracking = true,
            supportsBatchTracking = true,
            supportsKhata = true,
            supportsSupplierLedger = true,
            supportsWholesale = true,
            supportsPrescriptionWorkflow = false,
            supportsCloudSync = true,
            supportsMultiStoreAdmin = false,
        )
    }
}
