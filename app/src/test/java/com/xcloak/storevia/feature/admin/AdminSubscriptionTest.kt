package com.xcloak.storevia.feature.admin

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminSubscriptionTest {
    private val now = Instant.parse("2026-11-05T12:00:00Z")

    @Test
    fun activeProWithOffsetExpiryIsEnabled() {
        val subscription = AdminSubscription(
            storeId = "store-1",
            plan = "PRO",
            status = "active",
            expiresAt = "2126-11-03T19:00:55.520239+00:00",
        )

        assertTrue(subscription.isActiveAt(now))
        assertTrue(subscription.hasActiveProAccess(now))
    }

    @Test
    fun expiredOrInvalidSubscriptionIsNotEnabled() {
        assertFalse(
            AdminSubscription(
                storeId = "store-1",
                plan = "pro",
                status = "active",
                expiresAt = "2026-11-05T11:00:00Z",
            )
                .isActiveAt(now),
        )
        assertFalse(
            AdminSubscription(
                storeId = "store-1",
                plan = "pro",
                status = "active",
                expiresAt = "invalid",
            )
                .isActiveAt(now),
        )
        val activeFree = AdminSubscription(
            storeId = "store-1",
            plan = "free",
            status = "active",
            expiresAt = "2126-11-03T19:00:55+00:00",
        )
        assertTrue(activeFree.isActiveAt(now))
        assertFalse(activeFree.hasActiveProAccess(now))
    }
}
