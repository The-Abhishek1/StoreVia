package com.xcloak.storevia.domain.sale

import androidx.room.withTransaction
import com.xcloak.storevia.core.access.*
import com.xcloak.storevia.core.util.Parsing
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.BatchDao
import com.xcloak.storevia.core.database.dao.activeBranchId
import com.xcloak.storevia.core.database.dao.SerialNumberDao
import com.xcloak.storevia.core.database.dao.StockMovementDao
import com.xcloak.storevia.core.database.entity.BatchEntity
import com.xcloak.storevia.core.database.entity.MovementType
import com.xcloak.storevia.core.database.entity.SerialNumberEntity
import com.xcloak.storevia.core.database.entity.StockMovementEntity
import java.time.LocalDate
import javax.inject.Inject

/** Adds a new batch and the matching PURCHASE ledger entry in one transaction. */
class ReceiveStockUseCase @Inject constructor(
    private val db: StorviaDatabase,
    private val batchDao: BatchDao,
    private val stockDao: StockMovementDao,
    private val access: Access = OwnerAccess,
    private val audit: AuditSink = NoAudit,
    private val serialNumberDao: SerialNumberDao = db.serialNumberDao(),
) {
    suspend operator fun invoke(
        productId: String, qtyMilli: Long, costPaise: Long,
        batchNo: String? = null, expiryDate: LocalDate? = null, mrpPaise: Long? = null,
        type: MovementType = MovementType.PURCHASE,
        serialNumbers: List<String> = emptyList(),
    ): String {
        access.require(Permission.MANAGE_PURCHASES)
        require(qtyMilli > 0) { "Quantity must be positive" }
        return db.withTransaction {
            val branchId = db.branchDao().activeBranchId()
            val product = db.productDao().getById(productId) ?: throw IllegalArgumentException("Product not found")
            val normalizedSerials = serialNumbers.map { it.trim() }.filter { it.isNotEmpty() }
            if (product.serialTracked) {
                if (qtyMilli % 1_000L != 0L || normalizedSerials.size.toLong() != qtyMilli / 1_000L) {
                    throw SerialNumberValidationException(SerialNumberValidationException.Reason.COUNT_MISMATCH)
                }
                if (normalizedSerials.distinct().size != normalizedSerials.size) {
                    throw SerialNumberValidationException(SerialNumberValidationException.Reason.DUPLICATE)
                }
                if (normalizedSerials.any { serialNumberDao.exists(it) }) {
                    throw SerialNumberValidationException(SerialNumberValidationException.Reason.DUPLICATE)
                }
            } else if (normalizedSerials.isNotEmpty()) {
                throw SerialNumberValidationException(SerialNumberValidationException.Reason.PRODUCT_NOT_TRACKED)
            }
            val batch = BatchEntity(
                productId = productId, branchId = branchId, batchNo = batchNo, expiryDate = expiryDate,
                costPaise = costPaise, mrpPaise = mrpPaise,
                qtyReceivedMilli = qtyMilli, qtyOnHandMilli = qtyMilli,
            )
            batchDao.insert(batch)
            if (normalizedSerials.isNotEmpty()) {
                serialNumberDao.insertAll(
                    normalizedSerials.map { serial ->
                        SerialNumberEntity(productId = productId, batchId = batch.id, serialNumber = serial)
                    },
                )
            }
            stockDao.insert(
                StockMovementEntity(
                    productId = productId, branchId = branchId, batchId = batch.id, type = type,
                    qtyMilli = qtyMilli, unitCostPaise = costPaise, refType = "MANUAL", refId = batch.id,
                )
            )
            val name = product.name
            audit.log(AuditAction.STOCK_RECEIVED, "Received ${Parsing.milliToInput(qtyMilli)} of $name", "PRODUCT", productId)
            batch.id
        }
    }

}

class SerialNumberValidationException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { COUNT_MISMATCH, DUPLICATE, PRODUCT_NOT_TRACKED }
}
