package tk.glucodata.healthinsights

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Cloud text views preserve complete lines/UTF-8; the original ZIP remains the canonical package. */
internal class DriveExportBundle private constructor(val folder: File, val archive: File, val parts: List<Part>) : Closeable {
    data class Part(val file: File, val name: String, val original: String, val records: Long?, val sha256: String)
    override fun close() { folder.deleteRecursively() }
    companion object {
        private const val TEXT_BYTES = 4 * 1024 * 1024
        suspend fun prepare(archive: File, cache: File, chunkBytes: Int = TEXT_BYTES): DriveExportBundle {
            require(chunkBytes > 0)
            val folder = File(cache, "drive-export-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val parts = mutableListOf<Part>()
            try {
                ZipFile(archive).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        currentCoroutineContext().ensureActive()
                        val entry = entries.nextElement()
                        val name = entry.name
                        require(!entry.isDirectory && name.matches(Regex("[a-zA-Z0-9._-]+")) && name !in setOf(".", "..")) { "导出包文件名无效" }
                        require(name.substringAfterLast('.') in setOf("jsonl", "json", "md", "txt")) { "导出包中存在不支持的文本格式" }
                        // JSONL is kept line-by-line, schema/manifest/README is one small document.
                        var number = 0
                        var bytes = 0L
                        var records = 0L
                        var output: java.io.OutputStream? = null
                        var file: File? = null
                        fun finish() {
                            output?.close(); output = null
                            file?.let { value -> parts += Part(value, value.name, name, if (name.endsWith(".jsonl")) records else null, sha256(value)) }
                            file = null; bytes = 0; records = 0
                        }
                        try {
                            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { input ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val line = input.readLine() ?: break
                                    val encoded = (line + "\n").toByteArray(Charsets.UTF_8)
                                    require(encoded.size <= 32 * 1024 * 1024) { "单条健康记录过大，已保留原始导出包" }
                                    if (name.endsWith(".jsonl") && bytes > 0 && bytes + encoded.size > chunkBytes) finish()
                                    if (output == null) {
                                        number++
                                        file = File(folder, if (name.endsWith(".jsonl")) "$name.part-${number.toString().padStart(4, '0')}.txt" else "$name.txt")
                                        output = file!!.outputStream().buffered()
                                    }
                                    output!!.write(encoded); bytes += encoded.size
                                    if (name.endsWith(".jsonl") && line.isNotBlank()) records++
                                }
                            }
                            // Retain empty streams as empty files, rather than silently losing scope information.
                            if (output == null && number == 0) {
                                file = File(folder, "$name.txt"); output = file!!.outputStream()
                                output!!.write('\n'.code) // Blank text view = zero records; ZIP retains the original zero-byte stream.
                            }
                            finish()
                        } finally { output?.close() }
                    }
                }
                return DriveExportBundle(folder, archive, parts)
            } catch (ex: Exception) { folder.deleteRecursively(); throw ex }
        }
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
    /** Written/uploaded last: its presence means all package/data files passed size and MD5 checks. */
    fun index(uploaded: List<DriveFile>): File {
        require(uploaded.size == parts.size + 1)
        val files = JSONArray()
        parts.zip(uploaded.drop(1)).forEach { (part, remote) -> files.put(JSONObject().put("original", part.original).put("name", part.name)
            .put("records", part.records ?: JSONObject.NULL).put("sha256", part.sha256).put("drive_file_id", remote.id).put("link", remote.link)) }
        return File(folder, "START_HERE.txt").apply { writeText(
            "JugglucoNG 健康数据快照\n\n本次上传已完成，各文件均经过字节数和 MD5 校验。这个目录是一次导出快照，后续采集不会自动更新。\n" +
            "请先读取 manifest.json.txt、summary.json.txt 和 README.txt.txt，按 manifest 中的 scope/selection 确认范围和数据覆盖。全部数据仅包含本机已有的血糖、日志及三星健康缓存，不代表三星账户全部历史。\n" +
            "*.jsonl.part-XXXX.txt 是逐行 JSON 的文本副本；按原文件名和分段号读取全部分段，不要只读第一段。别名用于关联来源；UTC epoch 是对齐依据，勿平移时间或重复应用校准。\n" +
            "ZIP 是完整原始格式，文本是便于连接器读取的视图；连接器是否实际可读仍取决于它的授权、格式支持和索引状态。链接需要同一账号或已有访问权限，无公开共享。\n\n" +
            "原始 ZIP：${uploaded.first().link}\n原始 ZIP SHA-256：${sha256(archive)}\n\n文本文件清单：\n" + files.toString(2) + "\n"
        ) }
    }
}

internal class DriveExportUploader(private val client: DriveClient) {
    /** Any interruption leaves an explicitly incomplete snapshot, without modifying earlier exports. */
    suspend fun upload(bundle: DriveExportBundle, progress: (String) -> Unit, folderCreated: (String) -> Unit): String {
        val root = client.rootFolder()
        val name = bundle.archive.nameWithoutExtension
        val folder = client.createFolder(client.reserveId(), "未完成-$name", root, "snapshot-v1")
        folderCreated(DriveClient.folderLink(folder))
        val uploaded = mutableListOf<DriveFile>()
        val total = bundle.parts.size + 2
        uploaded += client.upload(bundle.archive, bundle.archive.name, "application/zip", folder) { n, size -> progress("上传 1/$total：完整压缩包 ${if (size > 0) 100 * n / size else 100}%") }
        for ((index, part) in bundle.parts.withIndex()) {
            uploaded += client.upload(part.file, part.name, "text/plain", folder) { n, size -> progress("上传 ${index + 2}/$total：${part.name} ${if (size > 0) 100 * n / size else 100}%") }
        }
        val index = bundle.index(uploaded)
        progress("上传 $total/$total：文件索引")
        client.upload(index, index.name, "text/plain", folder)
        client.renameFolder(folder, name)
        return DriveClient.folderLink(folder)
    }
}
