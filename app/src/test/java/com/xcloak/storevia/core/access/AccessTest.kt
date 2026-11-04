package com.xcloak.storevia.core.access

import com.xcloak.storevia.data.staff.Lockout
import com.xcloak.storevia.data.staff.PinHasher
import com.xcloak.storevia.data.staff.PinPolicy
import org.junit.Assert.*
import org.junit.Test

class AccessTest {
    @Test fun ownerCanManageStoreButNotPlatform() {
        val owner = RolePermissions.of(Role.OWNER)
        assertEquals(Permission.entries.toSet() - setOf(Permission.MANAGE_PLATFORM, Permission.VIEW_PLATFORM_INSIGHTS), owner)
    }

    @Test fun onlyPlatformAdminCanManagePlatform() {
        listOf(Role.OWNER, Role.MANAGER, Role.CASHIER).forEach { role ->
            val permissions = RolePermissions.of(role)
            assertFalse(Permission.MANAGE_PLATFORM in permissions)
            assertFalse(Permission.VIEW_PLATFORM_INSIGHTS in permissions)
        }
        val admin = RolePermissions.of(Role.PLATFORM_ADMIN)
        assertTrue(Permission.MANAGE_PLATFORM in admin)
        assertTrue(Permission.VIEW_PLATFORM_INSIGHTS in admin)
    }

    @Test fun managerCannotTouchStaffSettingsRestoreOrAudit() {
        val m = RolePermissions.of(Role.MANAGER)
        listOf(Permission.MANAGE_STAFF, Permission.MANAGE_SETTINGS, Permission.RESTORE, Permission.VIEW_AUDIT).forEach { assertFalse(it.name, it in m) }
        listOf(Permission.CANCEL_BILL, Permission.PROCESS_RETURN, Permission.ADJUST_STOCK, Permission.VIEW_REPORTS, Permission.VIEW_COST, Permission.BACKUP).forEach { assertTrue(it.name, it in m) }
    }

    @Test fun cashierCanOnlySellAndTakeKhata() {
        val c = RolePermissions.of(Role.CASHIER)
        assertEquals(setOf(Permission.SELL, Permission.MANAGE_CUSTOMERS, Permission.RECEIVE_KHATA_PAYMENT), c)
        assertFalse(Permission.VIEW_COST in c); assertFalse(Permission.VIEW_REPORTS in c); assertFalse(Permission.PROCESS_RETURN in c)
    }

    @Test fun purchaseAndCategoryManagementAreUnavailableToCashiers() {
        val manager = RolePermissions.of(Role.MANAGER)
        assertTrue(Permission.MANAGE_PURCHASES in manager)
        assertTrue(Permission.MANAGE_PRODUCTS in manager)

        val cashier = RolePermissions.of(Role.CASHIER)
        assertFalse(Permission.MANAGE_PURCHASES in cashier)
        assertFalse(Permission.MANAGE_PRODUCTS in cashier)
    }

    @Test fun ownerCanLoosenTwoThingsForCashiers() {
        val c = RolePermissions.of(Role.CASHIER, CashierOptions(canDiscount = true, canSellOnCredit = true))
        assertTrue(Permission.APPLY_DISCOUNT in c); assertTrue(Permission.SELL_ON_CREDIT in c)
        assertFalse(Permission.OVERRIDE_PRICE in c); assertFalse(Permission.CANCEL_BILL in c)
    }

    @Test fun batchOverrideIsOwnerConfigurableForCashiers() {
        assertFalse(Permission.OVERRIDE_BATCH in RolePermissions.of(Role.CASHIER))
        assertTrue(Permission.OVERRIDE_BATCH in RolePermissions.of(Role.CASHIER, CashierOptions(canOverrideBatch = true)))
    }

    @Test fun requireThrowsWhenNotAllowed() {
        val cashier = object : Access { override fun actor() = Actor("c", "Asha", Role.CASHIER, RolePermissions.of(Role.CASHIER)) }
        cashier.require(Permission.SELL)
        val e = assertThrows(PermissionDeniedException::class.java) { cashier.require(Permission.CANCEL_BILL) }
        assertEquals(Permission.CANCEL_BILL, e.permission)
        assertThrows(PermissionDeniedException::class.java) { object : Access { override fun actor() = Actor.NONE }.require(Permission.SELL) }
    }

    @Test fun pinPolicy() {
        assertEquals(PinPolicy.Result.OK, PinPolicy.validate("4827"))
        assertEquals(PinPolicy.Result.OK, PinPolicy.validate("390417"))
        assertEquals(PinPolicy.Result.BAD_LENGTH, PinPolicy.validate("123"))
        assertEquals(PinPolicy.Result.BAD_LENGTH, PinPolicy.validate("1234567"))
        assertEquals(PinPolicy.Result.NOT_DIGITS, PinPolicy.validate("12a4"))
        listOf("0000", "1111", "1234", "4321", "123456", "9876").forEach { assertEquals(it, PinPolicy.Result.TOO_SIMPLE, PinPolicy.validate(it)) }
    }

    @Test fun lockoutGrowsAfterFiveFailures() {
        assertEquals(0L, Lockout.lockMillis(4))
        assertEquals(30_000L, Lockout.lockMillis(5)); assertEquals(30_000L, Lockout.lockMillis(9))
        assertEquals(60_000L, Lockout.lockMillis(10)); assertEquals(120_000L, Lockout.lockMillis(15))
        assertEquals(15 * 60_000L, Lockout.lockMillis(500))
    }

    @Test fun pinHashingVerifiesAndSaltsDiffer() {
        val salt = PinHasher.newSalt()
        val h = PinHasher.hash("4827", salt, 1_000)
        assertTrue(PinHasher.verify("4827", salt, 1_000, h))
        assertFalse(PinHasher.verify("4828", salt, 1_000, h))
        assertFalse(PinHasher.verify("4827", PinHasher.newSalt(), 1_000, h))
        assertArrayEquals(h, PinHasher.fromHex(PinHasher.toHex(h)))
    }
}
