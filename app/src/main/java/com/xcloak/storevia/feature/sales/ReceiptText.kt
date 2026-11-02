package com.xcloak.storevia.feature.sales

import com.xcloak.storevia.core.database.entity.PaymentEntity
import com.xcloak.storevia.core.database.entity.SaleEntity
import com.xcloak.storevia.core.database.entity.SaleStatus
import com.xcloak.storevia.core.database.entity.SaleLineEntity
import com.xcloak.storevia.core.database.entity.StoreEntity
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.util.Parsing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One printed row: lines sold from several batches are merged back into one item. */
data class ReceiptItem(val name: String, val qtyMilli: Long, val unitPricePaise: Long, val amountPaise: Long)

object ReceiptText {
    private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")

    fun mergeLines(lines: List<SaleLineEntity>): List<ReceiptItem> =
        lines.groupBy { it.productId to it.unitPricePaise }.values.map { g ->
            ReceiptItem(g.first().nameSnapshot, g.sumOf { it.qtyMilli }, g.first().unitPricePaise, g.sumOf { it.lineTotalPaise })
        }

    fun format(
        store: StoreEntity, sale: SaleEntity, lines: List<SaleLineEntity>,
        payments: List<PaymentEntity>, customerName: String?,
    ): String = buildString {
        appendLine(store.name)
        if (store.address.isNotBlank()) appendLine(store.address)
        if (store.phone.isNotBlank()) appendLine("Ph: ${store.phone}")
        store.gstin?.let { appendLine("GSTIN: $it") }
        appendLine("Invoice: ${sale.invoiceNo}")
        appendLine(Instant.ofEpochMilli(sale.createdAt).atZone(ZoneId.systemDefault()).format(DATE))
        customerName?.let { appendLine("Customer: $it") }
        if (sale.status == SaleStatus.CANCELLED) appendLine("*** CANCELLED" + (sale.cancelReason?.let { ": $it" } ?: "") + " ***")
        appendLine("--------------------------------")
        for (i in mergeLines(lines)) {
            appendLine(i.name)
            appendLine("  ${Parsing.milliToInput(i.qtyMilli)} x ${Money(i.unitPricePaise).format()} = ${Money(i.amountPaise).format()}")
        }
        appendLine("--------------------------------")
        appendLine("Taxable value: ${Money(sale.subtotalPaise).format()}")
        val cgst = lines.sumOf { it.cgstPaise }; val sgst = lines.sumOf { it.sgstPaise }; val igst = lines.sumOf { it.igstPaise }
        if (igst > 0) appendLine("IGST: ${Money(igst).format()}")
        else if (cgst + sgst > 0) { appendLine("CGST: ${Money(cgst).format()}"); appendLine("SGST: ${Money(sgst).format()}") }
        if (sale.discountPaise > 0) appendLine("Discount given: ${Money(sale.discountPaise).format()}")
        if (sale.roundOffPaise != 0L) appendLine("Round off: ${Money(sale.roundOffPaise).format()}")
        appendLine("TOTAL: ${Money(sale.totalPaise).format()}")
        for (p in payments) appendLine("Paid (${p.method.name.lowercase()}): ${Money(p.amountPaise).format()}")
        if (sale.returnedPaise > 0) appendLine("Returned: -${Money(sale.returnedPaise).format()}")
        val due = sale.totalPaise - sale.returnedPaise - sale.paidPaise
        if (due > 0) appendLine("Balance due: ${Money(due).format()}")
        append("Thank you!")
    }
}
