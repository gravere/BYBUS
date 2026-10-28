package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.AccountManagerCompat
import org.junit.Assert.*
import org.junit.Test

class AccountTokenInvalidationTest {
    @Test fun revokedCredentialIsRemovedFromEveryScope() {
        val tokens = mutableMapOf("play" to "old", "reauth" to "old", "other" to "valid")
        assertTrue(AccountManagerCompat.removeStoredToken(tokens, "old"))
        assertEquals(mapOf("other" to "valid"), tokens)
        assertFalse(AccountManagerCompat.removeStoredToken(tokens, "old"))
    }

    @Test fun customCacheInvalidationUsesAccountTypeAndStaysInItsUser() {
        data class Cached(val user: Int, val type: String, val scope: String, val token: String)
        val cache = mutableListOf(
            Cached(0, "com.google", "oauth2:play", "old"),
            Cached(0, "com.google", "oauth2:reauth", "old"),
            Cached(1, "com.google", "oauth2:play", "old"),
            Cached(0, "other.account", "oauth2:play", "old"),
            Cached(0, "com.google", "oauth2:play", "valid"),
        )
        cache.removeAll {
            AccountManagerCompat.matchesTokenInvalidation(it.user, it.type, it.token, 0, "com.google", "old")
        }
        assertEquals(listOf(
            Cached(1, "com.google", "oauth2:play", "old"),
            Cached(0, "other.account", "oauth2:play", "old"),
            Cached(0, "com.google", "oauth2:play", "valid"),
        ), cache)
    }

    @Test fun absentCredentialCannotClearCache() {
        val tokens = mutableMapOf("play" to "valid")
        assertFalse(AccountManagerCompat.removeStoredToken(tokens, null))
        assertFalse(AccountManagerCompat.matchesTokenInvalidation(0, "com.google", "valid", 0, "com.google", null))
        assertEquals(mapOf("play" to "valid"), tokens)
    }
}
