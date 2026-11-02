package com.xcloak.storevia.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xcloak.storevia.core.access.AuditAction
import com.xcloak.storevia.core.access.OwnerAccess
import com.xcloak.storevia.core.access.Role
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.data.audit.AuditLogger
import com.xcloak.storevia.data.staff.AuthResult
import com.xcloak.storevia.data.staff.StaffException
import com.xcloak.storevia.data.staff.StaffRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaffRepositoryTest {
    private lateinit var db: StorviaDatabase
    private lateinit var repo: StaffRepository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StorviaDatabase::class.java).allowMainThreadQueries().build()
        repo = StaffRepository(db.staffDao(), AuditLogger(db.auditDao(), OwnerAccess))
    }

    @After fun tearDown() = db.close()

    private suspend fun expect(error: StaffException.Error, block: suspend () -> Unit) {
        try { block(); fail("expected $error") } catch (e: StaffException) { assertEquals(error, e.error) }
    }

    @Test fun pinsAreStoredOnlyAsHashesAndSignInWorks() = runBlocking {
        val owner = repo.createOwner("Anil", "4827")
        val stored = db.staffDao().getById(owner)!!
        assertNotNull(stored.pinHash); assertNotEquals("4827", stored.pinHash); assertFalse(stored.pinHash!!.contains("4827"))

        assertTrue(repo.authenticate(owner, "4827") is AuthResult.Success)
        assertTrue(db.auditDao().all().any { it.action == AuditAction.LOGIN && it.actorName == "Anil" })
    }

    @Test fun onlyOneOwnerCanBeCreatedThisWay() = runBlocking {
        repo.createOwner("Anil", "4827")
        expect(StaffException.Error.OWNER_EXISTS) { repo.createOwner("Other", "5906") }
    }

    @Test fun weakAndMalformedPinsAreRejected() = runBlocking {
        expect(StaffException.Error.PIN_TOO_SIMPLE) { repo.createOwner("Anil", "1234") }
        expect(StaffException.Error.PIN_TOO_SIMPLE) { repo.createOwner("Anil", "0000") }
        expect(StaffException.Error.PIN_BAD_LENGTH) { repo.createOwner("Anil", "48") }
        expect(StaffException.Error.PIN_NOT_DIGITS) { repo.createOwner("Anil", "48a7") }
        expect(StaffException.Error.NAME_REQUIRED) { repo.createOwner(" ", "4827") }
    }

    @Test fun repeatedWrongPinsLockTheAccountThenItRecovers() = runBlocking {
        val owner = repo.createOwner("Anil", "4827")
        val t0 = 1_000_000L
        for (i in 1..4) assertEquals(AuthResult.WrongPin(5 - i), repo.authenticate(owner, "0852", now = t0 + i))
        val locked = repo.authenticate(owner, "0852", now = t0 + 5) as AuthResult.LockedOut      // 5th wrong try
        assertEquals(t0 + 5 + 30_000, locked.untilMillis)

        assertTrue(repo.authenticate(owner, "4827", now = t0 + 10_000) is AuthResult.LockedOut)   // even the right PIN waits
        assertTrue(repo.authenticate(owner, "4827", now = t0 + 40_000) is AuthResult.Success)    // lock over
        assertEquals(0, db.staffDao().getById(owner)!!.failedAttempts)
        assertEquals(5, db.auditDao().all().count { it.action == AuditAction.LOGIN_FAILED })
    }

    @Test fun theLastOwnerCannotBeRemoved() = runBlocking {
        val owner = repo.createOwner("Anil", "4827")
        expect(StaffException.Error.LAST_OWNER) { repo.update(owner, "Anil", Role.MANAGER, true) }
        expect(StaffException.Error.LAST_OWNER) { repo.update(owner, "Anil", Role.OWNER, false) }
        repo.update(owner, "Anil K", Role.OWNER, true)         // renaming is fine
    }

    @Test fun namesMustBeUniqueIgnoringCase() = runBlocking {
        repo.createOwner("Anil", "4827")
        repo.addStaff("Asha", Role.CASHIER, "5906")
        expect(StaffException.Error.NAME_TAKEN) { repo.addStaff("asha", Role.MANAGER, "7391") }
    }

    @Test fun aTurnedOffPersonCannotSignInAndPinResetWorks() = runBlocking {
        repo.createOwner("Anil", "4827")
        val asha = repo.addStaff("Asha", Role.CASHIER, "5906")
        repo.update(asha, "Asha", Role.CASHIER, active = false)
        assertEquals(AuthResult.Unknown, repo.authenticate(asha, "5906"))

        repo.update(asha, "Asha", Role.CASHIER, active = true)
        repo.setPin(asha, "6173")
        assertTrue(repo.authenticate(asha, "5906") is AuthResult.WrongPin)       // old PIN no longer works
        assertTrue(repo.authenticate(asha, "6173") is AuthResult.Success)
        assertTrue(db.auditDao().all().any { it.action == AuditAction.PIN_CHANGED })
    }
}
