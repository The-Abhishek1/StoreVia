package com.xcloak.storevia.domain.customer

import com.xcloak.storevia.feature.customers.KhataText
import org.junit.Assert.assertTrue
import org.junit.Test

class KhataTextTest {
    @Test fun reminderMentionsNameStoreAndAmount() {
        val t = KhataText.reminder("Ravi Stores", "Suresh", 123_450)
        assertTrue(t.contains("Suresh")); assertTrue(t.contains("Ravi Stores")); assertTrue(t.contains("₹1,234.50"))
    }
}
