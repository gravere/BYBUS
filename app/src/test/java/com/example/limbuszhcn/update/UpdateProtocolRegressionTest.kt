package com.example.limbuszhcn.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class UpdateProtocolRegressionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val release = "https://github.com/example/project/releases/latest"
    private val asset = "https://github.com/example/project/releases/download/200/LimbusLocalize_200.7z"
    private fun transport(body: String) = object : HttpTransport {
        override fun execute(request: HttpRequest) = HttpResponse(200, -1, body.byteInputStream())
    }

    @Test fun releaseFieldsCanBeReorderedAndDigestCanBeNull() {
        val client = GitHubTranslationReleaseClient(release, transport("""{
            "assets":[{"browser_download_url":"$asset","digest":null,"name":"LimbusLocalize_200.7z"}],
            "tag_name":"200"}
        """))
        assertEquals(GitHubTranslationRelease(200, asset, null), client.fetchRelease())
    }

    @Test fun unrelatedArchivesAndAmbiguousAssetsAreRejected() {
        val wrong = """{"tag_name":"200","assets":[{"name":"other.7z","browser_download_url":"$asset"}]}"""
        assertThrows(TranslationProtocolException::class.java) { GitHubTranslationReleaseClient(release, transport(wrong)).fetchRelease() }
        val item = """{"name":"LimbusLocalize_200.7z","browser_download_url":"$asset"}"""
        assertThrows(TranslationProtocolException::class.java) {
            GitHubTranslationReleaseClient(release, transport("""{"tag_name":"200","assets":[$item,$item]}""")).fetchRelease()
        }
    }

    @Test fun everyFailedDownloadResponseIsClosed() {
        val opened = AtomicInteger(); val closed = AtomicInteger()
        val transport = object : HttpTransport {
            override fun execute(request: HttpRequest): HttpResponse {
                opened.incrementAndGet()
                return HttpResponse(503, 0, object : ByteArrayInputStream(byteArrayOf()) {
                    override fun close() { closed.incrementAndGet(); super.close() }
                })
            }
        }
        assertThrows(TranslationHttpException::class.java) {
            TranslationPackageDownloader(transport, 3).download(DownloadRequest(asset, "200", null, temporary.root.toPath()))
        }
        assertEquals(3, opened.get()); assertEquals(3, closed.get())
    }

    @Test fun oversizedApiBodyIsClosedAndRejected() {
        var closed = false
        val response = HttpResponse(200, -1, object : ByteArrayInputStream(ByteArray(100)) {
            override fun close() { closed = true; super.close() }
        })
        assertThrows(TranslationProtocolException::class.java) { response.bodyText(10) }
        assertTrue(closed)
    }

    @Test fun exhaustedNetworkBudgetDoesNotMakeAnotherRequest() {
        var calls = 0
        val fake = object : HttpTransport {
            override fun execute(request: HttpRequest): HttpResponse { calls++; throw IOException("unexpected") }
        }
        assertThrows(java.io.InterruptedIOException::class.java) {
            BudgetHttpTransport(fake, 0).execute(HttpRequest(asset, "GET"))
        }
        assertEquals(0, calls)
    }

    @Test fun manifestParsingDoesNotDependOnPropertyOrder() {
        val manifest = PatchManifest.fromJson("""{
            "entries":[{"sha256":"hash","size":12,"targetPath":"en/a.json","cachePath":"files/a.json","sourcePath":"a.json"}],
            "extractedFileCount":1,"archiveFileCount":1,"archiveSha256":"archive","archiveVersion":null,"requestedVersion":"200"
        }""")
        assertEquals("en/a.json", manifest.entries.single().targetPath)
        assertEquals(manifest, PatchManifest.fromJson(manifest.toJson()))
    }
}
