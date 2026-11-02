package com.xcloak.storevia.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xcloak.storevia.core.database.entity.CustomerEntity
import com.xcloak.storevia.core.database.entity.MovementType
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.sync.CacheRebuilder
import com.xcloak.storevia.domain.sale.RefundMode
import com.xcloak.storevia.domain.sale.ReturnItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The sync design rests on one claim: the cached columns (batch quantity, a bill's paid / returned / status) can always
 * be recomputed from the permanent records. This runs a messy day of real activity through the app's own use cases and
 * checks that recomputing changes nothing.
 */
@RunWith(AndroidJUnit4::class)
class CacheRebuilderTest {
    private val phone = Phone("")

    @After fun tearDown() = phone.close()

    private fun caches() = listOf(
        phone.rows("SELECT id, qtyOnHandMilli FROM batches ORDER BY id"),
        phone.rows("SELECT id, returnedQtyMilli, returnedAmountPaise FROM sale_lines ORDER BY id"),
        phone.rows("SELECT id, paidPaise, returnedPaise, status FROM sales ORDER BY id"),
    )

    @Test fun recomputingAfterARealMessyDayChangesNothing() = runBlocking {
        val p = phone.seedShop()
        val ravi = CustomerEntity(name = "Ravi"); phone.db.customerDao().insert(ravi)

        val s1 = phone.sellPieces(p, 3); phone.giveBack(s1, listOf(ReturnItem(p.id, 1_000)))                              // cash refund
        val s2 = phone.sellPieces(p, 4, paid = 3_500, customerId = ravi.id)                                               // owes ₹5
        phone.giveBack(s2, listOf(ReturnItem(p.id, 2_000)), RefundMode.KhataCredit)                                        // ₹5 off the debt, ₹15 kept as credit
        runCatching { phone.takePayment(ravi.id, 100, PaymentMethod.CASH) }                                                // nothing owed now (balance is credit): refused, changes nothing
        val s3 = phone.sellPieces(p, 2); phone.cancel(s3, null)                                                            // cancelled bill
        phone.adjust(phone.db.batchDao().fefoBatches(p.id, LocalDate.now()).first().id, MovementType.DAMAGE, 1_000, "dropped")
        phone.receive(p.id, 5_000, 700, "B", LocalDate.now().plusDays(90))                                                 // a second batch
        phone.sellPieces(p, 50)                                                                                            // far more than in stock: part has no batch

        val before = caches()
        CacheRebuilder(phone.db).recomputeAll()
        assertEquals(before, caches())
        assertTrue("the scenario must include a refund kept as khata credit", phone.rows("SELECT id FROM sale_returns WHERE keptAsCredit = 1").isNotEmpty())
    }
}
