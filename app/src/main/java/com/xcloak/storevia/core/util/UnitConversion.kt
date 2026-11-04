package com.xcloak.storevia.core.util

import com.xcloak.storevia.core.money.Rounding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

data class PackUnit(val name: String, val baseUnitsPerPackMilli: Long) {
    init {
        require(name.isNotBlank())
        require(baseUnitsPerPackMilli > 0)
    }

    fun toBaseQuantity(packQuantityMilli: Long): Long {
        require(packQuantityMilli >= 0)
        return Rounding.divHalfUp(
            Math.multiplyExact(packQuantityMilli, baseUnitsPerPackMilli),
            1_000L,
        )
    }

    fun fromBaseQuantity(baseQuantityMilli: Long): Long {
        require(baseQuantityMilli >= 0)
        return Rounding.divHalfUp(
            Math.multiplyExact(baseQuantityMilli, 1_000L),
            baseUnitsPerPackMilli,
        )
    }

    companion object {
        const val UNIT_NAME_ATTRIBUTE = "pack_unit"
        const val BASE_UNITS_ATTRIBUTE = "pack_size"

        fun fromAttributesJson(attributesJson: String): PackUnit? {
            val fields = Json.parseToJsonElement(attributesJson).jsonObject
            val name = fields[UNIT_NAME_ATTRIBUTE]?.jsonPrimitive?.content?.trim().orEmpty()
            val size = fields[BASE_UNITS_ATTRIBUTE]?.jsonPrimitive?.content?.trim().orEmpty()
            if (name.isEmpty() && size.isEmpty()) return null
            require(name.isNotEmpty() && size.isNotEmpty()) { "Pack unit and pack size must both be provided" }
            val sizeMilli = try {
                BigDecimal(size).movePointRight(3).longValueExact()
            } catch (e: ArithmeticException) {
                throw IllegalArgumentException("Pack size must be a positive quantity with at most three decimals", e)
            }
            return PackUnit(name, sizeMilli)
        }
    }
}
