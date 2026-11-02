package com.xcloak.storevia.data.staff

import com.xcloak.storevia.core.access.*
import com.xcloak.storevia.core.database.dao.StaffDao
import com.xcloak.storevia.core.database.entity.StaffEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class StaffException(val error: Error) : Exception(error.name) {
    enum class Error { NAME_REQUIRED, NAME_TAKEN, PIN_BAD_LENGTH, PIN_NOT_DIGITS, PIN_TOO_SIMPLE, OWNER_EXISTS, NOT_FOUND, LAST_OWNER }
}

sealed interface AuthResult {
    data class Success(val staff: StaffEntity) : AuthResult
    /** [triesLeft] wrong PINs remain before the account locks for a while. */
    data class WrongPin(val triesLeft: Int) : AuthResult
    data class LockedOut(val untilMillis: Long) : AuthResult
    data object Unknown : AuthResult
}

class StaffRepository @Inject constructor(
    private val dao: StaffDao,
    private val audit: AuditSink,
) {
    private fun checkPin(pin: String) {
        when (PinPolicy.validate(pin)) {
            PinPolicy.Result.OK -> Unit
            PinPolicy.Result.BAD_LENGTH -> throw StaffException(StaffException.Error.PIN_BAD_LENGTH)
            PinPolicy.Result.NOT_DIGITS -> throw StaffException(StaffException.Error.PIN_NOT_DIGITS)
            PinPolicy.Result.TOO_SIMPLE -> throw StaffException(StaffException.Error.PIN_TOO_SIMPLE)
        }
    }

    private suspend fun withPin(base: StaffEntity, pin: String): StaffEntity = withContext(Dispatchers.Default) {
        val salt = PinHasher.newSalt()
        base.copy(
            pinHash = PinHasher.toHex(PinHasher.hash(pin, salt, PinHasher.ITERATIONS)), pinSalt = PinHasher.toHex(salt),
            pinIterations = PinHasher.ITERATIONS, failedAttempts = 0, lockedUntil = 0, updatedAt = System.currentTimeMillis(), dirty = true,
        )
    }

    private suspend fun requireFreeName(name: String, exceptId: String? = null) {
        if (name.isBlank()) throw StaffException(StaffException.Error.NAME_REQUIRED)
        val clash = dao.findActiveByName(name.trim())
        if (clash != null && clash.id != exceptId) throw StaffException(StaffException.Error.NAME_TAKEN)
    }

    /** Turns staff accounts on: the person using the phone now becomes a real, PIN-protected owner. */
    suspend fun createOwner(name: String, pin: String): String {
        if (dao.activeOwnerCount() > 0) throw StaffException(StaffException.Error.OWNER_EXISTS)
        requireFreeName(name); checkPin(pin)
        val owner = withPin(StaffEntity(name = name.trim(), role = Role.OWNER), pin)
        dao.insert(owner)
        audit.log(AuditAction.STAFF_ADDED, "Owner account created: ${owner.name}", "STAFF", owner.id,
            actor = Actor(owner.id, owner.name, Role.OWNER, RolePermissions.of(Role.OWNER)))
        return owner.id
    }

    suspend fun addStaff(name: String, role: Role, pin: String): String {
        requireFreeName(name); checkPin(pin)
        val s = withPin(StaffEntity(name = name.trim(), role = role), pin)
        dao.insert(s)
        audit.log(AuditAction.STAFF_ADDED, "Added ${s.name} as ${role.name.lowercase()}", "STAFF", s.id)
        return s.id
    }

    suspend fun update(id: String, name: String, role: Role, active: Boolean) {
        val s = dao.getById(id) ?: throw StaffException(StaffException.Error.NOT_FOUND)
        requireFreeName(name, exceptId = id)
        val losesOwnerRights = s.role == Role.OWNER && s.isActive && (role != Role.OWNER || !active)
        if (losesOwnerRights && dao.activeOwnerCount() <= 1) throw StaffException(StaffException.Error.LAST_OWNER)
        dao.update(s.copy(name = name.trim(), role = role, isActive = active, updatedAt = System.currentTimeMillis(), dirty = true))
        val changes = buildList {
            if (s.name != name.trim()) add("name ${s.name} -> ${name.trim()}")
            if (s.role != role) add("role ${s.role.name.lowercase()} -> ${role.name.lowercase()}")
            if (s.isActive != active) add(if (active) "re-activated" else "deactivated")
        }
        if (changes.isNotEmpty()) audit.log(AuditAction.STAFF_CHANGED, "${s.name}: ${changes.joinToString(", ")}", "STAFF", id)
    }

    suspend fun setPin(id: String, pin: String) {
        val s = dao.getById(id) ?: throw StaffException(StaffException.Error.NOT_FOUND)
        checkPin(pin)
        dao.update(withPin(s, pin))
        audit.log(AuditAction.PIN_CHANGED, "PIN changed for ${s.name}", "STAFF", id)
    }

    suspend fun authenticate(id: String, pin: String, now: Long = System.currentTimeMillis()): AuthResult {
        val s = dao.getById(id)
        if (s == null || !s.isActive || s.deletedAt != null || s.pinHash == null || s.pinSalt == null) return AuthResult.Unknown
        if (s.lockedUntil > now) return AuthResult.LockedOut(s.lockedUntil)

        val ok = withContext(Dispatchers.Default) { PinHasher.verify(pin, PinHasher.fromHex(s.pinSalt), s.pinIterations, PinHasher.fromHex(s.pinHash)) }
        val actor = Actor(s.id, s.name, s.role, RolePermissions.of(s.role))
        if (ok) {
            dao.update(s.copy(failedAttempts = 0, lockedUntil = 0, updatedAt = now))
            audit.log(AuditAction.LOGIN, "${s.name} signed in", "STAFF", s.id, actor)
            return AuthResult.Success(s)
        }
        val attempts = s.failedAttempts + 1
        val lockFor = Lockout.lockMillis(attempts)
        dao.update(s.copy(failedAttempts = attempts, lockedUntil = if (lockFor > 0) now + lockFor else 0, updatedAt = now))
        audit.log(AuditAction.LOGIN_FAILED, "Wrong PIN for ${s.name} (attempt $attempts)", "STAFF", s.id, actor)
        return if (lockFor > 0) AuthResult.LockedOut(now + lockFor) else AuthResult.WrongPin((Lockout.FREE_ATTEMPTS - attempts).coerceAtLeast(0))
    }
}
