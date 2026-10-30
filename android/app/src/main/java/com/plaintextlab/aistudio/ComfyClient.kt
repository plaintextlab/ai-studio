package com.plaintextlab.aistudio

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class ComfyException(message: String) : IOException(message)

/** An image ComfyUI saved: what /view needs to fetch it. */
data class OutputImage(val filename: String, val subfolder: String, val type: String)

/** What a finished prompt produced: its saved images, and text shown by preview nodes. */
data class Finished(val images: List<OutputImage>, val texts: Map<String, String>)

/** "Which face should be replaced?": the picture with its faces numbered, and each face. */
class FaceQuestion(
    val id: String, val picture: Int, val pictures: Int,
    val image: android.graphics.Bitmap, val faces: List<android.graphics.Bitmap>,
)

/**
 * ComfyUI's HTTP API (the same one its web page uses). All calls block; run them off
 * the main thread.
 */
class ComfyClient(baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/')

    /** ComfyUI's version, as a connection check. */
    fun version(): String =
        request("/system_stats").let { JSONObject(it.decodeToString()) }
            .getJSONObject("system").optString("comfyui_version", "?")

    /**
     * Uploads a picture into ComfyUI's input folder (or a [subfolder] of it); returns the
     * name to use in a workflow.
     */
    fun uploadImage(jpeg: ByteArray, name: String, subfolder: String = ""): String {
        val boundary = "----aistudio" + UUID.randomUUID()
        val body = buildString {
            append("--$boundary\r\nContent-Disposition: form-data; name=\"overwrite\"\r\n\r\ntrue\r\n")
            if (subfolder.isNotEmpty()) {
                append("--$boundary\r\nContent-Disposition: form-data; name=\"subfolder\"\r\n\r\n$subfolder\r\n")
            }
            append("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"$name\"\r\n")
            append("Content-Type: image/jpeg\r\n\r\n")
        }.toByteArray() + jpeg + "\r\n--$boundary--\r\n".toByteArray()
        val reply = JSONObject(request("/upload/image", body, "multipart/form-data; boundary=$boundary").decodeToString())
        val sub = reply.optString("subfolder")
        return if (sub.isEmpty()) reply.getString("name") else "$sub/${reply.getString("name")}"
    }

    /** Queues a workflow (API format); returns its prompt id. */
    fun queue(workflow: JSONObject, clientId: String): String {
        val body = JSONObject().put("prompt", workflow).put("client_id", clientId)
        val reply = JSONObject(request("/prompt", body.toString().toByteArray(), "application/json").decodeToString())
        return reply.getString("prompt_id")
    }

    /**
     * Where a queued prompt is: null while it waits or runs, else what it produced.
     * Throws when it failed.
     */
    fun result(promptId: String): Finished? {
        val all = JSONObject(request("/history/$promptId").decodeToString())
        val entry = all.optJSONObject(promptId) ?: return null
        val status = entry.optJSONObject("status") ?: return null
        if (status.optString("status_str") == "error") throw ComfyException(executionError(status))
        if (!status.optBoolean("completed")) return null
        val images = mutableListOf<OutputImage>()
        val texts = mutableMapOf<String, String>()
        val outputs = entry.optJSONObject("outputs") ?: JSONObject()
        for (node in outputs.keys()) {
            val out = outputs.getJSONObject(node)
            out.optJSONArray("text")?.optString(0)?.let { texts[node] = it }
            val list = out.optJSONArray("images") ?: continue
            for (i in 0 until list.length()) {
                val img = list.getJSONObject(i)
                if (img.optString("type") == "output") {
                    images += OutputImage(img.getString("filename"), img.optString("subfolder"), "output")
                }
            }
        }
        return Finished(images, texts)
    }

    /**
     * Waits for a queued prompt to finish. A busy PC can miss a status check now and then,
     * so short outages are ridden out; only ~30 s without an answer counts as a failure.
     * [onWaiting] is told whether the prompt is still queued behind others.
     */
    suspend fun await(promptId: String, onWaiting: suspend (Boolean) -> Unit = {}): Finished {
        var misses = 0
        while (true) {
            try {
                result(promptId)?.let { return it }
                onWaiting(isWaiting(promptId))
                misses = 0
            } catch (e: ComfyException) {
                throw e
            } catch (e: IOException) {
                if (++misses >= 10) throw e
            }
            kotlinx.coroutines.delay(if (misses == 0) 700L else 3000L)
        }
    }

    /** True while the prompt is still waiting behind others (not running yet). */
    fun isWaiting(promptId: String): Boolean {
        val queue = JSONObject(request("/queue").decodeToString())
        return contains(queue.optJSONArray("queue_pending"), promptId)
    }

    fun download(image: OutputImage): ByteArray =
        request("/view?filename=${enc(image.filename)}&subfolder=${enc(image.subfolder)}&type=${enc(image.type)}")

    // The studio's "Swap face" node: with several faces in a picture it waits for a choice.

    /** Questions [promptId] is waiting on: which face to replace. */
    fun faceQuestions(promptId: String): List<FaceQuestion> {
        val list = JSONArray(request("/studio/faces/pending?prompt_id=${enc(promptId)}").decodeToString())
        return List(list.length()) { i ->
            val q = list.getJSONObject(i)
            val faces = q.getJSONArray("faces")
            FaceQuestion(q.getString("id"), q.optInt("picture", 1), q.optInt("pictures", 1),
                dataUrl(q.getString("image")), List(faces.length()) { dataUrl(faces.getString(it)) })
        }
    }

    /** Answers a face question: a face's number (1 = leftmost), or 0 to keep all faces. */
    fun chooseFace(questionId: String, choice: Int) {
        val body = JSONObject().put("id", questionId).put("choice", choice).toString().toByteArray()
        request("/studio/faces/choose", body, "application/json")
    }

    /**
     * Everything the app made that the PC kept (outputs/comfyui/images and thumbnails),
     * oldest first, each with its prompt.
     */
    fun history(source: String = "android"): List<JSONObject> {
        val list = JSONArray(request("/studio/history?source=${enc(source)}").decodeToString())
        return List(list.length()) { list.getJSONObject(it) }
    }

    /** The PC's other checkpoints (any SD 1.5 or SDXL model), for the model menu. */
    fun models(): List<PcModel> {
        val list = JSONArray(request("/studio/models").decodeToString())
        return List(list.length()) { PcModel.fromJson(list.getJSONObject(it)) }
    }

    private fun dataUrl(url: String): android.graphics.Bitmap =
        Images.decode(android.util.Base64.decode(url.substringAfter(","), android.util.Base64.DEFAULT))

    // ComfyUI's user data store (data/comfyui/user/default/<path> on the PC): the app keeps
    // a copy of every chat there.

    /** File names in a user data folder, e.g. "chats". */
    fun listUserData(dir: String): List<String> = try {
        val list = JSONArray(request("/userdata?dir=${enc(dir)}").decodeToString())
        List(list.length()) { list.getString(it) }
    } catch (e: ComfyException) {
        emptyList() // the folder does not exist yet
    }

    fun readUserData(path: String): ByteArray = request("/userdata/${enc(path)}")

    fun writeUserData(path: String, data: ByteArray) {
        request("/userdata/${enc(path)}?overwrite=true", data, "application/octet-stream")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun contains(items: JSONArray?, promptId: String): Boolean {
        if (items == null) return false
        for (i in 0 until items.length()) {
            if (items.optJSONArray(i)?.optString(1) == promptId) return true
        }
        return false
    }

    private fun executionError(status: JSONObject): String {
        val messages = status.optJSONArray("messages") ?: return "Generation failed"
        for (i in 0 until messages.length()) {
            val m = messages.optJSONArray(i) ?: continue
            if (m.optString(0) == "execution_error") {
                val detail = m.optJSONObject(1)
                return detail?.optString("exception_message")?.trim()?.ifEmpty { null } ?: "Generation failed"
            }
        }
        return "Generation failed"
    }

    private fun request(path: String, body: ByteArray? = null, contentType: String? = null): ByteArray {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 120_000
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val text = conn.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()
                throw ComfyException(errorMessage(code, text))
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** ComfyUI's validation errors are nested JSON; keep the part a person can act on. */
    private fun errorMessage(code: Int, text: String): String {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return "ComfyUI answered $code"
        val error = json.optJSONObject("error")
        val nodeErrors = json.optJSONObject("node_errors")
        if (nodeErrors != null) {
            for (node in nodeErrors.keys()) {
                val errs = nodeErrors.getJSONObject(node).optJSONArray("errors") ?: continue
                if (errs.length() > 0) {
                    val e = errs.getJSONObject(0)
                    return listOf(e.optString("message"), e.optString("details"))
                        .filter { it.isNotBlank() }.joinToString(": ")
                }
            }
        }
        return error?.optString("message")?.ifBlank { null } ?: "ComfyUI answered $code"
    }
}
