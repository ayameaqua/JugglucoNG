package tk.glucodata.healthinsights

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class DriveClientTest {
    data class Request(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray?)
    private val session = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=test"
    private fun complete(file: File) = DriveResponse(201, JSONObject().put("id", "file-1").put("name", "snapshot.zip").put("size", file.length().toString())
        .put("md5Checksum", MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") { "%02x".format(it) }).toString())
    private suspend fun fixture(size: Int, block: suspend (File) -> Unit) {
        val dir = Files.createTempDirectory("drive-client").toFile()
        try { val file = File(dir, "snapshot.zip").apply { writeBytes(ByteArray(size) { (it % 251).toByte() }) }; block(file) }
        finally { dir.deleteRecursively() }
    }
    @Test fun resumableUploadKeepsEveryByteWithOneReservedIdAndVerifiedChecksum() = runBlocking { fixture(1_100_001) { file ->
        val requests = mutableListOf<Request>()
        val client = DriveClient("ephemeral", DriveTransport { method, url, token, headers, body ->
            assertEquals("ephemeral", token); requests += Request(method, url, headers, body)
            when {
                url.contains("generateIds") -> DriveResponse(200, "{\"ids\":[\"file-1\"]}")
                method == "POST" -> { assertEquals("file-1", JSONObject(body!!.toString(Charsets.UTF_8)).getString("id")); DriveResponse(200, headers = mapOf("location" to session)) }
                headers["Content-Range"]!!.startsWith("bytes 0-") -> DriveResponse(308, headers = mapOf("Range" to "bytes=0-1048575"))
                else -> complete(file)
            }
        }, {})
        val result = client.upload(file, "snapshot.zip", "application/zip", "private-folder")
        assertEquals("file-1", result.id)
        assertEquals(1, requests.count { it.url.contains("generateIds") })
        val puts = requests.filter { it.method == "PUT" }
        assertEquals(listOf("bytes 0-1048575/1100001", "bytes 1048576-1100000/1100001"), puts.map { it.headers["Content-Range"] })
        assertArrayEquals(file.readBytes(), puts.flatMap { it.body!!.toList() }.toByteArray())
    } }
    @Test fun lostCreateResponseUsesSameIdAndConflictChecksCompletedContent() = runBlocking { fixture(20) { file ->
        var creates = 0; var ids = 0
        val client = DriveClient("token", DriveTransport { method, url, _, _, body -> when {
            url.contains("generateIds") -> { ids++; DriveResponse(200, "{\"ids\":[\"file-1\"]}") }
            method == "POST" -> { assertEquals("file-1", JSONObject(body!!.toString(Charsets.UTF_8)).getString("id")); if (++creates == 1) throw IOException("lost reply") else DriveResponse(409) }
            else -> complete(file)
        } }, {})
        assertEquals("file-1", client.upload(file, "snapshot.zip", "application/zip", "folder").id)
        assertEquals(1, ids); assertEquals(2, creates)
    } }
    @Test fun interruptedChunkQueriesCommittedOffsetBeforeResending() = runBlocking { fixture(400_001) { file ->
        val ranges = mutableListOf<String>(); var interrupted = false
        val client = DriveClient("token", DriveTransport { method, url, _, headers, _ -> when {
            url.contains("generateIds") -> DriveResponse(200, "{\"ids\":[\"file-1\"]}")
            method == "POST" -> DriveResponse(200, headers = mapOf("Location" to session))
            else -> {
                val range = headers.getValue("Content-Range"); ranges += range
                when { !interrupted -> { interrupted = true; throw IOException("network") }
                    range.startsWith("bytes */") -> DriveResponse(308, headers = mapOf("Range" to "bytes=0-262143"))
                    else -> complete(file) }
            }
        } }, {})
        client.upload(file, "snapshot.zip", "application/zip", "folder")
        assertEquals(listOf("bytes 0-400000/400001", "bytes */400001", "bytes 262144-400000/400001"), ranges)
    } }
    @Test fun expiredSessionRestartsUsingTheSameReservedFileId() = runBlocking { fixture(11) { file ->
        var ids = 0; var starts = 0; var chunks = 0
        val client = DriveClient("token", DriveTransport { method, url, _, _, body -> when {
            url.contains("generateIds") -> { ids++; DriveResponse(200, "{\"ids\":[\"file-1\"]}") }
            method == "POST" -> { starts++; assertEquals("file-1", JSONObject(body!!.toString(Charsets.UTF_8)).getString("id")); DriveResponse(200, headers = mapOf("Location" to session)) }
            else -> if (++chunks == 1) DriveResponse(404) else complete(file)
        } }, {})
        client.upload(file, "snapshot.zip", "application/zip", "folder")
        assertEquals(1, ids); assertEquals(2, starts)
    } }
    @Test fun wrongChecksumNeverReturnsUploadSuccess() = runBlocking { fixture(11) { file ->
        val client = DriveClient("token", DriveTransport { method, url, _, _, _ -> when {
            url.contains("generateIds") -> DriveResponse(200, "{\"ids\":[\"file-1\"]}")
            method == "POST" -> DriveResponse(200, headers = mapOf("Location" to session))
            else -> DriveResponse(201, "{\"id\":\"file-1\",\"name\":\"snapshot.zip\",\"size\":11,\"md5Checksum\":\"wrong\"}")
        } }, {})
        assertTrue(runCatching { client.upload(file, "snapshot.zip", "application/zip", "folder") }.isFailure)
    } }
    @Test fun authorizationAndQuotaFailuresAreNotRetriedOrHidden() = runBlocking {
        for (status in listOf(401, 403)) {
            var requests = 0
            val client = DriveClient("token", DriveTransport { _, _, _, _, _ -> requests++; DriveResponse(status, "{\"error\":{\"errors\":[{\"reason\":\"storageQuotaExceeded\"}]}}") }, {})
            val failure = runCatching { client.account() }.exceptionOrNull()
            assertTrue(failure is DriveFailure); assertEquals(status, (failure as DriveFailure).status); assertEquals(1, requests)
        }
    }
    @Test fun retriesAreBoundedAndNoProgressCannotLoopForever() = runBlocking { fixture(11) { file ->
        var chunks = 0
        val client = DriveClient("token", DriveTransport { method, url, _, _, _ -> when {
            url.contains("generateIds") -> DriveResponse(200, "{\"ids\":[\"file-1\"]}")
            method == "POST" -> DriveResponse(200, headers = mapOf("Location" to session))
            else -> { chunks++; DriveResponse(308) }
        } }, {})
        assertTrue(runCatching { client.upload(file, "snapshot.zip", "application/zip", "folder") }.isFailure)
        assertEquals(4, chunks)
    } }
    @Test fun uploadLocationCannotExfiltrateBearerToken() = runBlocking { fixture(11) { file ->
        var puts = 0
        val client = DriveClient("token", DriveTransport { method, url, _, _, _ -> when {
            url.contains("generateIds") -> DriveResponse(200, "{\"ids\":[\"file-1\"]}")
            method == "POST" -> DriveResponse(200, headers = mapOf("Location" to "https://example.com/steal"))
            else -> { puts++; error("must not send") }
        } }, {})
        assertTrue(runCatching { client.upload(file, "snapshot.zip", "application/zip", "folder") }.isFailure)
        assertEquals(0, puts)
        assertTrue(runCatching { DriveHttp.validateUrl("http://www.googleapis.com/upload") }.isFailure)
        assertTrue(runCatching { DriveHttp.validateUrl("https://www.googleapis.com.evil.test/upload") }.isFailure)
        assertTrue(runCatching { DriveHttp.validateUrl("https://user@www.googleapis.com/upload") }.isFailure)
    } }
    @Test fun rootFolderIgnoresPreviouslySharedFoldersAndCreatesOnlyNormalPrivateDriveDirectory() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = DriveClient("token", DriveTransport { method, url, _, headers, body ->
            requests += Request(method, url, headers, body)
            when {
                url.contains("generateIds") -> DriveResponse(200, "{\"ids\":[\"private-root\"]}")
                method == "GET" -> DriveResponse(200, "{\"files\":[{\"id\":\"shared-root\",\"shared\":true}]}")
                else -> DriveResponse(200, "{\"id\":\"private-root\"}")
            }
        }, {})
        assertEquals("private-root", client.rootFolder())
        val metadata = JSONObject(requests.single { it.method == "POST" }.body!!.toString(Charsets.UTF_8))
        assertEquals("root", metadata.getJSONArray("parents").getString(0))
        assertEquals("application/vnd.google-apps.folder", metadata.getString("mimeType"))
        assertTrue(requests.none { it.url.contains("permissions") || it.url.contains("appDataFolder") })
    }
    @Test fun existingPrivateFolderIsReusedWithoutCreatingAnotherOne() = runBlocking {
        var calls = 0
        val client = DriveClient("token", DriveTransport { _, _, _, _, _ -> calls++; DriveResponse(200, "{\"files\":[{\"id\":\"private-root\",\"shared\":false}]}") }, {})
        assertEquals("private-root", client.rootFolder()); assertEquals(1, calls)
    }
}
