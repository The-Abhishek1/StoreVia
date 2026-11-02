package com.xcloak.storevia.feature.stock

import com.xcloak.storevia.core.access.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.dao.BatchDao
import com.xcloak.storevia.core.database.dao.ProductDao
import com.xcloak.storevia.core.database.dao.StockMovementDao
import com.xcloak.storevia.core.database.dao.UnitDao
import com.xcloak.storevia.core.database.entity.BatchEntity
import com.xcloak.storevia.core.database.entity.MovementType
import com.xcloak.storevia.core.database.entity.StockMovementEntity
import com.xcloak.storevia.core.database.entity.ProductEntity
import com.xcloak.storevia.core.database.entity.UnitEntity
import com.xcloak.storevia.core.util.Parsing
import com.xcloak.storevia.domain.sale.ReceiveStockUseCase
import com.xcloak.storevia.domain.stock.AdjustStockUseCase
import com.xcloak.storevia.domain.stock.StockAdjustException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import javax.inject.Inject

data class DetailUi(
    val product: ProductEntity,
    val unit: UnitEntity?,
    val barcode: String?,
    val batches: List<BatchEntity>,
    val movements: List<StockMovementEntity>,
) {
    val onHandMilli: Long get() = batches.sumOf { it.qtyOnHandMilli }
}

@HiltViewModel
class ProductDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    productDao: ProductDao,
    unitDao: UnitDao,
    batchDao: BatchDao,
    stockDao: StockMovementDao,
    private val receiveStock: ReceiveStockUseCase,
    private val adjustStock: AdjustStockUseCase,
) : ViewModel() {

    private val id: String = checkNotNull(handle["id"])

    val ui: StateFlow<DetailUi?> = combine(
        productDao.observeById(id),
        unitDao.observeAll(),
        batchDao.observeForProduct(id),
        productDao.observeBarcode(id),
        stockDao.observeForProduct(id, 30),
    ) { product, units, batches, barcode, movements ->
        product?.takeIf { it.deletedAt == null }?.let { p ->
            DetailUi(p, units.firstOrNull { it.id == p.unitId }, barcode, batches, movements)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** @return a string-resource id describing the problem, or null on success. */
    suspend fun receive(qty: String, cost: String, batchNo: String, expiry: LocalDate?): Int? {
        val d = ui.value ?: return R.string.err_qty_invalid
        val qtyMilli = Parsing.qtyToMilli(qty, d.unit?.allowDecimal ?: false)
        if (qtyMilli == null || qtyMilli <= 0) return if (d.unit?.allowDecimal == false) R.string.err_qty_whole else R.string.err_qty_invalid
        val costPaise = if (cost.isBlank()) d.product.costPaise else Parsing.moneyToPaise(cost)
            ?: return R.string.err_amount_invalid
        if (d.product.trackExpiry && expiry == null) return R.string.err_expiry_required
        return try {
            receiveStock(
                productId = d.product.id, qtyMilli = qtyMilli, costPaise = costPaise,
                batchNo = batchNo.trim().ifEmpty { null }, expiryDate = expiry,
            )
            null
        } catch (e: PermissionDeniedException) { R.string.err_permission_denied }
    }

    /** @return a string-resource id describing the problem, or null on success. */
    suspend fun adjust(batchId: String, type: MovementType, qty: String, note: String): Int? {
        val d = ui.value ?: return R.string.err_qty_invalid
        val qtyMilli = Parsing.qtyToMilli(qty, d.unit?.allowDecimal ?: false)
        if (qtyMilli == null || qtyMilli <= 0) return if (d.unit?.allowDecimal == false) R.string.err_qty_whole else R.string.err_qty_invalid
        return try {
            adjustStock(batchId, type, qtyMilli, note); null
        } catch (e: StockAdjustException) {
            when (e.reason) {
                StockAdjustException.Reason.EXCEEDS_ON_HAND -> R.string.err_exceeds_on_hand
                else -> R.string.err_qty_invalid
            }
        } catch (e: PermissionDeniedException) {
            R.string.err_permission_denied
        }
    }
}
