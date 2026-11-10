package com.xcloak.storevia.feature.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.access.StaffSession
import com.xcloak.storevia.core.database.entity.CategoryEntity
import com.xcloak.storevia.data.product.ProductCatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class CategoryRow(val category: CategoryEntity, val productCount: Int)

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CategoriesViewModel @Inject constructor(
    private val catalog: ProductCatalogRepository,
    private val session: StaffSession,
) : ViewModel() {

    val canManage: StateFlow<Boolean> = session.actorState
        .map { it.can(Permission.MANAGE_PRODUCTS) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.actor().can(Permission.MANAGE_PRODUCTS))
    private val _operationError = MutableStateFlow<String?>(null)
    val operationError: StateFlow<String?> = _operationError.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    val rows: StateFlow<List<CategoryRow>?> = canManage.flatMapLatest { allowed ->
        if (!allowed) flowOf(emptyList<CategoryRow>())
        else catalog.observeCategorySnapshot().map { (cats, counts) ->
            _loadError.value = null
            cats.map { CategoryRow(it, counts[it.id] ?: 0) }
        }.catch { error ->
            _loadError.value = error.message ?: "Cloud categories could not be loaded."
            emit(emptyList())
        }
    }.map<List<CategoryRow>, List<CategoryRow>?> { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** @return a string-resource id for the problem, or null on success. */
    suspend fun add(name: String): Int? {
        if (!canManageNow()) return R.string.err_permission_denied
        val n = name.trim()
        if (n.isEmpty()) return R.string.err_name_required
        return try {
            if (catalog.findCategoryByName(n) != null) return R.string.err_category_exists
            saveSafely(CategoryEntity(name = n))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _operationError.value = error.message ?: "Category lookup failed."
            R.string.err_cloud_catalog_operation_failed
        }
    }

    suspend fun rename(id: String, name: String): Int? {
        if (!canManageNow()) return R.string.err_permission_denied
        val n = name.trim()
        if (n.isEmpty()) return R.string.err_name_required
        return try {
            val c = catalog.observeCategories().first().firstOrNull { it.id == id }
                ?: return R.string.err_category_not_found
            val clash = catalog.findCategoryByName(n)
            if (clash != null && clash.id != id) return R.string.err_category_exists
            saveSafely(c.copy(name = n, updatedAt = System.currentTimeMillis(), dirty = true))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _operationError.value = error.message ?: "Category lookup failed."
            R.string.err_cloud_catalog_operation_failed
        }
    }

    suspend fun delete(id: String): Int? {
        if (!canManageNow()) return R.string.err_permission_denied
        if (catalog.observeCategories().first().none { it.id == id }) return R.string.err_category_not_found
        val now = System.currentTimeMillis()
        return try {
            catalog.deleteCategory(id, now)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _operationError.value = error.message ?: "Category deletion failed."
            R.string.err_cloud_catalog_operation_failed
        }
    }

    private suspend fun saveSafely(category: CategoryEntity): Int? = try {
        catalog.saveCategory(category)
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        _operationError.value = error.message ?: "Category save failed."
        R.string.err_cloud_catalog_operation_failed
    }

    private fun canManageNow() = session.actor().can(Permission.MANAGE_PRODUCTS)
}
