package com.plaintextlab.aistudio

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every finished Image and Thumbnail request, kept on the phone so it is there after a
 * restart: history/<turn id>/turn.json (the prompt, the model, the prompt used...) with the
 * images next to it, and the picture edited and the face used. [importFromPc] brings in what
 * the PC kept of the app's images (also those made before the app kept a history), so they
 * come back after a reinstall too. Never deletes.
 */
class History(root: File) {
    private val dir = File(root, "history").apply { mkdirs() }

    fun folder(turnId: Long) = File(dir, turnId.toString()).apply { mkdirs() }

    fun load(): List<Turn> = dir.listFiles().orEmpty()
        .mapNotNull { f -> runCatching { fromJson(f, JSONObject(File(f, "turn.json").readText())) }.getOrNull() }
        .sortedBy { it.time }

    /** Saves a finished turn (its images are already in [folder]). */
    fun save(turn: Turn) {
        val folder = folder(turn.id)
        turn.input?.let { File(folder, "input.jpg").writeBytes(it.jpeg) }
        turn.face?.let { File(folder, "face.jpg").writeBytes(it.jpeg) }
        val tmp = File(folder, "turn.json.tmp")
        tmp.writeText(toJson(turn).toString())
        tmp.renameTo(File(folder, "turn.json"))
    }

    /**
     * Turns for the PC's images that are not on the phone yet ([known]: "subfolder/filename"
     * of every result already here), oldest first. One turn per run, as it was made.
     */
    fun importFromPc(comfy: ComfyClient, known: Set<String>): List<Turn> {
        val runs = comfy.history().filter { "${it.optString("subfolder")}/${it.optString("filename")}" !in known }
            .groupBy { it.optString("run").ifEmpty { it.optString("filename") } }
        return runs.values.mapNotNull { entries ->
            runCatching {
                val first = entries.first()
                val time = (first.optDouble("time") * 1000).toLong()
                val id = time * 1000 + (first.optString("run").hashCode().toUInt() % 1000u).toLong()
                val folder = folder(id)
                val mode = if (first.optString("mode") == "thumbnail") Mode.Thumbnail else Mode.Image
                val results = entries.mapIndexed { i, e ->
                    val server = OutputImage(e.getString("filename"), e.optString("subfolder"), "output")
                    val file = File(folder, "image_${i + 1}.png")
                    if (!file.exists()) file.writeBytes(comfy.download(server))
                    val model = e.optString("model").ifEmpty { null }?.let(Workflow::modelLabel)
                        ?: if (mode == Mode.Thumbnail) "Z-Image Turbo" else Workflow.modelName(server.filename)
                    Result(file, server.filename, model, e.optString("prompt_used").ifEmpty { null }, server)
                }
                val (w, h) = Images.size(results.first().file)
                val shape = Shape.entries.minBy { s -> kotlin.math.abs(s.width.toFloat() / s.height - w.toFloat() / h) }
                Turn(id, mode, first.optString("prompt"), null, null, shape, Turn.State.Done, results = results,
                    text = first.optString("text"), time = time, count = results.size).also(::save)
            }.getOrNull()
        }.sortedBy { it.time }
    }

    private fun toJson(turn: Turn) = JSONObject()
        .put("id", turn.id).put("mode", turn.mode.name).put("prompt", turn.prompt).put("text", turn.text)
        .put("shape", turn.shape.name).put("time", turn.time).put("model", turn.model.name).put("pcModel", turn.pcModel?.toJson())
        .put("results", JSONArray().apply {
            turn.results.forEach { r ->
                put(JSONObject().put("file", r.file.name).put("name", r.name).put("model", r.model ?: "")
                    .put("promptUsed", r.promptUsed ?: "")
                    .put("subfolder", r.server?.subfolder ?: "").put("filename", r.server?.filename ?: ""))
            }
        })

    private fun fromJson(folder: File, o: JSONObject): Turn {
        val list = o.getJSONArray("results")
        val results = List(list.length()) { i ->
            val r = list.getJSONObject(i)
            val server = r.optString("filename").ifEmpty { null }?.let { OutputImage(it, r.optString("subfolder"), "output") }
            Result(File(folder, r.getString("file")), r.optString("name"), r.optString("model").ifEmpty { null },
                r.optString("promptUsed").ifEmpty { null }, server)
        }
        fun picked(name: String) = File(folder, name).takeIf { it.exists() }
            ?.let { PickedImage(Images.load(it, 256), it.readBytes()) }
        return Turn(
            id = o.getLong("id"),
            mode = Mode.valueOf(o.getString("mode")),
            prompt = o.getString("prompt"),
            input = picked("input.jpg"),
            face = picked("face.jpg"),
            shape = Shape.valueOf(o.optString("shape", Shape.Square.name)),
            state = Turn.State.Done,
            results = results,
            text = o.optString("text"),
            time = o.optLong("time"),
            count = results.size,
            model = ImageModel.entries.firstOrNull { it.name == o.optString("model") } ?: ImageModel.Auto,
            pcModel = o.optJSONObject("pcModel")?.let(PcModel::fromJson),
        )
    }
}
