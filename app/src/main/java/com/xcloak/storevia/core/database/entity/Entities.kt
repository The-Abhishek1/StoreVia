package com.xcloak.storevia.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.util.UUID

/*
 * Conventions
 * - String UUID primary keys: safe to sync with Supabase later without ID clashes.
 * - Money: Long paise.  Quantity: Long thousandths (1.250 kg = 1250).
 * - updatedAt / deletedAt (epoch millis) + dirty flag support soft-delete and sync.
 */

private fun newId() = UUID.randomUUID().toString()

@Entity(tableName = "store")
data class StoreEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val address: String = "",
    val phone: String = "",
    val gstin: String? = null,
    val stateCode: String = "29",          // GST state code, 29 = Karnataka
    val invoicePrefix: String = "INV",
    val nextInvoiceNo: Long = 1,
    val allowNegativeStock: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val dirty: Boolean = true,
)

@Entity(tableName = "units")
data class UnitEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val symbol: String,
    val allowDecimal: Boolean,
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val parentId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    val dirty: Boolean = true,
)

@Entity(
    tableName = "products",
    indices = [Index("name"), Index("sku"), Index("categoryId")],
)
data class ProductEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val sku: String? = null,
    val categoryId: String? = null,
    val unitId: String,
    val hsnCode: String? = null,
    val gstRateBp: Int = 0,
    val taxInclusive: Boolean = true,
    val mrpPaise: Long? = null,
    val sellPricePaise: Long,
    val costPaise: Long = 0,
    val trackExpiry: Boolean = false,
    val minStockMilli: Long = 0,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val deletedAt: Long? = null,
    val dirty: Boolean = true,
)

@Entity(
    tableName = "product_barcodes",
    foreignKeys = [ForeignKey(ProductEntity::class, ["id"], ["productId"], onDelete = ForeignKey.CASCADE)],
    // Not unique: rows are soft-deleted (so the deletion can sync) and two phones may add the same barcode offline.
    // The app checks for an active duplicate in code (ProductRepository).
    indices = [Index("barcode"), Index("productId")],
)
data class ProductBarcodeEntity(
    @PrimaryKey val id: String = newId(),
    val productId: String,
    val barcode: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    val dirty: Boolean = true,
)

@Entity(
    tableName = "batches",
    foreignKeys = [ForeignKey(ProductEntity::class, ["id"], ["productId"])],
    indices = [Index(value = ["productId", "expiryDate"])],
)
data class BatchEntity(
    @PrimaryKey val id: String = newId(),
    val productId: String,
    val batchNo: String? = null,
    val expiryDate: LocalDate? = null,
    val costPaise: Long = 0,
    val mrpPaise: Long? = null,
    val qtyReceivedMilli: Long,
    /** Cached balance. Source of truth is stock_movements. */
    val qtyOnHandMilli: Long,
    val receivedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = receivedAt,
    val deletedAt: Long? = null,
    val dirty: Boolean = true,
)

enum class MovementType {
    OPENING, PURCHASE, SALE, SALE_RETURN, PURCHASE_RETURN,
    DAMAGE, EXPIRED, ADJUSTMENT_IN, ADJUSTMENT_OUT
}

@Entity(
    tableName = "stock_movements",
    indices = [Index(value = ["productId", "createdAt"]), Index("batchId"), Index(value = ["refType", "refId"])],
)
data class StockMovementEntity(
    @PrimaryKey val id: String = newId(),
    val productId: String,
    val batchId: String? = null,
    val type: MovementType,
    /** Signed: positive adds stock, negative removes it. */
    val qtyMilli: Long,
    val unitCostPaise: Long? = null,
    val refType: String? = null,       // "SALE", "PURCHASE", "MANUAL"
    val refId: String? = null,
    val reason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
)

@Entity(tableName = "customers", indices = [Index("phone")])
data class CustomerEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val phone: String? = null,
    val creditLimitPaise: Long? = null,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val deletedAt: Long? = null,
    val dirty: Boolean = true,
)

enum class SaleStatus { COMPLETED, CANCELLED, PARTIAL_RETURN, RETURNED }

@Entity(
    tableName = "sales",
    indices = [Index(value = ["invoiceNo"], unique = true), Index("customerId"), Index("createdAt")],
)
data class SaleEntity(
    @PrimaryKey val id: String = newId(),
    val invoiceNo: String,
    val customerId: String? = null,
    val status: SaleStatus = SaleStatus.COMPLETED,
    val interState: Boolean = false,
    val subtotalPaise: Long,           // sum of taxable values
    val discountPaise: Long,
    val taxPaise: Long,
    val roundOffPaise: Long,
    val totalPaise: Long,
    /** Money kept for this bill (net of refunds). */
    val paidPaise: Long,
    /** Value of goods returned. Amount still due = total - returned - paid. */
    val returnedPaise: Long = 0,
    val cancelReason: String? = null,
    val cancelledAt: Long? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
)

@Entity(
    tableName = "sale_lines",
    foreignKeys = [ForeignKey(SaleEntity::class, ["id"], ["saleId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("saleId"), Index("productId"), Index("batchId")],
)
data class SaleLineEntity(
    @PrimaryKey val id: String = newId(),
    val saleId: String,
    val productId: String,
    /** Null when sold against negative stock (no batch available). */
    val batchId: String? = null,
    // Snapshots: invoices must never change when the catalogue does.
    val nameSnapshot: String,
    val hsnSnapshot: String? = null,
    val qtyMilli: Long,
    val unitPricePaise: Long,
    val discountPaise: Long,
    val gstRateBp: Int,
    val taxInclusive: Boolean,
    val taxableValuePaise: Long,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val lineTotalPaise: Long,
    /** Cost at time of sale -> accurate gross profit later. */
    val costSnapshotPaise: Long,
    val returnedQtyMilli: Long = 0,
    val returnedAmountPaise: Long = 0,
    val dirty: Boolean = true,
)

enum class PaymentMethod { CASH, UPI, CARD, OTHER }

@Entity(tableName = "payments", indices = [Index("saleId"), Index("customerId")])
data class PaymentEntity(
    @PrimaryKey val id: String = newId(),
    val saleId: String? = null,
    val customerId: String? = null,
    val method: PaymentMethod,
    val amountPaise: Long,
    val reference: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
)

enum class LedgerType { SALE_ON_CREDIT, PAYMENT_RECEIVED, RETURN_CREDIT, ADJUSTMENT }

/** Khata. Customer balance owed = SUM(amountPaise) (positive = customer owes you). */
@Entity(tableName = "customer_ledger", indices = [Index("customerId")])
data class CustomerLedgerEntity(
    @PrimaryKey val id: String = newId(),
    val customerId: String,
    val type: LedgerType,
    val amountPaise: Long,
    val refType: String? = null,
    val refId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
)

/** A customer bringing goods back. Invoices are never edited; returns are separate records. */
@Entity(tableName = "sale_returns", indices = [Index("saleId"), Index("createdAt")])
data class SaleReturnEntity(
    @PrimaryKey val id: String = newId(),
    val saleId: String,
    val customerId: String? = null,
    val totalPaise: Long,
    /** Part of the return that simply reduced what the customer still owed. */
    val dueReducedPaise: Long,
    /** Part handed back as money, or kept as advance credit when [keptAsCredit]. */
    val refundPaise: Long,
    val refundMethod: PaymentMethod? = null,
    val keptAsCredit: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
)

@Entity(
    tableName = "sale_return_lines",
    foreignKeys = [ForeignKey(SaleReturnEntity::class, ["id"], ["returnId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("returnId"), Index("saleLineId")],
)
data class SaleReturnLineEntity(
    @PrimaryKey val id: String = newId(),
    val returnId: String,
    val saleLineId: String,
    val productId: String,
    val batchId: String? = null,
    val qtyMilli: Long,
    val amountPaise: Long,
    val costSnapshotPaise: Long,
    val dirty: Boolean = true,
)

/** A bill parked while the shopkeeper serves someone else. Local to the device; the cart is stored as JSON. */
@Entity(tableName = "held_carts")
data class HeldCartEntity(
    @PrimaryKey val id: String = newId(),
    val label: String,
    val customerId: String? = null,
    val interState: Boolean = false,
    val billDiscountPaise: Long = 0,
    val itemsJson: String,
    val itemCount: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "suppliers", indices = [Index("name")])
data class SupplierEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val phone: String? = null,
    val gstin: String? = null,
    val notes: String? = null,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val deletedAt: Long? = null,
    val dirty: Boolean = true,
)

/** Stock bought from a supplier (or off the street). Purchase stock arrives as batches + PURCHASE ledger rows. */
@Entity(tableName = "purchases", indices = [Index("supplierId"), Index("purchaseDate")])
data class PurchaseEntity(
    @PrimaryKey val id: String = newId(),
    val supplierId: String? = null,
    val invoiceNo: String? = null,
    /** Start of the purchase day, local time. */
    val purchaseDate: Long,
    /** What the supplier bill says (may include tax/freight, so it can exceed the sum of the lines). */
    val totalPaise: Long,
    /** Paid at the time of purchase. Later payments are supplier-ledger entries. */
    val paidPaise: Long,
    val payMethod: PaymentMethod? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
)

@Entity(
    tableName = "purchase_lines",
    foreignKeys = [ForeignKey(PurchaseEntity::class, ["id"], ["purchaseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("purchaseId"), Index("productId")],
)
data class PurchaseLineEntity(
    @PrimaryKey val id: String = newId(),
    val purchaseId: String,
    val productId: String,
    val batchId: String,
    val nameSnapshot: String,
    val qtyMilli: Long,
    val unitCostPaise: Long,
    val lineTotalPaise: Long,
    val dirty: Boolean = true,
)

enum class SupplierLedgerType { PURCHASE, PAYMENT, ADJUSTMENT }

/** What you owe a supplier. Balance = SUM(amountPaise); positive = you owe them. */
@Entity(tableName = "supplier_ledger", indices = [Index("supplierId")])
data class SupplierLedgerEntity(
    @PrimaryKey val id: String = newId(),
    val supplierId: String,
    val type: SupplierLedgerType,
    val amountPaise: Long,
    val method: PaymentMethod? = null,
    val refType: String? = null,
    val refId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
)

/** Where each synced table has got to: the server timestamp of the newest row already pulled. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val tableName: String,
    val cursorTs: String? = null,
)
