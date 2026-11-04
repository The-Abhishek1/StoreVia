package com.xcloak.storevia.domain.stock

import androidx.room.withTransaction
import com.xcloak.storevia.core.access.*
import com.xcloak.storevia.core.util.Parsing
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.BatchDao
import com.xcloak.storevia.core.database.dao.StockMovementDao
import com.xcloak.storevia.core.database.dao.activeBranchId
import com.xcloak.storevia.core.database.entity.MovementType
import com.xcloak.storevia.core.database.entity.StockMovementEntity
import javax.inject.Inject

class StockAdjustException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { INVALID_QTY, EXCEEDS_ON_HAND, BATCH_NOT_FOUND, INVALID_TYPE }
}

/** Damage, expiry write-off and count corrections. Always one ledger row + one batch balance change. */
class AdjustStockUseCase @Inject constructor(
    private val db: StorviaDatabase,
    private val batchDao: BatchDao,
    private val stockDao: StockMovementDao,
    private val access: Access = OwnerAccess,
    private val audit: AuditSink = NoAudit,
) {
    private val allowed = setOf(MovementType.DAMAGE, MovementType.EXPIRED, MovementType.ADJUSTMENT_IN, MovementType.ADJUSTMENT_OUT)

    suspend operator fun invoke(
        batchId: String,
        type: MovementType,
        qtyMilli: Long,
        note: String? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        access.require(Permission.ADJUST_STOCK)
        if (type !in allowed) throw StockAdjustException(StockAdjustException.Reason.INVALID_TYPE)
        if (qtyMilli <= 0) throw StockAdjustException(StockAdjustException.Reason.INVALID_QTY)

        db.withTransaction {
            val branchId = db.branchDao().activeBranchId()
            val batch = batchDao.getById(batchId, branchId) ?: throw StockAdjustException(StockAdjustException.Reason.BATCH_NOT_FOUND)
            val signed = if (type == MovementType.ADJUSTMENT_IN) qtyMilli else -qtyMilli
            if (signed < 0 && qtyMilli > batch.qtyOnHandMilli) throw StockAdjustException(StockAdjustException.Reason.EXCEEDS_ON_HAND)

            batchDao.adjustQty(batchId, signed, now)
            stockDao.insert(
                StockMovementEntity(
                    productId = batch.productId, branchId = branchId, batchId = batchId, type = type, qtyMilli = signed,
                    unitCostPaise = batch.costPaise, refType = "MANUAL", refId = batchId,
                    reason = note?.trim()?.ifEmpty { null }, createdAt = now,
                )
            )
            val name = db.productDao().getById(batch.productId)?.name ?: "product"
            audit.log(AuditAction.STOCK_ADJUST, "${type.name.lowercase().replace('_', ' ')}: ${Parsing.milliToInput(qtyMilli)} of $name" + (note?.trim()?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""), "PRODUCT", batch.productId)
        }
    }
}
