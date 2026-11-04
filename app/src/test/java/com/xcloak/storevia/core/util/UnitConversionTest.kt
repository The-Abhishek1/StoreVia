package com.xcloak.storevia.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UnitConversionTest {
    @Test
    fun packQuantitiesConvertToCanonicalBaseQuantity() {
        val pack = PackUnit("carton", 12_000)

        assertEquals(24_000L, pack.toBaseQuantity(2_000))
        assertEquals(2_000L, pack.fromBaseQuantity(24_000))
    }

    @Test
    fun decimalPackSizeIsSupportedAtThousandthPrecision() {
        val pack = PackUnit("bundle", 1_500)

        assertEquals(3_000L, pack.toBaseQuantity(2_000))
    }

    @Test
    fun conversionConfigurationMustBeCompleteAndPositive() {
        assertNull(PackUnit.fromAttributesJson("""{}"""))
        assertThrows(IllegalArgumentException::class.java) {
            PackUnit.fromAttributesJson("""{"pack_unit":"carton"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackUnit.fromAttributesJson("""{"pack_unit":"carton","pack_size":"0"}""")
        }
    }
}
