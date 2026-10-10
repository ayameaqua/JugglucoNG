package tk.glucodata.healthinsights

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

internal data class DriveResponse(val code: Int, val body: String = "", val headers: Map<String, String> = emptyMap()) {
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
}

internal fun interface DriveTransport {
    suspend fun execute(method: String, url: String, token: String, headers: Map<String, String>, body: ByteArray?): DriveResponse
}

/** Called on Dispatchers.IO. No redirects: a bearer token must never follow an arbitrary Location. */
internal object DriveHttp : DriveTransport {
    fun validateUrl(url: String) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "www.googleapis.com" && uri.userInfo == null && uri.port in setOf(-1, 443)) {
            "云盘返回了不支持的上传地址"
        }
    }
    override suspend fun execute(method: String, url: String, token: String, headers: Map<String, String>, body: ByteArray?): DriveResponse {
        validateUrl(url)
        currentCoroutineContext().ensureActive()
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            // Google documents POST + override for clients whose HTTP stack cannot send PATCH.
            connection.requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..399) connection.inputStream else connection.errorStream)?.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    if (bytes.size() + n > 1_048_576) throw IOException("云盘响应过大")
                    bytes.write(buffer, 0, n)
                }
                bytes.toString("UTF-8")
            }.orEmpty()
            currentCoroutineContext().ensureActive()
            return DriveResponse(code, text, connection.headerFields.filterKeys { it != null }.mapValues { it.value.firstOrNull().orEmpty() })
        } finally { connection.disconnect() }
    }
}
