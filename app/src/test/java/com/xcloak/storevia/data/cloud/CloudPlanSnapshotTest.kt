package com.xcloak.storevia.data.cloud

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPlanSnapshotTest {
    private val now = Instant.parse("2026-11-05T12:00:00Z")

    @Test
    fun activeUnexpiredProHasAccess() {
        val plan = CloudPlanSnapshot("pro", "active", "2026-11-06T12:00:00Z")

        assertTrue(plan.hasActiveProAccess(now))
    }

    @Test
    fun activeUnexpiredProAcceptsSupabaseOffsetTimestamp() {
        val plan = CloudPlanSnapshot("pro", "active", "2026-11-06T12:00:00+00:00")

        assertTrue(plan.hasActiveProAccess(now))
    }

    @Test
    fun freeExpiredAndInvalidExpiryDoNotHaveAccess() {
        assertFalse(CloudPlanSnapshot("free", "active", "2026-11-06T12:00:00Z").hasActiveProAccess(now))
        assertFalse(CloudPlanSnapshot("pro", "expired", "2026-11-06T12:00:00Z").hasActiveProAccess(now))
        assertFalse(CloudPlanSnapshot("pro", "active", "2026-11-05T11:59:59Z").hasActiveProAccess(now))
        assertFalse(CloudPlanSnapshot("pro", "active", "not-a-timestamp").hasActiveProAccess(now))
    }

    @Test
    fun rewardedAccessIsIndependentOfPaidSubscriptionState() {
        assertTrue(
            CloudPlanSnapshot(
                plan = "free",
                status = "active",
                expiresAt = null,
                rewardedExpiresAt = "2026-11-05T12:15:00Z",
            ).hasActiveProAccess(now),
        )
        assertFalse(
            CloudPlanSnapshot(
                plan = "free",
                status = "active",
                expiresAt = null,
                rewardedExpiresAt = "2026-11-05T11:59:59Z",
            ).hasActiveProAccess(now),
        )
    }
}
