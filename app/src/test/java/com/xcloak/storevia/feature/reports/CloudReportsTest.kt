package com.xcloak.storevia.feature.reports

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CloudReportsTest {
    @Test
    fun reportAggregatesCloudSalesReturnsAndPayments() {
        val result = calculateCloudReports(
            rows = mapOf(
                "sales" to listOf(
                    row(
                        "id" to "sale-1", "status" to "COMPLETED", "created_at" to 1500L,
                        "branch_id" to "branch-1", "customer_id" to "customer-1",
                        "total_paise" to 1200L, "discount_paise" to 100L,
                    ),
                ),
                "sale_lines" to listOf(
                    row(
                        "id" to "line-1", "sale_id" to "sale-1", "product_id" to "product-1",
                        "name_snapshot" to "Tea", "qty_milli" to 1000L, "line_total_paise" to 1200L,
                        "discount_paise" to 100L, "taxable_value_paise" to 1000L,
                        "cost_snapshot_paise" to 500L, "gst_rate_bp" to 2000,
                        "cgst_paise" to 100L, "sgst_paise" to 100L, "igst_paise" to 0L,
                    ),
                ),
                "payments" to listOf(
                    row("id" to "payment-1", "sale_id" to "sale-1", "method" to "CASH", "amount_paise" to 1200L),
                ),
                "sale_returns" to listOf(
                    row("id" to "return-1", "sale_id" to "sale-1", "created_at" to 1600L, "total_paise" to 300L),
                ),
                "sale_return_lines" to listOf(
                    row(
                        "id" to "return-line-1", "return_id" to "return-1", "sale_line_id" to "line-1",
                        "amount_paise" to 300L, "qty_milli" to 250L, "cost_snapshot_paise" to 400L,
                    ),
                ),
            ),
            query = ReportQuery(
                from = 1000L,
                to = 2000L,
                method = null,
                filters = ReportFilters(),
                period = Period.CUSTOM,
            ),
            today = LocalDate.of(2026, 11, 5),
            expiryWarnDays = 30,
        )

        assertEquals(1, result.snapshot.sales.bills)
        assertEquals(1200L, result.snapshot.sales.grossPaise)
        assertEquals(300L, result.snapshot.sales.returnsPaise)
        assertEquals(100L, result.snapshot.sales.discountPaise)
        assertEquals(200L, result.snapshot.sales.taxPaise)
        assertEquals(350L, result.snapshot.sales.profitPaise)
        assertEquals(1, result.snapshot.payments.size)
        assertEquals(1200L, result.snapshot.payments.single().totalPaise)
    }

    private fun row(vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
        fields.forEach { (key, value) ->
            when (value) {
                null -> put(key, JsonNull)
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                else -> error("Unsupported test value $value")
            }
        }
    }
}
