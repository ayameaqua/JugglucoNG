package tk.glucodata.healthinsights

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.security.MessageDigest

internal class DriveFailure(val status: Int, val reason: String = "") : IOException(when {
    status == 401 -> "Google 授权已过期或被撤销，请重新连接账号后上传"
    status == 403 && reason == "storageQuotaExceeded" -> "Google Drive 空间不足"
    status == 403 && reason in setOf("accessNotConfigured", "SERVICE_DISABLED") -> "请先在 Google Cloud 启用 Drive API"
    status == 403 -> "Google Drive 未允许此操作，请检查授权和 Cloud 项目配置"
    status == 404 -> "云盘文件或上传会话已失效，请重新上传"
    status == 429 -> "Google Drive 请求过于频繁，请稍后上传"
    else -> "Google Drive 请求失败（HTTP $status）"
})

internal data class DriveAccount(val id: String, val email: String, val name: String)
internal data class DriveFile(val id: String, val name: String, val size: Long, val md5: String) {
    val link get() = "https://drive.google.com/file/d/$id/view"
}

/** Drive v3 protocol only. Preallocated IDs and resumable offsets make retries idempotent. */
internal class DriveClient(
    private val token: String,
    private val transport: DriveTransport = DriveHttp,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FIELDS = "id,name,size,md5Checksum"
        private const val CHUNK = 1024 * 1024
        fun folderLink(id: String) = "https://drive.google.com/drive/folders/$id"
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun reason(response: DriveResponse) = runCatching {
            JSONObject(response.body).getJSONObject("error").optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        }.getOrDefault("")
        private fun transient(response: DriveResponse) = response.code == 429 || response.code in 500..599 ||
            (response.code == 403 && reason(response) in setOf("rateLimitExceeded", "userRateLimitExceeded"))
        private fun check(response: DriveResponse): DriveResponse {
            if (response.code !in 200..299) throw DriveFailure(response.code, reason(response))
            return response
        }
        private fun parsed(response: DriveResponse): DriveFile {
            val json = JSONObject(check(response).body)
            return DriveFile(json.getString("id"), json.getString("name"), json.optString("size").toLongOrNull() ?: 0, json.optString("md5Checksum"))
        }
    }
    private suspend fun send(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): DriveResponse {
        currentCoroutineContext().ensureActive()
        return transport.execute(method, url, token, headers, body)
    }
    /** Only use for reads or writes with an explicitly reserved ID. */
    private suspend fun retry(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): DriveResponse {
        for (attempt in 0..3) {
            val response = try { send(method, url, headers, body) } catch (ex: IOException) {
                if (attempt == 3) throw IOException("无法连接 Google Drive；请检查网络后重试")
                pause(1000L shl attempt); continue
            }
            if (!transient(response) || attempt == 3) return response
            pause(1000L shl attempt)
        }
        error("unreachable")
    }
    suspend fun account(): DriveAccount {
        val user = JSONObject(check(retry("GET", "$API/about?fields=user(permissionId,emailAddress,displayName)")).body).getJSONObject("user")
        return DriveAccount(user.getString("permissionId"), user.getString("emailAddress"), user.optString("displayName"))
    }
    suspend fun reserveId(): String = JSONObject(check(retry("GET", "$API/files/generateIds?count=1&space=drive&type=files")).body).getJSONArray("ids").getString(0)
    suspend fun createFolder(id: String, name: String, parent: String, marker: String): String {
        val metadata = JSONObject().put("id", id).put("name", name).put("mimeType", "application/vnd.google-apps.folder")
            .put("parents", JSONArray(listOf(parent))).put("appProperties", JSONObject().put("jugglucoFolder", marker))
        val response = retry("POST", "$API/files?fields=id", mapOf("Content-Type" to "application/json; charset=UTF-8"), metadata.toString().toByteArray())
        if (response.code == 409) {
            val known = JSONObject(check(retry("GET", "$API/files/${encode(id)}?fields=id,name,mimeType,trashed,shared")).body)
            check(known.getString("mimeType") == "application/vnd.google-apps.folder" && known.getString("name") == name && !known.optBoolean("trashed") && !known.optBoolean("shared")) { "云盘目录状态不匹配" }
        } else check(response)
        return id
    }
    suspend fun rootFolder(): String {
        val query = "'root' in parents and trashed=false and mimeType='application/vnd.google-apps.folder' and appProperties has { key='jugglucoFolder' and value='health-exports-v1' }"
        val rows = JSONObject(check(retry("GET", "$API/files?q=${encode(query)}&fields=files(id,shared)&pageSize=100&spaces=drive")).body).getJSONArray("files")
        // A user may have shared or moved an old folder. Never inherit its sharing for a new export.
        for (n in 0 until rows.length()) { val folder = rows.getJSONObject(n); if (!folder.optBoolean("shared")) return folder.getString("id") }
        return createFolder(reserveId(), "JugglucoNG Health", "root", "health-exports-v1")
    }
    suspend fun renameFolder(id: String, name: String) {
        check(retry("PATCH", "$API/files/${encode(id)}?fields=id", mapOf("Content-Type" to "application/json"), JSONObject().put("name", name).toString().toByteArray()))
    }
    suspend fun upload(file: File, name: String, mime: String, parent: String, progress: (Long, Long) -> Unit = { _, _ -> }): DriveFile {
        val id = reserveId()
        val length = file.length()
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        val md5 = digest.digest().joinToString("") { "%02x".format(it) }
        fun verified(response: DriveResponse): DriveFile = parsed(response).also {
            check(it.id == id && it.name == name && it.size == length && it.md5 == md5) { "上传校验失败；云盘内容未确认为完整" }
        }
        val metadata = JSONObject().put("id", id).put("name", name).put("parents", JSONArray(listOf(parent)))
        val initHeaders = mapOf("Content-Type" to "application/json; charset=UTF-8", "X-Upload-Content-Type" to mime, "X-Upload-Content-Length" to length.toString())
        // Restart expired sessions at most twice, using the same ID, never a second file.
        for (sessionAttempt in 0..2) {
            val start = retry("POST", "$UPLOAD?uploadType=resumable&fields=$FIELDS", initHeaders, metadata.toString().toByteArray())
            if (start.code == 409) return verified(retry("GET", "$API/files/${encode(id)}?fields=$FIELDS"))
            check(start)
            val session = start.header("Location") ?: throw IOException("云盘没有返回上传会话")
            DriveHttp.validateUrl(session)
            var offset = 0L
            var failures = 0
            var expired = false
            RandomAccessFile(file, "r").use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val data = ByteArray(minOf(CHUNK.toLong(), length - offset).toInt())
                    input.seek(offset); input.readFully(data)
                    val range = if (length == 0L) "bytes */0" else "bytes $offset-${offset + data.size - 1}/$length"
                    val response = try { send("PUT", session, mapOf("Content-Type" to mime, "Content-Range" to range), data) } catch (_: IOException) { DriveResponse(503) }
                    if (response.code in 200..299) return verified(response).also { progress(length, length) }
                    if (response.code == 404) { expired = true; break }
                    val state = if (transient(response)) {
                        if (++failures > 3) throw DriveFailure(response.code, reason(response))
                        pause(1000L shl (failures - 1))
                        retry("PUT", session, mapOf("Content-Range" to "bytes */$length"), ByteArray(0))
                    } else response
                    if (state.code in 200..299) return verified(state).also { progress(length, length) }
                    if (state.code == 404) { expired = true; break }
                    if (state.code != 308) throw DriveFailure(state.code, reason(state))
                    val next = state.header("Range")?.let {
                        Regex("bytes=0-(\\d+)").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull()?.plus(1)
                            ?: throw IOException("云盘上传进度无效")
                    } ?: 0L
                    check(next in 0..length && next <= offset + data.size) { "云盘返回了无效上传进度" }
                    if (next <= offset) { if (++failures > 3) throw IOException("云盘上传没有进展") } else if (!transient(response)) failures = 0
                    offset = next; progress(offset, length)
                    // If all bytes are acknowledged with 308, ask for final metadata rather than
                    // sending an invalid empty bytes N-(N-1)/N range.
                    if (offset == length && length > 0) {
                        val final = retry("PUT", session, mapOf("Content-Range" to "bytes */$length"), ByteArray(0))
                        if (final.code in 200..299) return verified(final)
                        throw IOException("云盘尚未确认上传完成")
                    }
                }
            }
            if (!expired) break
        }
        throw DriveFailure(404)
    }
}
