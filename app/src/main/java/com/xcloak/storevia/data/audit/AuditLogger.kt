package com.xcloak.storevia.data.audit

import com.xcloak.storevia.core.access.Access
import com.xcloak.storevia.core.access.Actor
import com.xcloak.storevia.core.access.AuditAction
import com.xcloak.storevia.core.access.AuditSink
import com.xcloak.storevia.core.database.dao.AuditDao
import com.xcloak.storevia.core.database.entity.AuditEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuditLogger @Inject constructor(private val dao: AuditDao, private val access: Access) : AuditSink {
    override suspend fun log(action: AuditAction, summary: String, entityType: String?, entityId: String?, actor: Actor?) {
        val a = actor ?: access.actor()
        dao.insert(
            AuditEntity(
                actorId = a.id?.takeIf { it != "none" }, actorName = a.name.ifEmpty { "?" }, actorRole = a.role.name,
                action = action, entityType = entityType, entityId = entityId, summary = summary,
            )
        )
    }
}
