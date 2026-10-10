package tk.glucodata.healthinsights

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class DriveExportBundleTest {
    private fun zip(folder: File, entries: Map<String, String>): File = File(folder, "snapshot.zip").apply {
        ZipOutputStream(outputStream()).use { zip -> entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() } }
    }
    @Test fun textPartsPreserveEveryUnicodeRecordWithChecksumsAndOrderedIndex() = runBlocking {
        val folder = Files.createTempDirectory("drive-bundle").toFile()
        try {
            val lines = (0..24).map { "{\"id\":$it,\"note\":\"睡眠＋运动 🏃\",\"timestamp\":1791255600123}" }
            val archive = zip(folder, mapOf("glucose.jsonl" to lines.joinToString("\n", postfix = "\n"), "series.jsonl" to "", "manifest.json" to "{\"scope\":\"CGM_ONLY\"}"))
            DriveExportBundle.prepare(archive, folder, 160).use { bundle ->
                val parts = bundle.parts.filter { it.original == "glucose.jsonl" }
                assertTrue(parts.size > 1)
                assertEquals(lines, parts.flatMap { it.file.readLines() })
                assertEquals(25L, parts.sumOf { it.records ?: 0 })
                assertTrue(parts.all { it.file.length() <= 160 })
                assertTrue(bundle.parts.all { it.file.name.endsWith(".txt") && it.sha256 == DriveExportBundle.sha256(it.file) })
                assertEquals(0L, bundle.parts.single { it.original == "series.jsonl" }.records)
                val remotes = (0..bundle.parts.size).map { DriveFile("f$it", "file$it", 0, "") }
                val index = bundle.index(remotes).readText()
                assertTrue(index.contains("snapshot") || index.contains("ZIP"))
                assertTrue(index.contains("glucose.jsonl.part-0001.txt"))
                assertTrue(index.contains(DriveExportBundle.sha256(archive)))
                assertTrue(index.contains("https://drive.google.com/file/d/f0/view"))
                assertTrue(runCatching { bundle.index(remotes.dropLast(1)) }.isFailure)
            }
            assertEquals(listOf("snapshot.zip"), folder.list()?.toList())
        } finally { folder.deleteRecursively() }
    }
    @Test fun zipPathsCannotEscapeOwnedCacheFolderAndFailureCleansTemporaryFiles() = runBlocking {
        val folder = Files.createTempDirectory("drive-unsafe").toFile()
        try {
            val archive = zip(folder, mapOf("../outside.txt" to "no"))
            assertTrue(runCatching { DriveExportBundle.prepare(archive, folder) }.isFailure)
            assertEquals(listOf("snapshot.zip"), folder.list()?.toList())
        } finally { folder.deleteRecursively() }
    }
    private class Cloud(private val failText: Boolean = false) : DriveTransport {
        var next = 0
        val folders = mutableMapOf<String, String>()
        val pending = mutableMapOf<String, JSONObject>()
        val completed = mutableListOf<String>()
        override suspend fun execute(method: String, url: String, token: String, headers: Map<String, String>, body: ByteArray?): DriveResponse = when {
            url.contains("generateIds") -> DriveResponse(200, "{\"ids\":[\"id-${++next}\"]}")
            method == "GET" && url.contains("files?q=") -> DriveResponse(200, "{\"files\":[]}")
            method == "POST" && url.contains("uploadType=resumable") -> {
                val metadata = JSONObject(body!!.toString(Charsets.UTF_8))
                if (failText && metadata.getString("name").endsWith(".txt")) DriveResponse(401)
                else { val id = metadata.getString("id"); pending[id] = metadata; DriveResponse(200, headers = mapOf("Location" to "https://www.googleapis.com/upload/drive/v3/files?session=$id")) }
            }
            method == "PUT" -> {
                val id = url.substringAfter("session=")
                val metadata = pending.getValue(id)
                completed += metadata.getString("name")
                DriveResponse(201, JSONObject().put("id", id).put("name", metadata.getString("name")).put("size", body!!.size.toString())
                    .put("md5Checksum", MessageDigest.getInstance("MD5").digest(body).joinToString("") { "%02x".format(it) }).toString())
            }
            method == "PATCH" -> {
                val id = url.substringAfter("/files/").substringBefore('?')
                folders[id] = JSONObject(body!!.toString(Charsets.UTF_8)).getString("name")
                DriveResponse(200, "{\"id\":\"$id\"}")
            }
            method == "POST" -> {
                val json = JSONObject(body!!.toString(Charsets.UTF_8)); folders[json.getString("id")] = json.getString("name")
                DriveResponse(200, "{\"id\":\"${json.getString("id")}\"}")
            }
            else -> error("unexpected request $method")
        }
    }
    @Test fun uploaderWritesIndexAndRenamesSnapshotOnlyAfterAllDataPassesVerification() = runBlocking {
        val folder = Files.createTempDirectory("drive-whole-upload").toFile()
        try {
            val archive = zip(folder, mapOf("glucose.jsonl" to "{\"id\":1}\n", "manifest.json" to "{}"))
            DriveExportBundle.prepare(archive, folder).use { bundle ->
                val cloud = Cloud()
                var created: String? = null
                val link = DriveExportUploader(DriveClient("memory-only", cloud, {})).upload(bundle, {}, { created = it })
                assertEquals(created, link)
                assertEquals("START_HERE.txt", cloud.completed.last())
                assertEquals(bundle.parts.size + 2, cloud.completed.size)
                assertEquals(1, cloud.folders.values.count { it == "snapshot" })
                assertTrue(cloud.folders.values.none { it.startsWith("未完成-") })
            }
        } finally { folder.deleteRecursively() }
    }
    @Test fun partialUploadDoesNotWriteCompletionIndexOrRenameIncompleteDirectory() = runBlocking {
        val folder = Files.createTempDirectory("drive-partial-upload").toFile()
        try {
            val archive = zip(folder, mapOf("glucose.jsonl" to "{\"id\":1}\n", "manifest.json" to "{}"))
            DriveExportBundle.prepare(archive, folder).use { bundle ->
                val cloud = Cloud(failText = true)
                var created: String? = null
                val result = runCatching { DriveExportUploader(DriveClient("token", cloud, {})).upload(bundle, {}, { created = it }) }
                assertTrue(result.exceptionOrNull() is DriveFailure)
                assertNotNull(created)
                assertEquals(listOf("snapshot.zip"), cloud.completed)
                assertEquals(1, cloud.folders.values.count { it.startsWith("未完成-") })
            }
        } finally { folder.deleteRecursively() }
    }
}
