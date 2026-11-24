package com.example.limbuszhcn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证公开诊断包写入前的敏感信息脱敏与长度限制。
 */
class DebugLogExporterTest {
    @Test fun timelinePreservesExitTimeAndProcessWithoutRawPayloadsOrMainProcessAttribution() {
        val error = "1795452524.130 9760 9761 E AppSealing: !!! Kill Process ........[70107]"
        assertEquals("time=1795452524.130 pid=9760 event=game_reported_exit code=70107", safeGameTimelineMarker(error))
        assertEquals(null, safeGameTimelineMarker("$error account=private@example.com"))
        assertEquals(null, safeGameTimelineMarker(error.replace("9760", "999999999999999")))
        val game = "com.ProjectMoon.LimbusCompany"
        val bound = "1795452524.001 9905 9905 I LimbusVA: VClient.bindApplication before makeApplication package=$game process=$game info=private@example.com"
        assertEquals("time=1795452524.001 pid=9905 event=game_process_bound role=main", safeGameTimelineMarker(bound))
        assertEquals("time=1795452524.001 pid=9905 event=game_process_bound role=helper",
            safeGameTimelineMarker(bound.replace("process=$game", "process=$game:private-helper")))
        assertEquals(null, safeGameTimelineMarker(bound.replace("package=$game", "package=other.app")))
        assertEquals(null, safeGameTimelineMarker(bound.replace("process=$game", "process=other.app")))
        assertEquals(null, safeGameTimelineMarker("1795452524.130 9760 9761 E AppSealing: private account payload"))
    }
    @Test fun engineMarkersContainNoFreeTextOrAccountPayload() {
        val prefix = "1795004706.847  8909  8958 E Unity : "
        assertEquals("time=1795004706.847 pid=8909 event=intro_complete", safeGameStartupMarker(prefix + "PlayDone"))
        assertEquals("time=1795004706.847 pid=8909 event=resource_install_failed",
            safeGameStartupMarker(prefix + "Not enough storage space to install required resources."))
        assertEquals("time=1795004706.847 pid=8909 event=addressables_runtime_data_missing",
            safeGameStartupMarker(prefix + "RuntimeData is null path=/private/account-123"))
        val error = safeGameStartupMarker(prefix + "Curl error 7 https://private.example?token=fixture")!!
        assertFalse(error.contains("private")); assertFalse(error.contains("fixture"))
        assertEquals(null, safeGameStartupMarker(prefix + "Login user private@example.com"))
        assertEquals(null, safeGameStartupMarker(prefix + "PlayDone purchase={secret receipt}"))
        assertEquals(null, safeGameStartupMarker(prefix.replace("Unity", "Billing") + "PlayDone"))
        assertEquals(null, safeGameStartupMarker("invalid time 8909 E Unity : PlayDone"))
    }
    @Test fun gameExitCodesRequireTheCurrentProcessTimeAndAnExplicitMessage() {
        val line = " 1795004706.847  8909  8958 E AppSealing: !!! Kill Process ........[50025]"
        val time = 1_795_004_706_847L
        assertEquals("50025", gameReportedExitCode(line, 8909, time - 1200))
        assertEquals(null, gameReportedExitCode(line, 8958, time))
        assertEquals(null, gameReportedExitCode(line, 8909, time + 1))
        assertEquals(null, gameReportedExitCode(line, 8909, time - 300_001))
        assertEquals(null, gameReportedExitCode(line, 8909, time - 2000, time - 1))
        assertEquals("50025", gameReportedExitCode(line, 8909, time - 2000, time))
        assertEquals(null, gameReportedExitCode(line.replace("AppSealing", "Unity"), 8909, time))
        assertEquals(null, gameReportedExitCode("$line customer data", 8909, time))
        assertEquals(null, gameReportedExitCode(line.replace("Kill Process", "request"), 8909, time))
    }

    @Test fun anEarlierPidMustBeBoundToTheMainGameAndConfirmedDeadInThisFailedLaunch() {
        val game = "com.ProjectMoon.LimbusCompany"
        val bound = "1795058032.387 6320 6320 I LimbusVA: VClient.bindApplication before makeApplication package=$game process=$game info=fixture"
        val error = "1795058032.680 6320 6320 E AppSealing: !!! Kill Process ........[50040]"
        val start = 1_795_058_028_642L
        val earlierExit = 1_795_058_033_119L
        val finalExit = 1_795_058_036_280L
        fun codes(vararg logs: String, end: Long = finalExit, oldExit: Long? = earlierExit) =
            launchGameExitCodes(logs.asSequence(), 6388, start, end) { pid ->
                assertEquals(6320, pid)
                oldExit
            }
        assertEquals(listOf("50040"), codes(bound, error))
        assertTrue(codes(error).isEmpty())
        assertTrue(codes(bound.replace("process=$game", "process=$game:helper"), error).isEmpty())
        assertTrue(codes(bound.replace(game, "another.app"), error).isEmpty())
        assertTrue(codes(bound.replace("8032.387", "8028.600"), error).isEmpty())
        assertTrue(codes(bound.replace("8032.387", "8032.700"), error).isEmpty())
        assertTrue(codes(bound, error, oldExit = earlierExit - 500).isEmpty())
        assertTrue(codes(bound, error, oldExit = null).isEmpty())
        assertTrue(codes(bound, error, oldExit = finalExit + 1).isEmpty())
        assertTrue(codes(bound, error, end = Long.MAX_VALUE).isEmpty())
        assertTrue(codes(bound, error, bound.replace(game, "another.app")).isEmpty())
        val current = error.replace("6320", "6388").replace("50040", "70034")
        assertEquals(listOf("70034"), codes(bound, error, current))
    }

    @Test fun redactsApiKeysEvenWithoutAnAuthorizationLabel() {
        val fixture = "sk-proj-" + "fixture_not_a_real_credential_12345"
        val line = "Failed value $fixture; safe message"
        assertFalse(line.redactedAndBounded(4096).contains(fixture))
        val bytes = line.toByteArray(Charsets.UTF_8)
        val redacted = bytes.redactedBinaryCopy()
        assertEquals(bytes.size, redacted.size)
        assertFalse(String(redacted, Charsets.UTF_8).contains(fixture))
        assertTrue(String(redacted, Charsets.UTF_8).contains("safe message"))
    }
    /** 验证凭据、账号标识和 JWT 形态字符串都会被替换。 */
    @Test
    fun redactsCredentialsAccountsAndJwtValues() {
        val input = """
            Authorization: Bearer live-secret
            token=token-secret&mode=fast
            url=https://user:password@example.com/path
            account=user@example.com
            jwt=abcdefghijklmnop.qrstuvwxyzABCDEF.ghijklmnopQRSTUV
            FirebaseAuth: Notifying id token listeners about user ( account-internal-id ).
        """.trimIndent()

        val result = input.redactedAndBounded(16 * 1024)

        assertFalse(result.contains("live-secret"))
        assertFalse(result.contains("token-secret"))
        assertFalse(result.contains("user:password"))
        assertFalse(result.contains("user@example.com"))
        assertFalse(result.contains("abcdefghijklmnop.qrstuvwxyzABCDEF.ghijklmnopQRSTUV"))
        assertFalse(result.contains("account-internal-id"))
        assertTrue(result.contains("<redacted>"))
        assertTrue(result.contains("<redacted-email>"))
        assertTrue(result.contains("<redacted-jwt>"))
        assertTrue(result.contains("<redacted-account>"))
    }

    /** 验证超长日志只保留更有诊断价值的最新尾部。 */
    @Test
    fun keepsRecentTailWhenTextExceedsLimit() {
        val result = ("old".repeat(1000) + "RECENT-END").redactedAndBounded(64)

        assertTrue(result.startsWith("[truncated to recent data]"))
        assertTrue(result.endsWith("RECENT-END"))
    }

    /** 验证二进制脱敏保持 protobuf 结构字节、总长度和非敏感文本不变。 */
    @Test
    fun redactsBinaryCredentialsWithoutChangingFieldBoundaries() {
        val prefix = byteArrayOf(0x82.toByte(), 0x01, 0x12, 0x20)
        val secret = "account=user@example.com token=live-secret safe=kept"
            .toByteArray(Charsets.US_ASCII)
        val suffix = byteArrayOf(0x80.toByte(), 0x01, 0x7a)
        val input = prefix + secret + suffix

        val result = input.redactedBinaryCopy()

        assertEquals(input.size, result.size)
        assertArrayEquals(prefix, result.copyOfRange(0, prefix.size))
        assertArrayEquals(suffix, result.copyOfRange(result.size - suffix.size, result.size))
        val printable = String(result, Charsets.ISO_8859_1)
        assertFalse(printable.contains("user@example.com"))
        assertFalse(printable.contains("live-secret"))
        assertTrue(printable.contains("safe=kept"))
    }
}
