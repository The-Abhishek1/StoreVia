package com.xcloak.storevia.core.access

enum class Role { OWNER, MANAGER, CASHIER, PLATFORM_ADMIN }

enum class Permission {
    SELL, APPLY_DISCOUNT, OVERRIDE_PRICE, SELL_ON_CREDIT,
    PROCESS_RETURN, CANCEL_BILL,
    MANAGE_CUSTOMERS, EDIT_CREDIT_LIMIT, RECEIVE_KHATA_PAYMENT,
    MANAGE_PRODUCTS, VIEW_COST, ADJUST_STOCK, MANAGE_PURCHASES, OVERRIDE_BATCH,
    VIEW_REPORTS, EXPORT_DATA, IMPORT_DATA,
    MANAGE_SETTINGS, BACKUP, RESTORE, MANAGE_STAFF, VIEW_AUDIT,
    MANAGE_PLATFORM, VIEW_PLATFORM_INSIGHTS,
}

/** The owner-configurable permissions granted to cashiers. */
data class CashierOptions(
    val canDiscount: Boolean = false,
    val canSellOnCredit: Boolean = false,
    val canOverrideBatch: Boolean = false,
)

object RolePermissions {
    private val platformPermissions = setOf(Permission.MANAGE_PLATFORM, Permission.VIEW_PLATFORM_INSIGHTS)
    private val manager: Set<Permission> = Permission.entries.toSet() -
        setOf(Permission.MANAGE_SETTINGS, Permission.RESTORE, Permission.MANAGE_STAFF, Permission.VIEW_AUDIT) - platformPermissions

    private val cashier = setOf(Permission.SELL, Permission.MANAGE_CUSTOMERS, Permission.RECEIVE_KHATA_PAYMENT)

    fun of(role: Role, options: CashierOptions = CashierOptions()): Set<Permission> = when (role) {
        Role.OWNER -> Permission.entries.toSet() - platformPermissions
        Role.MANAGER -> manager
        Role.CASHIER -> buildSet {
            addAll(cashier)
            if (options.canDiscount) add(Permission.APPLY_DISCOUNT)
            if (options.canSellOnCredit) add(Permission.SELL_ON_CREDIT)
            if (options.canOverrideBatch) add(Permission.OVERRIDE_BATCH)
        }
        Role.PLATFORM_ADMIN -> Permission.entries.toSet()
    }
}

data class Actor(val id: String?, val name: String, val role: Role, val permissions: Set<Permission>) {
    fun can(p: Permission) = p in permissions

    companion object {
        /** Before any staff accounts exist, whoever holds the phone is the owner. */
        val LOCAL_OWNER = Actor(null, "Owner", Role.OWNER, Permission.entries.toSet())
        /** Nobody is signed in: no permissions at all. */
        val NONE = Actor("none", "", Role.CASHIER, emptySet())
    }
}

class PermissionDeniedException(val permission: Permission) : Exception("Not allowed: $permission")

/** Who is acting right now. Use cases ask this before doing anything sensitive. */
interface Access {
    fun actor(): Actor
    fun require(p: Permission) { if (!actor().can(p)) throw PermissionDeniedException(p) }
}

/** Full rights. The default in tests and for a single-user phone. */
object OwnerAccess : Access { override fun actor() = Actor.LOCAL_OWNER }

enum class AuditAction {
    LOGIN, LOGIN_FAILED, STAFF_ADDED, STAFF_CHANGED, PIN_CHANGED,
    SALE_DISCOUNT, PRICE_OVERRIDE, RETURN, CANCEL_BILL, STOCK_ADJUST, STOCK_RECEIVED,
    PURCHASE, SUPPLIER_PAYMENT, KHATA_PAYMENT, OLD_BALANCE_ADDED, CREDIT_LIMIT_CHANGED,
    PRODUCT_PRICE_CHANGED, PRODUCT_ARCHIVED, IMPORT, EXPORT, BACKUP, RESTORE, SETTINGS_CHANGED, BATCH_OVERRIDE,
}

/** Where sensitive actions are recorded. Called inside the same transaction as the action itself. */
interface AuditSink {
    suspend fun log(action: AuditAction, summary: String, entityType: String? = null, entityId: String? = null, actor: Actor? = null)
}

object NoAudit : AuditSink {
    override suspend fun log(action: AuditAction, summary: String, entityType: String?, entityId: String?, actor: Actor?) = Unit
}
