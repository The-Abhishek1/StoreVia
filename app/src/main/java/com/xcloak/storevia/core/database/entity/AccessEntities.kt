package com.xcloak.storevia.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.xcloak.storevia.core.access.AuditAction
import com.xcloak.storevia.core.access.Role
import java.util.UUID

/** A person who can use this phone. PIN is stored only as a salted hash (hex). */
@Entity(tableName = "staff", indices = [Index("name")])
data class StaffEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: Role,
    val pinHash: String? = null,
    val pinSalt: String? = null,
    val pinIterations: Int = 0,
    val isActive: Boolean = true,
    val failedAttempts: Int = 0,
    val lockedUntil: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val deletedAt: Long? = null,
    val dirty: Boolean = true,
)

/** Append-only record of who did what. Never edited or deleted by the app. */
@Entity(tableName = "audit_log", indices = [Index("createdAt"), Index("action")])
data class AuditEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val actorId: String? = null,
    val actorName: String,
    val actorRole: String,
    val action: AuditAction,
    val entityType: String? = null,
    val entityId: String? = null,
    val summary: String,
    val dirty: Boolean = true,
)
