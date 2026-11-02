package com.xcloak.storevia.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.xcloak.storevia.core.database.entity.AuditEntity
import com.xcloak.storevia.core.database.entity.StaffEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StaffDao {
    @Query("SELECT * FROM staff WHERE isActive = 1 AND deletedAt IS NULL ORDER BY name COLLATE NOCASE") fun observeActive(): Flow<List<StaffEntity>>
    @Query("SELECT * FROM staff WHERE deletedAt IS NULL ORDER BY isActive DESC, name COLLATE NOCASE") fun observeAll(): Flow<List<StaffEntity>>
    @Query("SELECT * FROM staff WHERE id = :id") suspend fun getById(id: String): StaffEntity?
    @Query("SELECT COUNT(*) FROM staff WHERE role = 'OWNER' AND isActive = 1 AND deletedAt IS NULL") suspend fun activeOwnerCount(): Int
    @Query("SELECT * FROM staff WHERE isActive = 1 AND deletedAt IS NULL AND name = :name COLLATE NOCASE LIMIT 1") suspend fun findActiveByName(name: String): StaffEntity?
    @Insert suspend fun insert(s: StaffEntity)
    @Update suspend fun update(s: StaffEntity)
}

@Dao
interface AuditDao {
    @Insert suspend fun insert(e: AuditEntity)
    @Query("SELECT * FROM audit_log ORDER BY createdAt DESC, rowid DESC LIMIT :limit") fun observeRecent(limit: Int = 500): Flow<List<AuditEntity>>
    @Query("SELECT * FROM audit_log ORDER BY createdAt, rowid") suspend fun all(): List<AuditEntity>
}
