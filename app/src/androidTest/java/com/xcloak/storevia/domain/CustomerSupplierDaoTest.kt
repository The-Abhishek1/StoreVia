package com.xcloak.storevia.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xcloak.storevia.core.access.Access
import com.xcloak.storevia.core.access.Actor
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.access.PermissionDeniedException
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.entity.*
import com.xcloak.storevia.domain.customer.ReceivePaymentUseCase
import com.xcloak.storevia.domain.purchase.PaySupplierUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomerSupplierDaoTest {
    private lateinit var db: StorviaDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StorviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun customerListsAndTotalsExcludeSoftDeletedRecords() = runBlocking {
        val active = CustomerEntity(id = "active-customer", name = "Active")
        val deleted = CustomerEntity(id = "deleted-customer", name = "Deleted", deletedAt = 1L)
        db.customerDao().insert(active)
        db.customerDao().insert(deleted)
        db.customerDao().insertLedger(
            CustomerLedgerEntity(customerId = active.id, type = LedgerType.ADJUSTMENT, amountPaise = 125),
        )
        db.customerDao().insertLedger(
            CustomerLedgerEntity(customerId = deleted.id, type = LedgerType.ADJUSTMENT, amountPaise = 500),
        )

        assertEquals(listOf("active-customer"), db.customerDao().observeWithBalance("").first().map { it.id })
        assertEquals(125L, db.customerDao().observeTotalDue().first())
        assertNull(db.customerDao().observeById(deleted.id).first())
    }

    @Test
    fun supplierListsAndTotalsExcludeSoftDeletedRecords() = runBlocking {
        val active = SupplierEntity(id = "active-supplier", name = "Active")
        val deleted = SupplierEntity(id = "deleted-supplier", name = "Deleted", deletedAt = 1L)
        db.supplierDao().insert(active)
        db.supplierDao().insert(deleted)
        db.supplierDao().insertLedger(
            SupplierLedgerEntity(supplierId = active.id, type = SupplierLedgerType.ADJUSTMENT, amountPaise = 250),
        )
        db.supplierDao().insertLedger(
            SupplierLedgerEntity(supplierId = deleted.id, type = SupplierLedgerType.ADJUSTMENT, amountPaise = 750),
        )

        assertEquals(listOf("active-supplier"), db.supplierDao().observeWithBalance("").first().map { it.id })
        assertEquals(250L, db.supplierDao().observeTotalPayable().first())
        assertNull(db.supplierDao().observeById(deleted.id).first())
    }

    @Test
    fun receivingCustomerPaymentReducesKhataBalance() = runBlocking {
        val customer = CustomerEntity(id = "payment-customer", name = "Customer")
        db.customerDao().insert(customer)
        db.customerDao().insertLedger(
            CustomerLedgerEntity(customerId = customer.id, type = LedgerType.ADJUSTMENT, amountPaise = 1_000, createdAt = 1L),
        )

        ReceivePaymentUseCase(db, db.customerDao(), db.saleDao())(
            customer.id, 400, PaymentMethod.UPI, now = 2L,
        )

        assertEquals(600L, db.customerDao().balance(customer.id))
        assertEquals(-400L, db.customerDao().observeLedger(customer.id).first().last().amountPaise)
    }

    @Test
    fun payingSupplierReducesPayableBalance() = runBlocking {
        val supplier = SupplierEntity(id = "payment-supplier", name = "Supplier")
        db.supplierDao().insert(supplier)
        db.supplierDao().insertLedger(
            SupplierLedgerEntity(supplierId = supplier.id, type = SupplierLedgerType.ADJUSTMENT, amountPaise = 1_000, createdAt = 1L),
        )

        PaySupplierUseCase(db, db.supplierDao())(
            supplier.id, 400, PaymentMethod.CARD, now = 2L,
        )

        assertEquals(600L, db.supplierDao().balance(supplier.id))
        val payment = db.supplierDao().observeLedger(supplier.id).first().last()
        assertEquals(SupplierLedgerType.PAYMENT, payment.type)
        assertEquals(-400L, payment.amountPaise)
        assertEquals(PaymentMethod.CARD, payment.method)
    }

    @Test
    fun paymentUseCasesRequireTheirPermissions() = runBlocking {
        val denied = object : Access { override fun actor() = Actor.NONE }
        val customerUseCase = ReceivePaymentUseCase(db, db.customerDao(), db.saleDao(), denied)
        val supplierUseCase = PaySupplierUseCase(db, db.supplierDao(), denied)

        try {
            customerUseCase("customer", 1, PaymentMethod.CASH)
            throw AssertionError("Customer payment should require permission")
        } catch (e: PermissionDeniedException) {
            assertEquals(Permission.RECEIVE_KHATA_PAYMENT, e.permission)
        }
        try {
            supplierUseCase("supplier", 1, PaymentMethod.CASH)
            throw AssertionError("Supplier payment should require permission")
        } catch (e: PermissionDeniedException) {
            assertEquals(Permission.MANAGE_PURCHASES, e.permission)
        }
    }
}
