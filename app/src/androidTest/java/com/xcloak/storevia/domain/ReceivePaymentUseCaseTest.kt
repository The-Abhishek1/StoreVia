package com.xcloak.storevia.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.entity.*
import com.xcloak.storevia.domain.customer.KhataException
import com.xcloak.storevia.domain.customer.ReceivePaymentUseCase
import com.xcloak.storevia.domain.sale.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ReceivePaymentUseCaseTest {
    private lateinit var db: StorviaDatabase
    private lateinit var sell: CompleteSaleUseCase
    private lateinit var receive: ReceiveStockUseCase
    private lateinit var pay: ReceivePaymentUseCase
    private val today = LocalDate.of(2026, 10, 20)
    private lateinit var product: ProductEntity
    private lateinit var customer: CustomerEntity

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StorviaDatabase::class.java).allowMainThreadQueries().build()
        sell = CompleteSaleUseCase(db, db.storeDao(), db.productDao(), db.batchDao(), db.stockMovementDao(), db.saleDao(), db.customerDao())
        receive = ReceiveStockUseCase(db, db.batchDao(), db.stockMovementDao())
        pay = ReceivePaymentUseCase(db, db.customerDao(), db.saleDao())
        db.storeDao().upsert(StoreEntity(name = "Test"))
        val unit = UnitEntity(name = "Piece", symbol = "pcs", allowDecimal = false)
        db.unitDao().insertAll(listOf(unit))
        product = ProductEntity(name = "Rice", unitId = unit.id, sellPricePaise = 1000)
        db.productDao().insert(product)
        receive(product.id, 100_000, 700, "A", today.plusDays(90))
        customer = CustomerEntity(name = "Ravi"); db.customerDao().insert(customer)
        Unit
    }

    @After fun tearDown() = db.close()

    private suspend fun creditSale(pcs: Long): String =
        sell(listOf(CartItem(product.id, pcs * 1000, 1000)), emptyList(), customerId = customer.id, today = today)

    @Test fun paymentSettlesOldestBillFirst() = runBlocking {
        val first = creditSale(3)    // ₹30
        val second = creditSale(2)   // ₹20
        pay(customer.id, 4_000, PaymentMethod.CASH)   // ₹40 -> first fully (30) + 10 of second

        val dao = db.saleDao()
        assertEquals(3_000L, dao.observeById(first).first()!!.paidPaise)
        assertEquals(1_000L, dao.observeById(second).first()!!.paidPaise)
        assertEquals(1_000L, db.customerDao().balance(customer.id))
    }

    @Test fun cannotPayMoreThanBalance() = runBlocking {
        creditSale(1)   // ₹10 owed
        try {
            pay(customer.id, 2_000, PaymentMethod.CASH)
            fail("expected KhataException")
        } catch (e: KhataException) {
            assertEquals(KhataException.Reason.EXCEEDS_BALANCE, e.reason)
        }
        assertEquals(1_000L, db.customerDao().balance(customer.id))
    }

    @Test fun creditLimitBlocksSale() = runBlocking {
        db.customerDao().update(customer.copy(creditLimitPaise = 2_500))
        creditSale(2)   // ₹20 owed, limit ₹25
        try {
            creditSale(1)   // would reach ₹30
            fail("expected CreditLimitExceededException")
        } catch (e: CreditLimitExceededException) {
            assertEquals(2_500L, e.limitPaise)
        }
        assertEquals(2_000L, db.customerDao().balance(customer.id))
    }

    @Test fun oldBalancePaymentCreatesUnallocatedPaymentRow() = runBlocking {
        db.customerDao().insertLedger(
            CustomerLedgerEntity(customerId = customer.id, type = LedgerType.ADJUSTMENT, amountPaise = 5_000)
        )
        pay(customer.id, 5_000, PaymentMethod.UPI)
        assertEquals(0L, db.customerDao().balance(customer.id))
    }
}
