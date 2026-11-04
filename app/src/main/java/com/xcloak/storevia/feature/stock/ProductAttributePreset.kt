package com.xcloak.storevia.feature.stock

import androidx.annotation.StringRes
import com.xcloak.storevia.R
import com.xcloak.storevia.core.store.StoreType

data class ProductAttributePreset(val key: String, @StringRes val label: Int)

object ProductAttributePresets {
    fun forType(type: StoreType): List<ProductAttributePreset> = listOf(
        ProductAttributePreset("pack_unit", R.string.product_attribute_pack_unit),
        ProductAttributePreset("pack_size", R.string.product_attribute_pack_size),
    ) + when (type) {
        StoreType.GENERAL -> listOf(ProductAttributePreset("brand", R.string.product_attribute_brand))
        StoreType.MEDICAL -> listOf(
            ProductAttributePreset("manufacturer", R.string.product_attribute_manufacturer),
            ProductAttributePreset("composition", R.string.product_attribute_composition),
            ProductAttributePreset("prescription", R.string.product_attribute_prescription),
        )
        StoreType.RESTAURANT -> listOf(
            ProductAttributePreset("serving_size", R.string.product_attribute_serving_size),
            ProductAttributePreset("ingredients", R.string.product_attribute_ingredients),
            ProductAttributePreset("kitchen_routing", R.string.product_attribute_kitchen_routing),
        )
        StoreType.CLOTHING -> listOf(
            ProductAttributePreset("size", R.string.product_attribute_size),
            ProductAttributePreset("color", R.string.product_attribute_color),
            ProductAttributePreset("fabric", R.string.product_attribute_fabric),
            ProductAttributePreset("season", R.string.product_attribute_season),
        )
        StoreType.ELECTRONICS -> listOf(
            ProductAttributePreset("model_number", R.string.product_attribute_model_number),
            ProductAttributePreset("serial_number", R.string.product_attribute_serial_number),
            ProductAttributePreset("warranty", R.string.product_attribute_warranty),
            ProductAttributePreset("specifications", R.string.product_attribute_specifications),
        )
        StoreType.WHOLESALER -> listOf(
            ProductAttributePreset("bulk_unit", R.string.product_attribute_bulk_unit),
            ProductAttributePreset("carton_quantity", R.string.product_attribute_carton_quantity),
            ProductAttributePreset("minimum_order", R.string.product_attribute_minimum_order),
        )
        StoreType.SERVICE -> listOf(
            ProductAttributePreset("service_duration", R.string.product_attribute_service_duration),
            ProductAttributePreset("staff_assignment", R.string.product_attribute_staff_assignment),
        )
        StoreType.OTHER -> emptyList()
    }
}
