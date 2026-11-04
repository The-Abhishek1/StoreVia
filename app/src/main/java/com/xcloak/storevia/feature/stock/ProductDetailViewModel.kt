package com.xcloak.storevia.feature.stock

import com.xcloak.storevia.core.access.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.dao.BatchDao
import com.xcloak.storevia.core.database.dao.BranchDao
import com.xcloak.storevia.core.database.dao.ProductDao
import com.xcloak.storevia.core.database.dao.ProductVariantRow
import com.xcloak.storevia.core.database.dao.RecipeComponentRow
import com.xcloak.storevia.core.database.dao.RecipeDao
import com.xcloak.storevia.core.database.dao.SerialNumberDao
import com.xcloak.storevia.core.database.dao.StockMovementDao
import com.xcloak.storevia.core.database.dao.UnitDao
import com.xcloak.storevia.core.database.dao.observeActiveBranchId
import com.xcloak.storevia.core.database.entity.BatchEntity
import com.xcloak.storevia.core.database.entity.MovementType
import com.xcloak.storevia.core.database.entity.StockMovementEntity
import com.xcloak.storevia.core.database.entity.SerialNumberEntity
import com.xcloak.storevia.core.database.entity.ProductEntity
import com.xcloak.storevia.core.database.entity.UnitEntity
import com.xcloak.storevia.core.util.Parsing
import com.xcloak.storevia.domain.sale.ReceiveStockUseCase
import com.xcloak.storevia.domain.sale.SerialNumberValidationException
import com.xcloak.storevia.domain.stock.AdjustStockUseCase
import com.xcloak.storevia.domain.stock.StockAdjustException
import com.xcloak.storevia.domain.recipe.SaveRecipeComponentUseCase
import com.xcloak.storevia.domain.recipe.RemoveRecipeComponentUseCase
import com.xcloak.storevia.domain.recipe.RecipeValidationException
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
    val variants: List<ProductVariantRow>,
    val recipeComponents: List<RecipeComponentRow>,
    val serialNumbers: List<SerialNumberEntity>,
    val hasStockHistory: Boolean,
) {
    val onHandMilli: Long get() = batches.sumOf { it.qtyOnHandMilli }
}

private data class DetailBase(
    val product: ProductEntity?,
    val units: List<UnitEntity>,
    val batches: List<BatchEntity>,
    val barcode: String?,
    val movements: List<StockMovementEntity>,
)

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProductDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    productDao: ProductDao,
    private val unitDao: UnitDao,
    batchDao: BatchDao,
    stockDao: StockMovementDao,
    branchDao: BranchDao,
    recipeDao: RecipeDao,
    serialNumberDao: SerialNumberDao,
    private val receiveStock: ReceiveStockUseCase,
    private val adjustStock: AdjustStockUseCase,
    private val saveRecipeComponent: SaveRecipeComponentUseCase,
    private val removeRecipeComponent: RemoveRecipeComponentUseCase,
) : ViewModel() {

    private val id: String = checkNotNull(handle["id"])
    private val units: StateFlow<List<UnitEntity>> =
        unitDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun unitSymbol(productId: String): String? {
        val product = ingredientOptions.value.firstOrNull { it.id == productId } ?: return null
        return units.value.firstOrNull { it.id == product.unitId }?.symbol
    }
    val ingredientOptions: StateFlow<List<ProductEntity>> =
        productDao.observeActiveSellable().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val activeBranchId = branchDao.observeActiveBranchId().filterNotNull()
    private val base = activeBranchId.flatMapLatest { branchId ->
        combine(
            productDao.observeById(id),
            unitDao.observeAll(),
            batchDao.observeForProduct(id, branchId),
            productDao.observeBarcode(id),
            stockDao.observeForProductAtBranch(id, branchId, 30),
        ) { product, units, batches, barcode, movements ->
            DetailBase(product, units, batches, barcode, movements)
        }
    }

    val ui: StateFlow<DetailUi?> = combine(
        base,
        activeBranchId.flatMapLatest { productDao.observeVariants(id, it) },
        stockDao.observeMovementCount(id),
        recipeDao.observeForProduct(id),
        activeBranchId.flatMapLatest { serialNumberDao.observeForProduct(id, it) },
    ) { detail, variants, movementCount, recipeComponents, serialNumbers ->
        detail.product?.takeIf { it.deletedAt == null }?.let { p ->
            DetailUi(
                p,
                detail.units.firstOrNull { it.id == p.unitId },
                detail.barcode,
                detail.batches,
                detail.movements,
                variants,
                recipeComponents,
                serialNumbers,
                movementCount > 0,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun saveRecipeIngredient(ingredientId: String, quantity: String): Int? {
        val d = ui.value ?: return R.string.err_qty_invalid
        val ingredient = ingredientOptions.value.firstOrNull { it.id == ingredientId } ?: return R.string.err_product_not_found
        val unit = units.value.firstOrNull { it.id == ingredient.unitId }
        val quantityMilli = Parsing.qtyToMilli(quantity, unit?.allowDecimal ?: false)
        if (quantityMilli == null || quantityMilli <= 0) return R.string.err_qty_invalid
        return try {
            saveRecipeComponent(d.product.id, ingredientId, quantityMilli)
            null
        } catch (_: RecipeValidationException) {
            R.string.err_recipe_component_invalid
        } catch (_: PermissionDeniedException) {
            R.string.err_permission_denied
        }
    }

    suspend fun removeRecipeIngredient(id: String): Int? = try {
        removeRecipeComponent(id)
        null
    } catch (_: PermissionDeniedException) {
        R.string.err_permission_denied
    }

    /** @return a string-resource id describing the problem, or null on success. */
    suspend fun receive(
        qty: String,
        cost: String,
        batchNo: String,
        expiry: LocalDate?,
        serialNumbersText: String = "",
    ): Int? {
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
                serialNumbers = serialNumbersText.split(',', '\n', '\r').map { it.trim() }.filter { it.isNotEmpty() },
            )
            null
        } catch (e: SerialNumberValidationException) {
            when (e.reason) {
                SerialNumberValidationException.Reason.COUNT_MISMATCH -> R.string.err_serial_count
                SerialNumberValidationException.Reason.DUPLICATE -> R.string.err_serial_duplicate
                SerialNumberValidationException.Reason.PRODUCT_NOT_TRACKED -> R.string.err_serial_not_tracked
            }
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
