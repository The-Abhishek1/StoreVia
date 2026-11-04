package com.xcloak.storevia.data

import com.xcloak.storevia.core.access.AuditAction
import com.xcloak.storevia.core.database.dao.AuditDao
import com.xcloak.storevia.core.database.dao.BranchDao
import com.xcloak.storevia.core.database.dao.StoreDao
import com.xcloak.storevia.core.database.entity.AuditEntity
import com.xcloak.storevia.core.database.entity.BranchEntity
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.core.database.dao.UnitDao
import com.xcloak.storevia.core.database.entity.StoreEntity
import com.xcloak.storevia.core.database.entity.UnitEntity
import javax.inject.Inject
import javax.inject.Singleton

/** First-run seeding. Replaced by a proper onboarding flow (store name, GSTIN, state) later. */
@Singleton
class AppBootstrap @Inject constructor(
    private val storeDao: StoreDao,
    private val branchDao: BranchDao,
    private val unitDao: UnitDao,
    private val auditDao: AuditDao,
    private val prefs: AppPrefs,
) {
    suspend fun ensureSeeded() {
        if (storeDao.get() == null) storeDao.upsert(StoreEntity(name = "My Store"))
        val store = checkNotNull(storeDao.get())
        if (branchDao.count() == 0) {
            branchDao.insert(BranchEntity(id = store.id, name = store.name, selectedOnDevice = true))
        } else if (branchDao.selectedId() == null) {
            branchDao.firstId()?.let { branchDao.selectOnDevice(it, System.currentTimeMillis()) }
        }
        if (unitDao.count() == 0) {
            unitDao.insertAll(
                listOf(
                    UnitEntity(name = "Piece", symbol = "pcs", allowDecimal = false),
                    UnitEntity(name = "Kilogram", symbol = "kg", allowDecimal = true),
                    UnitEntity(name = "Gram", symbol = "g", allowDecimal = true),
                    UnitEntity(name = "Litre", symbol = "L", allowDecimal = true),
                    UnitEntity(name = "Box", symbol = "box", allowDecimal = false),
                    UnitEntity(name = "Packet", symbol = "pkt", allowDecimal = false),
                )
            )
        }
        // The record of a restore, parked before the database was replaced.
        prefs.takePendingAudit()?.let { (action, summary, name, role) ->
            runCatching { AuditAction.valueOf(action) }.getOrNull()?.let {
                auditDao.insert(AuditEntity(actorName = name, actorRole = role, action = it, summary = summary))
            }
        }
    }
}
