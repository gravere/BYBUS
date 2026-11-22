package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.PhysicalUserPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PhysicalUserPolicyTest {
    @Test fun guestZeroUsesTheRealPrivateOrWorkProfileWithoutGrantingCrossUserAccess() {
        for (pkg in listOf("com.ProjectMoon.LimbusCompany", "com.google.android.gms", "com.android.vending")) {
            assertEquals(11, PhysicalUserPolicy.systemUser(pkg, 0, 0, 11))
            assertEquals(0, PhysicalUserPolicy.systemUser(pkg, 0, 0, 0))
            assertEquals(11, PhysicalUserPolicy.systemUser(pkg, 11, 0, 11))
            assertEquals(12, PhysicalUserPolicy.systemUser(pkg, 12, 0, 11))
            assertEquals(-1, PhysicalUserPolicy.systemUser(pkg, -1, 0, 11))
        }
        assertEquals(0, PhysicalUserPolicy.systemUser("other.app", 0, 0, 11))
        assertEquals(0, PhysicalUserPolicy.systemUser(null, 0, 0, 11))
    }
}
