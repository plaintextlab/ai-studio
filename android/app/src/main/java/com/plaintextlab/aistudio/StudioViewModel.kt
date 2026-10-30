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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.random.Random

enum class Shape(val label: String, val width: Int, val height: Int) {
    Square("Square", 1024, 1024),
    Portrait("Portrait", 832, 1216),
    Landscape("Landscape", 1216, 832),
    Thumbnail("16:9", 1280, 720),
}

/**
 * The model for new images ([server] is its name in the studio's Pick model node). Auto lets
 * the PC pick from the prompt; edits always use FLUX.2 Klein, the only one that edits.
 */
enum class ImageModel(val label: String, val short: String, val server: String, val bestFor: String) {
    Auto("Auto", "Auto", "Auto",
        "Picks for each prompt: Z-Image for people, Lustify for adult content, Animagine for anime, Klein for the rest"),
    Klein("FLUX.2 Klein", "Klein", "FLUX.2 Klein",
        "Scenes, landscapes, objects, food and products, with fine detail"),
    ZImage("Z-Image Turbo", "Z-Image", "Z-Image Turbo",
        "Realistic photos of people, also groups together; the best at words in a picture"),
    Lustify("Lustify", "Lustify", "Lustify (adult)",
        "Realistic explicit adult content"),
    Animagine("Animagine", "Animagine", "Animagine (anime)",
        "Anime, manga and illustration"),
}

/** A checkpoint on the PC with no branch of its own: any SD 1.5 or SDXL model (/studio/models). */
data class PcModel(val name: String, val label: String, val arch: String, val hint: String) {
    val sd15 get() = arch == "sd15"
    fun toJson(): JSONObject = JSONObject().put("name", name).put("label", label).put("arch", arch).put("hint", hint)

    companion object {
        fun fromJson(o: JSONObject) = PcModel(o.getString("name"), o.optString("label", o.getString("name")),
            o.optString("arch", "sdxl"), o.optString("hint"))
    }
}

/** The image tabs: Quick image, and YouTube thumbnails. */
enum class Mode { Image, Thumbnail }

/** How many images one request can make (all at once, as one batch on the PC). */
val IMAGE_COUNTS = 1..6

/**
 * A finished image: the PNG as ComfyUI saved it (a file in the history), the model that made
 * it, the prompt it was given (the enhanced one when enhancing) and where the PC keeps it.
 */
class Result(val file: File, val name: String, val model: String?, val promptUsed: String?, val server: OutputImage?) {
    val key get() = server?.let { "${it.subfolder}/${it.filename}" }
}

/** One request and its answer, shown as a pair in the list. */
data class Turn(
    val id: Long,
    val mode: Mode,
    /** The prompt (Image) or the thumbnail idea (Thumbnail). */
    val prompt: String,
    val input: PickedImage?,
    val face: PickedImage?,
    val shape: Shape,
    val state: State,
    val results: List<Result> = emptyList(),
    val error: String? = null,
    /** Words on a thumbnail. */
    val text: String = "",
    /** When it was asked (ms since 1970). */
    val time: Long = System.currentTimeMillis(),
    /** Images asked for. */
    val count: Int = 1,
    /** Set while the PC waits to be told which face to replace. */
    val question: FaceQuestion? = null,
    /** The model asked for (new images). */
    val model: ImageModel = ImageModel.Auto,
    /** A PC checkpoint asked for instead of [model]. */
    val pcModel: PcModel? = null,
) {
    enum class State { Waiting, Working, Done, Failed }
}

class StudioViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", 0)
    private val clientId = UUID.randomUUID().toString()
    private val history = History(app.filesDir)

    var server by mutableStateOf(prefs.getString("server", "").orEmpty())
        private set
    /** Rewrite prompts in detail before generating (new images only). */
    var enhance by mutableStateOf(prefs.getBoolean("enhance", true))
        private set
    /** Images per request. */
    var count by mutableStateOf(prefs.getInt("count", 1).coerceIn(IMAGE_COUNTS))
        private set
    /** Model for new images. */
    var model by mutableStateOf(ImageModel.entries.firstOrNull { it.name == prefs.getString("model", null) } ?: ImageModel.Auto)
        private set
    /** A checkpoint from [pcModels] used instead of [model], or null. */
    var pcModel by mutableStateOf(prefs.getString("pcModel", null)
        ?.let { runCatching { PcModel.fromJson(JSONObject(it)) }.getOrNull() })
        private set
    /** The PC's other checkpoints (empty until it answers). */
    val pcModels = mutableStateListOf<PcModel>()
    val turns = mutableStateListOf<Turn>()
    val thumbnailTurns = mutableStateListOf<Turn>()
    fun turnsFor(mode: Mode) = if (mode == Mode.Image) turns else thumbnailTurns
    var shape by mutableStateOf(Shape.Square)
    /** What is typed in the Image tab (the Chat tab can put a prompt there). */
    var imageDraft by mutableStateOf("")
    var thumbnailIdea by mutableStateOf("")
    var thumbnailText by mutableStateOf("")
    /** The picture the next prompt edits, or null for a new image. */
    var attachment by mutableStateOf<PickedImage?>(null)
    /** Face put on the person in every result until removed; kept between launches. */
    var face by mutableStateOf(loadFace())
        private set
    private val faceFile get() = java.io.File(getApplication<Application>().filesDir, "face.jpg")
    /** One-off messages for the snackbar. */
    var message by mutableStateOf<String?>(null)
    /** Face questions already answered (the PC may still list one for a moment). */
    private val answered = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { history.load() }
            saved.forEach { add(it) }
            syncWithPc()
        }
    }

    /** Brings in the images the PC kept that the phone does not have (e.g. after a reinstall). */
    fun syncWithPc() {
        val url = server
        if (url.isBlank()) return
        viewModelScope.launch {
            // First the model list, so imported images get their models' names.
            runCatching { withContext(Dispatchers.IO) { ComfyClient(url).models() } }.onSuccess { list ->
                list.forEach { Workflow.addModelName(it.name, it.label) }
                pcModels.clear()
                pcModels += list
                pcModel?.let { p -> if (list.none { it.name == p.name }) updateModel(model) }
            }
            val known = (turns + thumbnailTurns).flatMap { it.results }.mapNotNull { it.key }.toSet()
            val imported = runCatching {
                withContext(Dispatchers.IO) { history.importFromPc(ComfyClient(url), known) }
            }.getOrDefault(emptyList())
            imported.forEach { add(it) }
        }
    }

    /** Adds a turn to its list in time order (the list ends with the newest). */
    private fun add(turn: Turn) {
        val list = turnsFor(turn.mode)
        if (list.any { it.id == turn.id }) return
        val at = list.indexOfFirst { it.time > turn.time }
        if (at < 0) list += turn else list.add(at, turn)
    }

    fun updateServer(url: String) {
        server = normalize(url)
        prefs.edit().putString("server", server).apply()
    }

    fun updateEnhance(on: Boolean) {
        enhance = on
        prefs.edit().putBoolean("enhance", on).apply()
    }

    fun updateCount(n: Int) {
        count = n.coerceIn(IMAGE_COUNTS)
        prefs.edit().putInt("count", count).apply()
    }

    fun updateModel(m: ImageModel) {
        model = m
        pcModel = null
        prefs.edit().putString("model", m.name).remove("pcModel").apply()
    }

    fun updatePcModel(m: PcModel) {
        pcModel = m
        prefs.edit().putString("pcModel", m.toJson().toString()).apply()
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
        viewModelScope.launch {
            attachment = withContext(Dispatchers.IO) {
                val bitmap = Images.load(result.file, 2048)
                val jpeg = java.io.ByteArrayOutputStream()
                    .also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
                PickedImage(bitmap, jpeg)
            }
        }
    }

    fun send(prompt: String) {
        val turn = Turn(newId(), Mode.Image, prompt.trim(), attachment, face, shape, Turn.State.Waiting, count = count,
            model = if (attachment == null) model else ImageModel.Auto,
            pcModel = if (attachment == null) pcModel else null)
        attachment = null
        turns += turn
        run(turn)
    }

    fun sendThumbnail(idea: String, text: String) {
        val turn = Turn(newId(), Mode.Thumbnail, idea.trim(), null, face, Shape.Thumbnail,
            Turn.State.Waiting, text = text.trim(), count = count)
        thumbnailTurns += turn
        run(turn)
    }

    /** "Again": the same request (prompt, picture, face, shape, count, model) as a new one, with a new seed. */
    fun again(turn: Turn) {
        val copy = Turn(newId(), turn.mode, turn.prompt, turn.input, turn.face, turn.shape, Turn.State.Waiting,
            text = turn.text, count = turn.count, model = turn.model, pcModel = turn.pcModel)
        turnsFor(turn.mode) += copy
        run(copy)
    }

    private fun newId() = System.currentTimeMillis() * 1000 + Random.nextInt(1000)

    fun retry(turn: Turn) {
        val again = turn.copy(state = Turn.State.Waiting, error = null)
        update(again)
        run(again)
    }

    fun save(result: Result) {
        viewModelScope.launch {
            message = try {
                withContext(Dispatchers.IO) { Images.saveToGallery(getApplication(), result.file.readBytes(), result.name) }
                "Saved to Pictures/AI Studio"
            } catch (e: Exception) {
                "Could not save: ${e.message}"
            }
        }
    }

    /** Answers the PC's "which face?" question: a face's number, or 0 to keep all faces. */
    fun chooseFace(turn: Turn, choice: Int) {
        val question = turn.question ?: return
        val url = server
        answered += question.id
        update(turn.copy(question = null))
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ComfyClient(url).chooseFace(question.id, choice) }
            } catch (e: Exception) {
                message = friendly(e, url)
            }
        }
    }

    private fun run(turn: Turn) {
        val url = server
        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { generate(url, turn) }
                val done = current(turn).copy(state = Turn.State.Done, results = results, question = null)
                update(done)
                withContext(Dispatchers.IO) { history.save(done) }
            } catch (e: Exception) {
                update(current(turn).copy(state = Turn.State.Failed, error = friendly(e, url), question = null))
            }
        }
    }

    private fun current(turn: Turn) = turnsFor(turn.mode).firstOrNull { it.id == turn.id } ?: turn

    private suspend fun generate(url: String, turn: Turn): List<Result> {
        val comfy = ComfyClient(url)
        val inputName = turn.input?.let { comfy.uploadImage(it.jpeg, "android_${UUID.randomUUID()}.jpg") }
        // Named after its content: the same face is stored once on the server.
        val faceName = turn.face?.let { comfy.uploadImage(it.jpeg, "android_face_${it.jpeg.contentHashCode().toUInt()}.jpg") }
        val seed = Random.nextLong(0, 1L shl 50)
        val workflow = if (turn.mode == Mode.Thumbnail) {
            Workflow.buildThumbnail(turn.prompt, turn.text, seed, enhance, faceName, turn.count)
        } else {
            Workflow.build(turn.prompt, turn.shape.width, turn.shape.height, inputName, seed, enhance, faceName, turn.count,
                turn.model.server, turn.pcModel)
        }
        val id = comfy.queue(workflow, clientId)
        val finished = comfy.await(id) { waiting ->
            // With several faces in a picture, the PC waits for the user to pick one. A missed
            // check keeps what is shown (null); an answered question is not shown again.
            val asked = if (faceName != null && !waiting) {
                runCatching { comfy.faceQuestions(id).filter { it.id !in answered } }.getOrNull()
            } else emptyList()
            withContext(Dispatchers.Main) {
                val now = turnsFor(turn.mode).firstOrNull { it.id == turn.id } ?: return@withContext
                val question = if (asked == null) now.question else asked.firstOrNull()
                val state = if (waiting) Turn.State.Waiting else Turn.State.Working
                if (now.state != state || now.question?.id != question?.id) {
                    update(now.copy(state = state, question = question))
                }
            }
        }
        if (finished.images.isEmpty()) throw ComfyException("ComfyUI made no image")
        val promptUsed = finished.texts[if (turn.mode == Mode.Thumbnail) Workflow.THUMBNAIL_PROMPT_NODE else Workflow.PROMPT_USED_NODE]
        val folder = history.folder(turn.id)
        return finished.images.mapIndexed { i, image ->
            val file = File(folder, "image_${i + 1}.png").apply { writeBytes(comfy.download(image)) }
            val model = if (turn.mode == Mode.Thumbnail) "Z-Image Turbo" else Workflow.modelName(image.filename)
            Result(file, image.filename, model, promptUsed, image)
        }
    }

    private fun update(turn: Turn) {
        val list = turnsFor(turn.mode)
        val i = list.indexOfFirst { it.id == turn.id }
        if (i >= 0) list[i] = turn
    }

    fun friendly(e: Exception, url: String): String = when {
        url.isBlank() -> "Set the ComfyUI address in settings first"
        e is ComfyException -> e.message ?: "ComfyUI reported an error"
        e is IOException -> "Can't reach ComfyUI at $url. Is it running with --lan, on the same Wi-Fi?"
        else -> e.message ?: e.javaClass.simpleName
    }
}
