package com.xcloak.storevia.feature.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.CategoryDao
import com.xcloak.storevia.core.database.entity.CategoryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class CategoryRow(val category: CategoryEntity, val productCount: Int)

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val db: StorviaDatabase,
    private val dao: CategoryDao,
) : ViewModel() {

    val rows: StateFlow<List<CategoryRow>?> = combine(dao.observeAll(), dao.observeProductCounts()) { cats, counts ->
        val byId = counts.associate { it.id to it.count }
        cats.map { CategoryRow(it, byId[it.id] ?: 0) }
    }.map<List<CategoryRow>, List<CategoryRow>?> { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** @return a string-resource id for the problem, or null on success. */
    suspend fun add(name: String): Int? {
        val n = name.trim()
        if (n.isEmpty()) return R.string.err_name_required
        if (dao.findByName(n) != null) return R.string.err_category_exists
        dao.insert(CategoryEntity(name = n))
        return null
    }

    suspend fun rename(id: String, name: String): Int? {
        val n = name.trim()
        if (n.isEmpty()) return R.string.err_name_required
        val clash = dao.findByName(n)
        if (clash != null && clash.id != id) return R.string.err_category_exists
        val c = dao.getById(id) ?: return null
        dao.update(c.copy(name = n, updatedAt = System.currentTimeMillis(), dirty = true))
        return null
    }

    suspend fun delete(id: String) = db.withTransaction {
        val now = System.currentTimeMillis()
        dao.clearFromProducts(id, now)
        dao.softDelete(id, now)
    }
}
