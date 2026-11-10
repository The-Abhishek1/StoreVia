package com.xcloak.storevia.feature.home

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CloudHomeMetricsTest {
    @Test
    fun dashboardMetricsUseOnlySelectedBranchAndCurrentCloudRows() {
        val today = LocalDate.of(2026, 11, 5)
        val query = DashboardQuery(
            branchId = "main",
            expiryWarnDays = 30,
            salesTo = today.plusDays(1).toEpochStart(),
            todayFrom = today.toEpochStart(),
            chartFrom = today.minusDays(13).toEpochStart(),
            expiryFrom = today,
            expiryTo = today.plusDays(30),
            today = today,
        )
        val result = calculateCloudHomeState(
            rows = mapOf(
                "sales" to listOf(
                    row("id" to "sale-main", "branch_id" to "main", "created_at" to today.toEpochStart(),
                        "status" to "COMPLETED", "total_paise" to 2500, "paid_paise" to 1500,
                        "returned_paise" to 0, "customer_id" to "c1"),
                    row("id" to "sale-other", "branch_id" to "other", "created_at" to today.toEpochStart(),
                        "status" to "COMPLETED", "total_paise" to 9000, "paid_paise" to 0,
                        "returned_paise" to 0, "customer_id" to "c1"),
                ),
                "products" to listOf(
                    row("id" to "p1", "is_active" to 1, "min_stock_milli" to 2000, "deleted_at" to null),
                ),
                "batches" to listOf(
                    row("product_id" to "p1", "branch_id" to "main", "deleted_at" to null,
                        "qty_on_hand_milli" to 1000, "expiry_date" to today.plusDays(8).toString()),
                    row("product_id" to "p1", "branch_id" to "other", "deleted_at" to null,
                        "qty_on_hand_milli" to 5000, "expiry_date" to today.plusDays(8).toString()),
                ),
                "customers" to listOf(row("id" to "c1", "deleted_at" to null)),
            ),
            query = query,
            canViewReports = true,
        )

        assertEquals(2500L, result.todaySalesPaise)
        assertEquals(1, result.todayCount)
        assertEquals(1, result.productCount)
        assertEquals(1, result.lowStockCount)
        assertEquals(1, result.expiringCount)
        assertEquals(1000L, result.khataDuePaise)
    }

    private fun LocalDate.toEpochStart(): Long =
        atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun row(vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
        fields.forEach { (key, value) ->
            when (value) {
                null -> put(key, kotlinx.serialization.json.JsonNull)
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                else -> error("Unsupported test value $value")
            }
        }
    }
}
