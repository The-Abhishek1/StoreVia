package com.xcloak.storevia.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xcloak.storevia.core.access.*
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.entity.*
import com.xcloak.storevia.data.audit.AuditLogger
import com.xcloak.storevia.data.product.ProductRepository
import com.xcloak.storevia.domain.sale.*
import com.xcloak.storevia.domain.stock.AdjustStockUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** A stand-in for the signed-in person that tests can switch between roles. */
class FakeAccess(var role: Role = Role.OWNER, var name: String = "Asha", var options: CashierOptions = CashierOptions()) : Access {
    override fun actor() = Actor("u-$name", name, role, RolePermissions.of(role, options))
}

/** Product: ₹10 per piece, cost ₹7. 1000 = 1 piece. */
@RunWith(AndroidJUnit4::class)
class AccessControlTest {
    private lateinit var db: StorviaDatabase
    private val access = FakeAccess()
    private lateinit var audit: AuditLogger
    private lateinit var sell: CompleteSaleUseCase
    private lateinit var giveBack: ReturnItemsUseCase
    private lateinit var cancel: CancelSaleUseCase
    private lateinit var adjust: AdjustStockUseCase
    private lateinit var receive: ReceiveStockUseCase
    private lateinit var products: ProductRepository
    private lateinit var product: ProductEntity
    private lateinit var batch: String
    private val today = LocalDate.of(2026, 10, 20)

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StorviaDatabase::class.java).allowMainThreadQueries().build()
        audit = AuditLogger(db.auditDao(), access)
        sell = CompleteSaleUseCase(db, db.storeDao(), db.productDao(), db.batchDao(), db.stockMovementDao(), db.saleDao(), db.customerDao(), access, audit)
        giveBack = ReturnItemsUseCase(db, db.saleDao(), db.batchDao(), db.stockMovementDao(), db.customerDao(), access, audit)
        cancel = CancelSaleUseCase(db, db.saleDao(), giveBack, access, audit)
        adjust = AdjustStockUseCase(db, db.batchDao(), db.stockMovementDao(), access, audit)
        receive = ReceiveStockUseCase(db, db.batchDao(), db.stockMovementDao(), access, audit)
        products = ProductRepository(db, db.productDao(), access, audit)

        db.storeDao().upsert(StoreEntity(name = "Test"))
        val unit = UnitEntity(name = "Piece", symbol = "pcs", allowDecimal = false)
        db.unitDao().insertAll(listOf(unit))
        product = ProductEntity(name = "Biscuits", unitId = unit.id, sellPricePaise = 1000, costPaise = 700)
        db.productDao().insert(product)
        batch = receive(product.id, 10_000, 700, "A", today.plusDays(60))      // owner: allowed
        Unit
    }

    @After fun tearDown() = db.close()

    private fun cash(p: Long) = listOf(PaymentInput(PaymentMethod.CASH, p))
    private suspend fun stock() = db.batchDao().getById(batch)!!.qtyOnHandMilli
    private suspend fun denied(p: Permission, block: suspend () -> Unit) {
        try { block(); fail("expected PermissionDeniedException($p)") } catch (e: PermissionDeniedException) { assertEquals(p, e.permission) }
    }

    @Test fun platformPermissionsAreNotGrantedToStoreRoles() {
        listOf(Role.OWNER, Role.MANAGER, Role.CASHIER).forEach { role ->
            assertFalse(Permission.MANAGE_PLATFORM in RolePermissions.of(role))
            assertFalse(Permission.VIEW_PLATFORM_INSIGHTS in RolePermissions.of(role))
        }
        assertTrue(Permission.MANAGE_PLATFORM in RolePermissions.of(Role.PLATFORM_ADMIN))
        assertTrue(Permission.VIEW_PLATFORM_INSIGHTS in RolePermissions.of(Role.PLATFORM_ADMIN))
    }

    @Test fun cashierCanSellButNotDiscountOverridePriceOrGiveCredit() = runBlocking {
        access.role = Role.CASHIER
        sell(listOf(CartItem(product.id, 1_000, 1000)), cash(1_000), today = today)            // plain sale: fine
        val before = stock()

        denied(Permission.APPLY_DISCOUNT) { sell(listOf(CartItem(product.id, 1_000, 1000, discountPaise = 100)), cash(900), today = today) }
        denied(Permission.APPLY_DISCOUNT) { sell(listOf(CartItem(product.id, 1_000, 1000)), cash(900), today = today, billDiscountPaise = 100) }
        denied(Permission.OVERRIDE_PRICE) { sell(listOf(CartItem(product.id, 1_000, 900)), cash(900), today = today) }
        val c = CustomerEntity(name = "Ravi"); db.customerDao().insert(c)
        denied(Permission.SELL_ON_CREDIT) { sell(listOf(CartItem(product.id, 1_000, 1000)), cash(400), customerId = c.id, today = today) }

        assertEquals(before, stock())                      // every refused sale left no trace
        assertEquals(0L, db.customerDao().balance(c.id))
    }

    @Test fun ownerCanLoosenDiscountsAndCreditForCashiers() = runBlocking {
        access.role = Role.CASHIER; access.options = CashierOptions(canDiscount = true, canSellOnCredit = true)
        val c = CustomerEntity(name = "Ravi"); db.customerDao().insert(c)
        sell(listOf(CartItem(product.id, 2_000, 1000, discountPaise = 200)), cash(1_000), customerId = c.id, today = today)
        assertEquals(800L, db.customerDao().balance(c.id))                                       // 1800 bill - 1000 paid
        val log = db.auditDao().all().single { it.action == AuditAction.SALE_DISCOUNT }
        assertEquals("Asha", log.actorName); assertEquals("CASHIER", log.actorRole)
        assertTrue(log.summary.contains("₹2.00"))
    }

    @Test fun cashierCannotReturnCancelAdjustOrReceiveStock() = runBlocking {
        access.role = Role.MANAGER
        val id = sell(listOf(CartItem(product.id, 2_000, 1000)), cash(2_000), today = today)
        val before = stock()

        access.role = Role.CASHIER
        denied(Permission.PROCESS_RETURN) { giveBack(id, listOf(ReturnItem(product.id, 1_000))) }
        denied(Permission.CANCEL_BILL) { cancel(id, "x") }
        denied(Permission.ADJUST_STOCK) { adjust(batch, MovementType.DAMAGE, 1_000) }
        denied(Permission.MANAGE_PURCHASES) { receive(product.id, 1_000, 700) }
        assertEquals(before, stock())
        assertEquals(SaleStatus.COMPLETED, db.saleDao().getById(id)!!.status)
    }

    @Test fun cancelByManagerIsRecordedWithWhoDidIt() = runBlocking {
        access.role = Role.MANAGER; access.name = "Ravi"
        val id = sell(listOf(CartItem(product.id, 2_000, 1000)), cash(2_000), today = today)
        cancel(id, "customer changed mind")

        val rows = db.auditDao().all()
        val log = rows.single { it.action == AuditAction.CANCEL_BILL }
        assertEquals("Ravi", log.actorName); assertEquals("MANAGER", log.actorRole)
        assertTrue(log.summary.contains("customer changed mind"))
        assertEquals(0, rows.count { it.action == AuditAction.RETURN })      // cancel doesn't also log a separate return
    }

    @Test fun priceChangesAreAuditedAndNeedProductRights() = runBlocking {
        access.role = Role.MANAGER
        products.save(product.copy(sellPricePaise = 1_200), null, isNew = false)
        val log = db.auditDao().all().single { it.action == AuditAction.PRODUCT_PRICE_CHANGED }
        assertTrue(log.summary, log.summary.contains("₹10.00 -> ₹12.00"))

        access.role = Role.CASHIER
        try { products.save(product.copy(sellPricePaise = 1), null, isNew = false); fail() } catch (e: PermissionDeniedException) { }
        assertEquals(1_200L, db.productDao().getById(product.id)!!.sellPricePaise)
    }

    @Test fun stockAdjustmentsAreAudited() = runBlocking {
        access.role = Role.MANAGER; access.name = "Ravi"
        adjust(batch, MovementType.DAMAGE, 2_000, "dropped crate")
        val log = db.auditDao().all().single { it.action == AuditAction.STOCK_ADJUST }
        assertTrue(log.summary.contains("Biscuits")); assertTrue(log.summary.contains("dropped crate"))
        assertEquals("Ravi", log.actorName)
    }
}
