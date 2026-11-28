package com.printease.app

import com.google.firebase.auth.FirebaseAuth
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

object HostedDocuments {
    private const val ORIGIN = "https://printease-campus.consideratefennel8.chatgpt.site"

    private suspend fun identity(): Pair<String, String> {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser ?: error("Sign in before sending your PDF.")
        val token =
            withTimeout(15000) { user.getIdToken(false).await() }.token
                ?: error("Your session expired. Sign in again.")
        check(auth.currentUser?.uid == user.uid) { "Your account changed. Sign in again." }
        return user.uid to token
    }

    private fun connection(path: String, token: String, method: String): HttpURLConnection =
        (URL(ORIGIN + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 90000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Cache-Control", "no-store")
        }

    private fun checkResponse(connection: HttpURLConnection) {
        if (connection.responseCode in 200..299) return
        val message =
            runCatching {
                    connection.errorStream?.bufferedReader()?.use { reader ->
                        JSONObject(reader.readText()).optJSONObject("error")?.optString("message")
                    }
                }
                .getOrNull()
        throw IOException(
            message?.takeIf { it.isNotBlank() }?.take(250)
                ?: "The document service could not finish. Keep your draft and try again."
        )
    }

    suspend fun upload(
        pdf: PdfFile,
        progress: (Float) -> Unit,
        ownerId: String,
        orderId: String? = null,
    ): String =
        withContext(Dispatchers.IO) {
            require(Regex("[a-f0-9]{64}").matches(pdf.sha256)) {
                "Choose the PDF again before sending."
            }
            val source = File(pdf.path)
            require(source.length() in 5..MAX_PDF_BYTES.toLong()) {
                "Choose a PDF no larger than $MAX_FILE_MB MB."
            }
            val (uid, token) = identity()
            check(uid == ownerId) { "Your account changed. Sign in again before sending." }
            if (orderId != null) require(Regex("[A-Za-z0-9_-]{1,128}").matches(orderId))
            val path = orderId?.let { "$it/${pdf.sha256}" } ?: pdf.sha256
            val expected = "hosted/$uid/$path.pdf"
            val prior = connection("/api/uploads/$path", token, "HEAD")
            val uploaded =
                try {
                    prior.readTimeout = 8000
                    prior.connectTimeout = 8000
                    prior.responseCode == 204
                } catch (_: IOException) {
                    false
                } finally {
                    prior.disconnect()
                }
            ensureActive()
            if (uploaded) {
                progress(.97f)
                return@withContext expected
            }
            val request = connection("/api/uploads/$path", token, "PUT")
            try {
                request.doOutput = true
                request.setFixedLengthStreamingMode(source.length())
                request.setRequestProperty("Content-Type", "application/pdf")
                source.inputStream().use { input ->
                    request.outputStream.use { output ->
                        val buffer = ByteArray(16384)
                        var sent = 0L
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            sent += count
                            progress((sent.toFloat() / source.length() * .95f).coerceAtMost(.95f))
                        }
                    }
                }
                checkResponse(request)
                val result = request.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                check(
                    result.getString("storagePath") == expected &&
                        result.getString("fileHash") == pdf.sha256
                ) {
                    "The uploaded document could not be confirmed. Keep this draft and retry."
                }
                progress(.97f)
                expected
            } finally {
                request.disconnect()
            }
        }

    suspend fun cleanup(id: String): Boolean =
        withContext(Dispatchers.IO) {
            require(Regex("[A-Za-z0-9_-]{1,128}").matches(id))
            val (_, token) = identity()
            val request = connection("/api/document-cleanup/$id", token, "POST")
            request.readTimeout = 30000
            try {
                request.doOutput = true
                request.setFixedLengthStreamingMode(0)
                request.outputStream.close()
                checkResponse(request)
                request.inputStream.bufferedReader().use {
                    JSONObject(it.readText()).optBoolean("removed")
                }
            } finally {
                request.disconnect()
            }
        }

    suspend fun notifyReady(orderId: String, reviewVersion: Long? = null): Boolean =
        withContext(Dispatchers.IO) {
            require(Regex("[A-Za-z0-9_-]{1,128}").matches(orderId))
            val (_, token) = identity()
            val route =
                if (reviewVersion == null) "notify/$orderId" else "review/$orderId/$reviewVersion"
            val request = connection("/api/push/$route", token, "POST")
            request.readTimeout = 45000
            try {
                request.doOutput = true
                request.setFixedLengthStreamingMode(0)
                request.outputStream.close()
                checkResponse(request)
                request.inputStream.bufferedReader().use {
                    JSONObject(it.readText()).optInt("failed", 0) > 0
                }
            } finally {
                request.disconnect()
            }
        }

    suspend fun download(order: PrintOrder, target: File, index: Int = 0): File {
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(order.id))
        val query = if (index == 0) "" else "?file=$index"
        return download("/api/documents/${order.id}$query", target)
    }

    suspend fun downloadService(id: String, target: File): File {
        require(Regex("service-[a-f0-9-]{36}").matches(id))
        return download("/api/service-documents/$id", target)
    }

    private suspend fun download(path: String, target: File): File =
        withContext(Dispatchers.IO) {
            val (_, token) = identity()
            val request = connection(path, token, "GET")
            val temp = File(target.parentFile, "${UUID.randomUUID()}.part")
            try {
                checkResponse(request)
                request.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(16384)
                        var size = 0L
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            require(size <= MAX_PDF_BYTES) { "The PDF exceeds $MAX_FILE_MB MB." }
                            output.write(buffer, 0, count)
                        }
                        require(size >= 5) { "The PDF is unavailable. Contact the shop." }
                    }
                }
                check(temp.renameTo(target)) { "The PDF could not be saved for preview." }
                target
            } catch (error: Exception) {
                temp.delete()
                throw error
            } finally {
                request.disconnect()
            }
        }
}
