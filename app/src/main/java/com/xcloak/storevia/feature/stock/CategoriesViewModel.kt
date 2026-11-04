package com.xcloak.storevia.feature.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.access.StaffSession
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.CategoryDao
import com.xcloak.storevia.core.database.entity.CategoryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class CategoryRow(val category: CategoryEntity, val productCount: Int)

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CategoriesViewModel @Inject constructor(
    private val db: StorviaDatabase,
    private val dao: CategoryDao,
    private val session: StaffSession,
) : ViewModel() {

    val canManage: StateFlow<Boolean> = session.actorState
        .map { it.can(Permission.MANAGE_PRODUCTS) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.actor().can(Permission.MANAGE_PRODUCTS))
    val rows: StateFlow<List<CategoryRow>?> = canManage.flatMapLatest { allowed ->
        if (!allowed) flowOf(emptyList<CategoryRow>())
        else combine(dao.observeAll(), dao.observeProductCounts()) { cats, counts ->
            val byId = counts.associate { it.id to it.count }
            cats.map { CategoryRow(it, byId[it.id] ?: 0) }
        }
    }.map<List<CategoryRow>, List<CategoryRow>?> { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** @return a string-resource id for the problem, or null on success. */
    suspend fun add(name: String): Int? = db.withTransaction {
        if (!canManageNow()) return@withTransaction R.string.err_permission_denied
        val n = name.trim()
        if (n.isEmpty()) return@withTransaction R.string.err_name_required
        if (dao.findByName(n) != null) return@withTransaction R.string.err_category_exists
        dao.insert(CategoryEntity(name = n))
        null
    }

    suspend fun rename(id: String, name: String): Int? = db.withTransaction {
        if (!canManageNow()) return@withTransaction R.string.err_permission_denied
        val n = name.trim()
        if (n.isEmpty()) return@withTransaction R.string.err_name_required
        val c = dao.getById(id) ?: return@withTransaction R.string.err_category_not_found
        val clash = dao.findByName(n)
        if (clash != null && clash.id != id) return@withTransaction R.string.err_category_exists
        dao.update(c.copy(name = n, updatedAt = System.currentTimeMillis(), dirty = true))
        null
    }

    suspend fun delete(id: String): Int? = db.withTransaction {
        if (!canManageNow()) return@withTransaction R.string.err_permission_denied
        if (dao.getById(id) == null) return@withTransaction R.string.err_category_not_found
        val now = System.currentTimeMillis()
        dao.clearFromProducts(id, now)
        dao.softDelete(id, now)
        null
    }

    private fun canManageNow() = session.actor().can(Permission.MANAGE_PRODUCTS)
}
