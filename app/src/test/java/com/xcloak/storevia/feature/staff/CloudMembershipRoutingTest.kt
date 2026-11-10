package com.xcloak.storevia.feature.staff

import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.access.Role
import com.xcloak.storevia.core.access.cloudActor
import com.xcloak.storevia.data.cloud.cloudMember
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudMembershipRoutingTest {
    @Test
    fun authenticatedCloudOwnerUsesSupabaseMembershipRole() {
        val actor = cloudActor("OWNER", authenticated = true)

        assertEquals(Role.OWNER, actor.role)
        assertTrue(actor.can(Permission.MANAGE_STAFF))
        assertTrue(actor.can(Permission.MANAGE_SETTINGS))
        assertTrue(actor.can(Permission.IMPORT_DATA))
    }

    @Test
    fun unauthenticatedOrUnknownMembershipHasNoPermissions() {
        assertFalse(cloudActor("OWNER", authenticated = false).can(Permission.MANAGE_SETTINGS))
        assertFalse(cloudActor("UNKNOWN", authenticated = true).can(Permission.MANAGE_STAFF))
        assertFalse(cloudActor("CASHIER", authenticated = true).can(Permission.IMPORT_DATA))
    }

    @Test
    fun cloudMembershipMappingNeverContainsLocalPinCredentials() {
        val member = cloudMember(
            buildJsonObject {
                put("member_user_id", "auth-user-1")
                put("role", "MANAGER")
                put("display_name", "Casey")
            },
        )

        assertEquals("auth-user-1", member?.id)
        assertEquals("Casey", member?.name)
        assertEquals(Role.MANAGER, member?.role)
    }

    @Test
    fun malformedCloudMembershipIsNotPresentedAsAStaffAccount() {
        assertNull(cloudMember(buildJsonObject { put("role", "MANAGER") }))
        assertNull(
            cloudMember(
                buildJsonObject {
                    put("member_user_id", "auth-user-1")
                    put("role", "PLATFORM_ADMIN")
                },
            ),
        )
    }
}
