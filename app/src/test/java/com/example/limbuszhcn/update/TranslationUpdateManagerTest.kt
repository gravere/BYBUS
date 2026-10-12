package com.example.limbuszhcn.update

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.io.path.exists

/**
 * 验证 GitHub Releases 汉化更新、缓存复用和补丁清单解析。
 */
class TranslationUpdateManagerTest {
    @Test
    fun checksLatestReleaseOnEveryLaunchAndKeepsVerifiedFilesAfterNetworkFailure() {
        val root = Files.createTempDirectory("limbus-persistent-update")
        val archiveBytes = createArchiveBytes(mapOf(
            "LimbusCompany_Data/Lang/LLC_zh-CN/MainUIText.json" to """{"dataList":[{"id":"1","content":"罪人"}]}""",
            "LimbusCompany_Data/Lang/LLC_zh-CN/Info/version.json" to """{"version":2026092102}"""
        ))
        val hash = archiveBytes.sha256()
        val api = "https://api.github.com/repos/LocalizeLimbusCompany/LocalizeLimbusCompany/releases/latest"
        val asset = "https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany/releases/download/2026092102/LimbusLocalize_2026092102.7z"
        var checks = 0
        var downloads = 0
        var offline = false
        val transport = object : HttpTransport {
            override fun execute(request: HttpRequest): HttpResponse {
                if (request.url == api) {
                    checks++
                    assertEquals("no-cache", request.headers["Cache-Control"])
                    if (offline) throw java.io.IOException("offline")
                    return HttpResponse(200, -1, """{
                        "tag_name":"2026092102",
                        "assets":[{
                            "url":"https://api.github.com/repos/LocalizeLimbusCompany/LocalizeLimbusCompany/releases/assets/1",
                            "name":"LimbusLocalize_2026092102.7z",
                            "digest":"sha256:$hash",
                            "browser_download_url":"$asset"
                        }]
                    }""".byteInput())
                }
                assertEquals(asset, request.url)
                downloads++
                return HttpResponse(200, archiveBytes.size.toLong(), ByteArrayInputStream(archiveBytes))
            }
        }
        val request = InstallGitHubReleaseRequest(api, root.resolve("downloads"), root.resolve("patch-cache"))
        val first = TranslationUpdateManager(transport).installFromGitHub(request)
        val second = TranslationUpdateManager(transport).installFromGitHub(request)
        assertEquals(first.manifest, second.manifest)
        assertTrue(second.fromCache)
        assertEquals(2, checks)
        assertEquals(1, downloads)

        // The optimized path still verifies and repairs extracted files and the archive itself.
        val extracted = second.outputDirectory.resolve("files/en/EN_MainUIText.json")
        Files.write(extracted, "damaged cache".toByteArray())
        val repairedFiles = TranslationUpdateManager(transport).installFromGitHub(request)
        assertEquals(first.manifest, repairedFiles.manifest)
        assertEquals(1, downloads)
        Files.write(root.resolve("downloads/LimbusLocalize_2026092102.7z"), byteArrayOf(0))
        val repairedArchive = TranslationUpdateManager(transport).installFromGitHub(request)
        assertEquals(first.manifest, repairedArchive.manifest)
        assertEquals(2, downloads)

        offline = true
        assertThrows(java.io.IOException::class.java) {
            TranslationUpdateManager(transport).installFromGitHub(request)
        }
        assertEquals(5, checks)
        assertEquals(hash, root.resolve("downloads/LimbusLocalize_2026092102.7z").sha256())
        assertTrue(second.outputDirectory.resolve("files/en/EN_MainUIText.json").exists())
    }

    /** 验证从 GitHub API 获取、下载并解压最新汉化包的完整流程。 */
    @Test
    fun installsLatestPackageFromGitHub() {
        val workDir = Files.createTempDirectory("limbus-update-test")
        val archiveBytes = createArchiveBytes(
            mapOf(
                "LimbusCompany_Data/Lang/LLC_zh-CN/MainUIText.json" to """
                    {"dataList":[{"id":"mainui_main_button1","content":"罪人"}]}
                """.trimIndent(),
                "LimbusCompany_Data/Lang/LLC_zh-CN/Info/version.json" to """
                    {"version":2026050702,"notice":""}
                """.trimIndent()
            )
        )
        val archiveHash = archiveBytes.sha256()
        val transport = FakeTransport(
            mapOf(
                "/repos/LocalizeLimbusCompany/LocalizeLimbusCompany/releases" to HttpResponse(
                    statusCode = 200,
                    contentLength = -1,
                    body = """
                        [{
                          "tag_name":"2026050702",
                          "assets":[{
                            "url":"https://api.github.com/repos/LocalizeLimbusCompany/LocalizeLimbusCompany/releases/assets/1",
                            "name":"LimbusLocalize_2026050702.7z",
                            "digest":"sha256:$archiveHash",
                            "browser_download_url":"https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany/releases/download/2026050702/LimbusLocalize_2026050702.7z"
                          }]
                        }]
                    """.trimIndent().byteInput()
                ),
                "/LocalizeLimbusCompany/LocalizeLimbusCompany/releases/download/2026050702/LimbusLocalize_2026050702.7z" to HttpResponse(
                    statusCode = 200,
                    contentLength = archiveBytes.size.toLong(),
                    body = ByteArrayInputStream(archiveBytes)
                )
            )
        )
        val manager = TranslationUpdateManager(transport = transport)

        val result = manager.installFromGitHub(
            InstallGitHubReleaseRequest(
                releaseUrl = "https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany/releases",
                downloadCacheRoot = workDir.resolve("downloads"),
                patchCacheRoot = workDir.resolve("patch-cache")
            )
        )

        assertEquals(2026050702L, result.manifest.archiveVersion)
        assertEquals(1, result.manifest.extractedFileCount)
        assertTrue(result.outputDirectory.resolve("files/en/EN_MainUIText.json").exists())
        assertEquals(
            listOf(
                "/repos/LocalizeLimbusCompany/LocalizeLimbusCompany/releases",
                "/LocalizeLimbusCompany/LocalizeLimbusCompany/releases/download/2026050702/LimbusLocalize_2026050702.7z"
            ),
            transport.paths
        )
    }

    /** 验证 SHA-256 匹配时不再发起 GitHub 归档下载请求。 */
    @Test
    fun reusesCachedPackageWhenHashMatches() {
        val workDir = Files.createTempDirectory("limbus-update-test")
        val archiveBytes = createArchiveBytes(
            mapOf(
                "LimbusCompany_Data/Lang/LLC_zh-CN/MainUIText.json" to "{}",
                "LimbusCompany_Data/Lang/LLC_zh-CN/Info/version.json" to """{"version":1}"""
            )
        )
        val downloadDir = workDir.resolve("downloads")
        downloadDir.toFile().mkdirs()
        val cached = downloadDir.resolve("LimbusLocalize_1.7z")
        Files.write(cached, archiveBytes)
        val transport = FakeTransport(emptyMap())

        val result = TranslationPackageDownloader(transport).download(
            DownloadRequest(
                url = "https://github.com/example/project/releases/download/1/LimbusLocalize_1.7z",
                version = "1",
                expectedSha256 = archiveBytes.sha256(),
                destinationDirectory = downloadDir
            )
        )

        assertTrue(result.fromCache)
        assertEquals(cached, result.file)
        assertTrue(transport.paths.isEmpty())
    }

    /** 验证 GitHub Releases 列表的资产选择、摘要解析和公开下载。 */
    @Test
    fun fetchesLatestPackageFromGitHubReleasesList() {
        val archiveBytes = createArchiveBytes(
            mapOf(
                "LimbusCompany_Data/Lang/LLC_zh-CN/MainUIText.json" to """
                    {"dataList":[{"id":"mainui_main_button1","content":"罪人"}]}
                """.trimIndent(),
                "LimbusCompany_Data/Lang/LLC_zh-CN/Info/version.json" to """
                    {"version":2026071001,"notice":""}
                """.trimIndent()
            )
        )
        val archiveHash = archiveBytes.sha256()
        val transport = FakeTransport(
            mapOf(
                "/repos/LocalizeLimbusCompany/LocalizeLimbusCompany/releases" to HttpResponse(
                    statusCode = 200,
                    contentLength = -1,
                    body = """
                        [{
                          "tag_name":"2026071001",
                          "assets":[{
                            "url":"https://api.github.com/repos/LocalizeLimbusCompany/LocalizeLimbusCompany/releases/assets/1",
                            "name":"LimbusLocalize_2026071001.7z",
                            "digest":"sha256:$archiveHash",
                            "browser_download_url":"https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany/releases/download/2026071001/LimbusLocalize_2026071001.7z"
                          }]
                        }]
                    """.trimIndent().byteInput()
                ),
                "/LocalizeLimbusCompany/LocalizeLimbusCompany/releases/download/2026071001/LimbusLocalize_2026071001.7z" to HttpResponse(
                    statusCode = 200,
                    contentLength = archiveBytes.size.toLong(),
                    body = ByteArrayInputStream(archiveBytes)
                )
            )
        )
        val release = GitHubTranslationReleaseClient(
            releaseUrl = "https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany/releases",
            transport = transport
        ).fetchRelease()

        val archive = TranslationPackageDownloader(transport).download(
            DownloadRequest(
                url = release.assetUrl,
                version = release.version.toString(),
                expectedSha256 = release.sha256,
                destinationDirectory = Files.createTempDirectory("limbus-github-download")
            )
        )

        assertEquals(2026071001L, release.version)
        assertEquals(archiveHash, release.sha256)
        assertTrue(archive.file.exists())
        assertEquals(
            listOf(
                "/repos/LocalizeLimbusCompany/LocalizeLimbusCompany/releases",
                "/LocalizeLimbusCompany/LocalizeLimbusCompany/releases/download/2026071001/LimbusLocalize_2026071001.7z"
            ),
            transport.paths
        )
    }

    /** 验证非 GitHub 域名会在任何网络请求发生前被拒绝。 */
    @Test
    fun rejectsNonGitHubReleaseSource() {
        val transport = FakeTransport(emptyMap())

        assertThrows(TranslationProtocolException::class.java) {
            GitHubTranslationReleaseClient(
                releaseUrl = "https://downloads.example/releases",
                transport = transport
            ).fetchRelease()
        }

        assertTrue(transport.paths.isEmpty())
    }

    /** 验证解压后生成的补丁清单可以无损序列化和恢复。 */
    @Test
    fun parsesGeneratedPatchManifest() {
        val manifest = PatchManifest(
            requestedVersion = "2026050702",
            archiveVersion = 2026050702,
            archiveSha256 = "archive",
            archiveFileCount = 2,
            extractedFileCount = 1,
            entries = listOf(
                PatchFileEntry(
                    sourcePath = "LimbusCompany_Data/Lang/LLC_zh-CN/MainUIText.json",
                    cachePath = "files/en/EN_MainUIText.json",
                    targetPath = "en/EN_MainUIText.json",
                    size = 42,
                    sha256 = "file"
                )
            )
        )

        val parsed = PatchManifest.fromJson(manifest.toJson())

        assertEquals(manifest, parsed)
    }

    private fun createArchiveBytes(entries: Map<String, String>): ByteArray {
        val temp = Files.createTempFile("limbus-package", ".7z")
        SevenZOutputFile(temp.toFile()).use { archive ->
            entries.forEach { (name, content) ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                val entry = SevenZArchiveEntry().apply {
                    this.name = name
                    this.size = bytes.size.toLong()
                }
                archive.putArchiveEntry(entry)
                archive.write(bytes)
                archive.closeArchiveEntry()
            }
        }
        return Files.readAllBytes(temp)
    }

    private fun String.byteInput(): ByteArrayInputStream =
        ByteArrayInputStream(toByteArray(Charsets.UTF_8))

    private fun ByteArray.sha256(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(this).joinToString("") { "%02x".format(it) }
    }
}

private class FakeTransport(
    private val responses: Map<String, HttpResponse>
) : HttpTransport {
    val paths = mutableListOf<String>()

    override fun execute(request: HttpRequest): HttpResponse {
        val path = java.net.URI(request.url).path
        paths += path
        return responses[path] ?: error("Unexpected request: ${request.method} ${request.url}")
    }
}
