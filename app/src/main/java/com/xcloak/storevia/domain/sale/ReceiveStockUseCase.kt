package com.xcloak.storevia.domain.sale

import androidx.room.withTransaction
import com.xcloak.storevia.core.access.*
import com.xcloak.storevia.core.util.Parsing
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.BatchDao
import com.xcloak.storevia.core.database.dao.StockMovementDao
import com.xcloak.storevia.core.database.entity.BatchEntity
import com.xcloak.storevia.core.database.entity.MovementType
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
) {
    suspend operator fun invoke(
        productId: String, qtyMilli: Long, costPaise: Long,
        batchNo: String? = null, expiryDate: LocalDate? = null, mrpPaise: Long? = null,
        type: MovementType = MovementType.PURCHASE,
    ): String {
        access.require(Permission.MANAGE_PURCHASES)
        require(qtyMilli > 0) { "Quantity must be positive" }
        return db.withTransaction {
            val batch = BatchEntity(
                productId = productId, batchNo = batchNo, expiryDate = expiryDate,
                costPaise = costPaise, mrpPaise = mrpPaise,
                qtyReceivedMilli = qtyMilli, qtyOnHandMilli = qtyMilli,
            )
            batchDao.insert(batch)
            stockDao.insert(
                StockMovementEntity(
                    productId = productId, batchId = batch.id, type = type,
                    qtyMilli = qtyMilli, unitCostPaise = costPaise, refType = "MANUAL", refId = batch.id,
                )
            )
            val name = db.productDao().getById(productId)?.name ?: "product"
            audit.log(AuditAction.STOCK_RECEIVED, "Received ${Parsing.milliToInput(qtyMilli)} of $name", "PRODUCT", productId)
            batch.id
        }
    }
}
