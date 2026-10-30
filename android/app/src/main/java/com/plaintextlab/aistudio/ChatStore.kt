package com.plaintextlab.aistudio

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A picture (stored as [name] next to the chats) or a text file (its [text] kept inline). */
data class ChatAttachment(val kind: String, val name: String, val text: String? = null) {
    val isImage get() = kind == "image"
}

data class ChatMessage(val role: String, val text: String, val time: Long, val attachments: List<ChatAttachment> = emptyList()) {
    val isUser get() = role == "user"
}

data class Chat(val id: String, val title: String, val created: Long, val updated: Long, val messages: List<ChatMessage>)

/**
 * Chats are never deleted: each is a JSON file in the app's storage, and a copy goes to the
 * PC (ComfyUI's user data, data/comfyui/user/default/chats/<id>.json, with its pictures in
 * inputs/comfyui/chats/), so they survive a new phone or a reinstall. [sync] merges both
 * ways, keeping the newer copy of each chat and never removing one.
 */
class ChatStore(root: File) {
    private val dir = File(root, "chats").apply { mkdirs() }
    val imageDir = File(dir, "images").apply { mkdirs() }

    fun load(): List<Chat> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
        .sortedByDescending { it.updated }

    fun save(chat: Chat) {
        val file = File(dir, "${chat.id}.json")
        val tmp = File(dir, "${chat.id}.json.tmp")
        tmp.writeText(toJson(chat).toString())
        tmp.renameTo(file)
    }

    fun image(name: String) = File(imageDir, name)

    /** Copies chats both ways with the PC; returns the merged list. Never deletes. */
    fun sync(comfy: ComfyClient): List<Chat> {
        val local = load().associateBy { it.id }.toMutableMap()
        val remoteNames = comfy.listUserData(SERVER_DIR).filter { it.endsWith(".json") }
        val remoteUpdated = mutableMapOf<String, Long>()
        for (name in remoteNames) {
            val chat = runCatching {
                fromJson(JSONObject(comfy.readUserData("$SERVER_DIR/$name").decodeToString()))
            }.getOrNull() ?: continue
            remoteUpdated[chat.id] = chat.updated
            val mine = local[chat.id]
            if (mine == null || chat.updated > mine.updated) {
                save(chat)
                local[chat.id] = chat
                fetchImages(comfy, chat)
            }
        }
        for (chat in local.values) {
            if ((remoteUpdated[chat.id] ?: -1) < chat.updated) upload(comfy, chat)
        }
        return local.values.sortedByDescending { it.updated }
    }

    /** Copies one chat (not its pictures, uploaded when sent) to the PC. */
    fun upload(comfy: ComfyClient, chat: Chat) {
        comfy.writeUserData("$SERVER_DIR/${chat.id}.json", toJson(chat).toString().toByteArray())
    }

    private fun fetchImages(comfy: ComfyClient, chat: Chat) {
        for (a in chat.messages.flatMap { it.attachments }.filter { it.isImage }) {
            val file = image(a.name)
            if (!file.exists()) {
                runCatching { file.writeBytes(comfy.download(OutputImage(a.name, SERVER_DIR, "input"))) }
            }
        }
    }

    companion object {
        /** Folder name used on the PC, for the JSON files and for the pictures. */
        const val SERVER_DIR = "chats"

        fun toJson(chat: Chat): JSONObject = JSONObject()
            .put("id", chat.id).put("title", chat.title).put("created", chat.created).put("updated", chat.updated)
            .put("messages", JSONArray().apply {
                chat.messages.forEach { m ->
                    put(JSONObject().put("role", m.role).put("text", m.text).put("time", m.time)
                        .put("attachments", JSONArray().apply {
                            m.attachments.forEach { a ->
                                put(JSONObject().put("kind", a.kind).put("name", a.name).apply { a.text?.let { put("text", it) } })
                            }
                        }))
                }
            })

        fun fromJson(o: JSONObject): Chat {
            val messages = o.getJSONArray("messages")
            return Chat(
                id = o.getString("id"),
                title = o.optString("title", "Chat"),
                created = o.optLong("created"),
                updated = o.optLong("updated"),
                messages = List(messages.length()) { i ->
                    val m = messages.getJSONObject(i)
                    val atts = m.optJSONArray("attachments") ?: JSONArray()
                    ChatMessage(
                        role = m.getString("role"),
                        text = m.optString("text"),
                        time = m.optLong("time"),
                        attachments = List(atts.length()) { j ->
                            val a = atts.getJSONObject(j)
                            ChatAttachment(a.getString("kind"), a.getString("name"), a.optString("text").ifEmpty { null })
                        },
                    )
                },
            )
        }
    }
}
