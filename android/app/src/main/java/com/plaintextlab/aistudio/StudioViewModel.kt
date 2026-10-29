package com.plaintextlab.aistudio

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.random.Random

enum class Shape(val label: String, val width: Int, val height: Int) {
    Square("Square", 1024, 1024),
    Portrait("Portrait", 832, 1216),
    Landscape("Landscape", 1216, 832),
}

/**
 * A finished image: the PNG as ComfyUI saved it, a bitmap to show, the model that made it
 * and the prompt it was given (the enhanced one when enhancing).
 */
class Result(val png: ByteArray, val bitmap: Bitmap, val name: String, val model: String?, val promptUsed: String?)

/** One request and its answer, shown as a pair in the list. */
data class Turn(
    val id: Long,
    val prompt: String,
    val input: PickedImage?,
    val face: PickedImage?,
    val shape: Shape,
    val state: State,
    val result: Result? = null,
    val error: String? = null,
) {
    enum class State { Waiting, Working, Done, Failed }
}

class StudioViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", 0)
    private val clientId = UUID.randomUUID().toString()

    var server by mutableStateOf(prefs.getString("server", "").orEmpty())
        private set
    /** Rewrite prompts in detail before generating (new images only). */
    var enhance by mutableStateOf(prefs.getBoolean("enhance", true))
        private set
    val turns = mutableStateListOf<Turn>()
    var shape by mutableStateOf(Shape.Square)
    /** The picture the next prompt edits, or null for a new image. */
    var attachment by mutableStateOf<PickedImage?>(null)
    /** Face put on the main person of every result until removed; kept between launches. */
    var face by mutableStateOf(loadFace())
        private set
    private val faceFile get() = java.io.File(getApplication<Application>().filesDir, "face.jpg")
    /** One-off messages for the snackbar. */
    var message by mutableStateOf<String?>(null)

    fun updateServer(url: String) {
        server = normalize(url)
        prefs.edit().putString("server", server).apply()
    }

    fun updateEnhance(on: Boolean) {
        enhance = on
        prefs.edit().putBoolean("enhance", on).apply()
    }

    suspend fun testServer(url: String): String = withContext(Dispatchers.IO) {
        val address = normalize(url)
        try {
            "Connected: ComfyUI ${ComfyClient(address).version()}"
        } catch (e: Exception) {
            friendly(e, address)
        }
    }

    /** "192.168.0.10:8188" -> "http://192.168.0.10:8188" */
    private fun normalize(url: String): String {
        val u = url.trim().trimEnd('/')
        return if (u.isEmpty() || u.contains("://")) u else "http://$u"
    }

    fun attach(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                attachment = withContext(Dispatchers.IO) { Images.load(getApplication(), uri) }
            } catch (e: Exception) {
                message = "Could not open that picture"
            }
        }
    }

    fun setFace(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val picked = withContext(Dispatchers.IO) {
                    Images.load(getApplication(), uri).also { faceFile.writeBytes(it.jpeg) }
                }
                face = picked
            } catch (e: Exception) {
                message = "Could not open that picture"
            }
        }
    }

    fun clearFace() {
        face = null
        faceFile.delete()
    }

    private fun loadFace(): PickedImage? = try {
        val file = java.io.File(getApplication<Application>().filesDir, "face.jpg")
        if (file.exists()) file.readBytes().let { PickedImage(Images.decode(it), it) } else null
    } catch (e: Exception) {
        null
    }

    /** "Edit this": use a result as the picture for the next prompt. */
    fun editResult(result: Result) {
        val jpeg = java.io.ByteArrayOutputStream()
            .also { result.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        attachment = PickedImage(result.bitmap, jpeg)
    }

    fun send(prompt: String) {
        val turn = Turn(System.nanoTime(), prompt.trim(), attachment, face, shape, Turn.State.Waiting)
        attachment = null
        turns += turn
        run(turn)
    }

    fun retry(turn: Turn) {
        val again = turn.copy(state = Turn.State.Waiting, error = null)
        update(again)
        run(again)
    }

    fun save(result: Result) {
        viewModelScope.launch {
            message = try {
                withContext(Dispatchers.IO) { Images.saveToGallery(getApplication(), result.png, result.name) }
                "Saved to Pictures/AI Studio"
            } catch (e: Exception) {
                "Could not save: ${e.message}"
            }
        }
    }

    private fun run(turn: Turn) {
        val url = server
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { generate(url, turn) }
                update(turn.copy(state = Turn.State.Done, result = result))
            } catch (e: Exception) {
                update(turn.copy(state = Turn.State.Failed, error = friendly(e, url)))
            }
        }
    }

    private suspend fun generate(url: String, turn: Turn): Result {
        val comfy = ComfyClient(url)
        val inputName = turn.input?.let { comfy.uploadImage(it.jpeg, "android_${UUID.randomUUID()}.jpg") }
        // Named after its content: the same face is stored once on the server.
        val faceName = turn.face?.let { comfy.uploadImage(it.jpeg, "android_face_${it.jpeg.contentHashCode().toUInt()}.jpg") }
        val workflow = Workflow.build(turn.prompt, turn.shape.width, turn.shape.height, inputName,
            Random.nextLong(0, 1L shl 50), enhance, faceName)
        val id = comfy.queue(workflow, clientId)
        while (true) {
            val finished = comfy.result(id)
            if (finished != null) {
                val first = finished.images.firstOrNull() ?: throw ComfyException("ComfyUI made no image")
                val png = comfy.download(first)
                return Result(png, Images.decode(png), first.filename, Workflow.modelName(first.filename),
                    finished.texts[Workflow.PROMPT_USED_NODE])
            }
            val waiting = comfy.isWaiting(id)
            withContext(Dispatchers.Main) {
                val now = turns.firstOrNull { it.id == turn.id }
                val state = if (waiting) Turn.State.Waiting else Turn.State.Working
                if (now != null && now.state != state) update(now.copy(state = state))
            }
            delay(700)
        }
    }

    private fun update(turn: Turn) {
        val i = turns.indexOfFirst { it.id == turn.id }
        if (i >= 0) turns[i] = turn
    }

    private fun friendly(e: Exception, url: String): String = when {
        url.isBlank() -> "Set the ComfyUI address in settings first"
        e is ComfyException -> e.message ?: "ComfyUI reported an error"
        e is IOException -> "Can't reach ComfyUI at $url. Is it running with --lan, on the same Wi-Fi?"
        else -> e.message ?: e.javaClass.simpleName
    }
}
