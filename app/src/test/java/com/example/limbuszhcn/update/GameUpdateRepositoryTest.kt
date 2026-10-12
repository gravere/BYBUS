package com.example.limbuszhcn.update

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class GameUpdateRepositoryTest {
    private fun listing(version: String = "1.115.0", pkg: String = PlayListing.PACKAGE): String {
        val record = JSONArray()
        record.put(77, JSONArray().put(pkg))
        record.put(140, JSONArray().put(JSONArray().put(JSONArray().put(version))))
        val root = JSONArray().put(org.json.JSONObject.NULL).put(
            JSONArray().put(org.json.JSONObject.NULL).put(org.json.JSONObject.NULL).put(record))
        return """<script>AF_initDataCallback({key: 'ds:5', hash: '4', data:$root, sideChannel: {}});</script>"""
    }

    @Test fun comparesNumericVersionsRatherThanStringsOrVersionCodes() {
        assertTrue(GameVersion.compare("1.115.0", "1.114.0")!! > 0)
        assertTrue(GameVersion.compare("1.10.0", "1.9.0")!! > 0)
        assertEquals(0, GameVersion.compare("1.10", "1.10.0"))
        assertTrue(GameVersion.compare("1.114.0", "1.115.1")!! < 0)
        for (value in listOf(null, "471", "1.2.beta", "1.9999999.0", "1.-2.0", " 1.2.0"))
            assertNull(GameVersion.compare(value, "1.2.0"))
    }

    @Test fun parsesOnlyTheVerifiedPackageRecord() {
        assertEquals("1.115.0", PlayListing.version("review version 9.999.0" + listing()))
        for (html in listOf(listing(pkg = "other.game"), listing(version = "varies"),
                listing().replace("ds:5", "ds:8"), "<html>Review: 1.115.0</html>",
                listing().replace("</script>", ""))) {
            assertTrue(runCatching { PlayListing.version(html) }.isFailure)
        }
    }

    @Test fun failuresPreserveLastVerifiedVersionButNeverClaimSuccess() {
        val cache = Files.createTempDirectory("game-update-test").resolve("version.properties")
        var now = 1_000_000L
        var fail = false
        var requests = 0
        val repo = GameUpdateRepository(cache, {
            requests++
            if (fail) throw java.io.IOException("offline")
            listing()
        }, { now }, { now })
        val success = repo.refresh()
        assertEquals("1.115.0", success.latestVersion)
        assertNull(success.issue)
        val saved = Files.readAllBytes(cache)
        assertEquals(success, repo.refresh(force = true))
        assertEquals(1, requests) // Coalesces a launch with the just-finished status request.
        now += 20_000
        fail = true
        val failure = repo.refresh(force = true)
        assertEquals(success.latestVersion, failure.latestVersion)
        assertEquals(success.checkedAt, failure.checkedAt)
        assertNotNull(failure.issue)
        assertArrayEquals(saved, Files.readAllBytes(cache))
        assertTrue(failure.availableFor("1.114.0"))
        assertFalse(failure.availableFor("1.115.0")) // Re-read after Play update.
        assertEquals(success, GameUpdateRepository(cache, clock = { now }).cached())
    }

    @Test fun malformedNetworkAndCacheAreUnknownRatherThanLatest() {
        val cache = Files.createTempDirectory("game-update-test").resolve("version.properties")
        Files.write(cache, "schema=1\npackage=${PlayListing.PACKAGE}\nversion=1.115.0\ncheckedAt=9999999999".toByteArray())
        val repo = GameUpdateRepository(cache, { "invalid" }, { 1000L })
        assertNull(repo.cached().latestVersion)
        val result = repo.refresh()
        assertNotNull(result.issue)
        assertNull(result.latestVersion)
        assertFalse(result.availableFor("1.114.0"))
    }

    @Test fun cancellationIsNotSilentlyReportedAsNetworkFailure() {
        val cache = Files.createTempDirectory("game-update-test").resolve("version.properties")
        val repo = GameUpdateRepository(cache, { throw java.util.concurrent.CancellationException() })
        assertTrue(runCatching { repo.refresh() }.exceptionOrNull() is java.util.concurrent.CancellationException)
    }

    private fun uptodown(version: String = "1.115.0", pkg: String = PlayListing.PACKAGE) = """
        <th>Package Name</th><td>$pkg</td><script type="application/ld+json">
        {"@type":"WebPage","url":"${GameUpdateSource.Uptodown.page}","mainEntity":{
        "@type":["MobileApplication","VideoGame"],"name":"Limbus Company","url":"${GameUpdateSource.Uptodown.page}",
        "operatingSystem":"Android","softwareVersion":"$version","author":{"name":"Project Moon"}}}</script>
    """.trimIndent()

    @Test fun uptodownRequiresCurrentPackageEntityAndRejectsOtherGamesAndAmbiguity() {
        assertEquals("1.115.0", UptodownListing.version("review 99.0.0" + uptodown()))
        for (html in listOf(uptodown(pkg = "other.game"), uptodown("latest"), uptodown() + uptodown(),
                uptodown().replace("Project Moon", "Someone Else"), uptodown().replace("Android", "Windows"),
                uptodown().replace("MobileApplication", "Review"))) {
            assertTrue(runCatching { UptodownListing.version(html) }.isFailure)
        }
    }

    @Test fun channelsKeepIndependentVersionsTimesAndStaleFailures() {
        val root = Files.createTempDirectory("game-channels")
        var now = 2_000_000L
        val play = GameUpdateRepository(root.resolve("play"), { listing("1.115.0") }, { now }, { now })
        val up = GameUpdateRepository(root.resolve("up"), { uptodown("1.114.0") }, { now }, { now }, GameUpdateSource.Uptodown)
        val catalog = GameUpdateCatalog(mapOf(GameUpdateSource.GooglePlay to play.refresh(), GameUpdateSource.Uptodown to up.refresh()))
        assertEquals(setOf(GameUpdateSource.GooglePlay), catalog.newerSources("1.114.0"))
        assertEquals("已检查", catalog.label)
        val wrong = GameUpdateRepository(root.resolve("play"), source = GameUpdateSource.Uptodown, clock = { now })
        assertNull(wrong.cached().latestVersion)
        now += 16 * 60_000
        val old = GameUpdateRepository(root.resolve("up"), source = GameUpdateSource.Uptodown, clock = { now }).cached()
        assertEquals("1.114.0", old.latestVersion)
        assertNotNull(old.issue)
        assertEquals(2_000_000L, old.checkedAt)
    }

    @Test fun installerAttributionDoesNotInventGooglePlayForUnknownOrLocalInstalls() {
        assertEquals("来源未知", GameStores.describe(null, null).label)
        assertEquals("本地安装", GameStores.describe("com.android.shell", "Shell").label)
        assertFalse(GameStores.describe("com.example.limbuszhcn", "BYBUS").canOpen)
        assertEquals("Uptodown", GameStores.describe("com.uptodown", null).label)
        assertEquals("其他安装来源", GameStores.describe("new.store", null).label)
        assertEquals("New Store", GameStores.describe("new.store", "New Store").label)
    }
}
