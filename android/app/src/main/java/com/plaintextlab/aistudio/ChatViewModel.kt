package com.plaintextlab.aistudio

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.random.Random

/** Something picked for the next message: a picture or a text file. */
class DraftAttachment(val attachment: ChatAttachment, val picture: PickedImage? = null)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", 0)
    private val store = ChatStore(app.filesDir)
    private val clientId = UUID.randomUUID().toString()
    private val server get() = prefs.getString("server", "").orEmpty()

    /** All chats, newest first. Nothing is ever removed from it. */
    val chats = mutableStateListOf<Chat>().apply { addAll(store.load()) }
    /** The open chat; null for a new one, created with its first message. */
    var currentId by mutableStateOf(chats.firstOrNull()?.id)
        private set
    val current get() = chats.firstOrNull { it.id == currentId }
    var draft by mutableStateOf("")
    val attachments = mutableStateListOf<DraftAttachment>()
    /** The chat waiting for a reply, if any. */
    var thinking by mutableStateOf<String?>(null)
        private set
    /** The last reply that failed, per chat, with what went wrong. */
    var failed by mutableStateOf<Pair<String, String>?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    init {
        syncWithPc()
    }

    fun syncWithPc() {
        val url = server.ifBlank { return }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.sync(ComfyClient(url)) } }
                .onSuccess { merged ->
                    chats.clear(); chats.addAll(merged)
                    if (currentId == null) currentId = chats.firstOrNull()?.id
                }
        }
    }

    fun open(id: String?) {
        currentId = id
        failed = null
    }

    fun imageFile(name: String) = store.image(name)

    fun attachPicture(uri: Uri) {
        viewModelScope.launch {
            try {
                val picked = withContext(Dispatchers.IO) { Images.load(getApplication(), uri, maxPixels = 450_000) }
                attachments += DraftAttachment(ChatAttachment("image", "chat_${UUID.randomUUID()}.jpg"), picked)
            } catch (e: Exception) {
                message = "Could not open that picture"
            }
        }
    }

    fun attachFile(uri: Uri) {
        viewModelScope.launch {
            try {
                val (name, text) = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "file.txt"
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    name to bytes.decodeToString().take(MAX_FILE_CHARS)
                }
                if (text.isBlank()) message = "That file has no text in it" else attachments += DraftAttachment(ChatAttachment("file", name, text))
            } catch (e: Exception) {
                message = "Could not read that file"
            }
        }
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        val now = System.currentTimeMillis()
        val picked = attachments.toList()
        draft = ""
        attachments.clear()
        // Pictures are kept with the chats on the phone (and copied to the PC when sent).
        for (a in picked) a.picture?.let { store.image(a.attachment.name).writeBytes(it.jpeg) }
        val userMessage = ChatMessage("user", text, now, picked.map { it.attachment })
        val chat = current?.let { it.copy(updated = now, messages = it.messages + userMessage) }
            ?: Chat("chat_${now}_${Random.nextInt(0, 0xffff).toString(16)}", title(text, picked), now, now, listOf(userMessage))
        put(chat)
        currentId = chat.id
        reply(chat)
    }

    fun retry() {
        current?.let { if (it.messages.lastOrNull()?.isUser == true) reply(it) }
    }

    private fun reply(chat: Chat) {
        val url = server
        thinking = chat.id
        failed = null
        viewModelScope.launch {
            try {
                val answer = withContext(Dispatchers.IO) { ask(url, chat) }
                val latest = chats.first { it.id == chat.id }
                put(latest.copy(updated = System.currentTimeMillis(),
                    messages = latest.messages + ChatMessage("assistant", answer, System.currentTimeMillis())))
            } catch (e: Exception) {
                failed = chat.id to when {
                    url.isBlank() -> "Set the ComfyUI address in settings first"
                    e is ComfyException -> e.message ?: "ComfyUI reported an error"
                    e is IOException -> "Can't reach ComfyUI at $url. Is it running with --lan, on the same Wi-Fi?"
                    else -> e.message ?: e.javaClass.simpleName
                }
            } finally {
                thinking = null
            }
        }
    }

    private suspend fun ask(url: String, chat: Chat): String {
        val comfy = ComfyClient(url)
        val last = chat.messages.last()
        // The newest picture in the conversation goes with the message, so follow-up questions
        // about it work; pictures are uploaded once, under their own name.
        val picture = chat.messages.flatMap { it.attachments }.lastOrNull { it.isImage }
        val imageName = picture?.let { comfy.uploadImage(store.image(it.name).readBytes(), it.name, ChatStore.SERVER_DIR) }
        val workflow = Workflow.buildChat(messageText(last), history(chat.messages.dropLast(1)), imageName,
            Random.nextLong(0, 1L shl 50))
        val id = comfy.queue(workflow, clientId)
        return comfy.await(id).texts[Workflow.CHAT_REPLY_NODE]?.trim().orEmpty().ifEmpty { "…" }
    }

    private fun put(chat: Chat) {
        val i = chats.indexOfFirst { it.id == chat.id }
        if (i >= 0) chats.removeAt(i)
        chats.add(0, chat)
        store.save(chat)
        val url = server
        if (url.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { store.upload(ComfyClient(url), chat) } }
        }
    }

    private fun title(text: String, picked: List<DraftAttachment>): String {
        val t = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return when {
            t.isNotEmpty() -> if (t.length > 48) t.take(47).trimEnd() + "…" else t
            picked.any { it.attachment.isImage } -> "About a picture"
            else -> picked.firstOrNull()?.attachment?.name ?: "Chat"
        }
    }

    companion object {
        private const val MAX_FILE_CHARS = 20_000
        private const val MAX_HISTORY_CHARS = 8_000

        /** A message as the model reads it: its text and any attached text files. */
        fun messageText(m: ChatMessage): String = buildString {
            append(m.text)
            for (a in m.attachments) {
                when {
                    a.isImage -> if (m.isUser) append("\n[I attached a picture]")
                    else -> append("\n\n[Attached file: ${a.name}]\n${a.text.orEmpty()}")
                }
            }
        }.trim()

        /** Earlier messages as "User:" / "Assistant:" lines, the most recent ones that fit. */
        fun history(messages: List<ChatMessage>): String {
            val lines = messages.map { (if (it.isUser) "User: " else "Assistant: ") + messageText(it) }
            val kept = ArrayDeque<String>()
            var size = 0
            for (line in lines.asReversed()) {
                if (size + line.length > MAX_HISTORY_CHARS) break
                kept.addFirst(line); size += line.length
            }
            return kept.joinToString("\n\n")
        }

        /** The text after a "Prompt:" line, if the reply has one; else the whole reply. */
        fun promptIn(reply: String): String {
            val m = Regex("(?im)^\\s*\\**prompt\\**\\s*:\\**\\s*(.*)$").find(reply) ?: return reply.trim()
            val after = reply.substring(m.range.first).substringAfter(":").trim().trimStart('*').trim()
            return after.split(Regex("\\n\\s*\\n")).first().replace("**", "").trim().ifEmpty { reply.trim() }
        }
    }
}
