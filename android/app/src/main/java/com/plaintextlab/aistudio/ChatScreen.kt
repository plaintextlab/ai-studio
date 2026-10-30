package com.plaintextlab.aistudio

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val CHAT_SUGGESTIONS = listOf(
    "Create a character for me: describe their look, then give me a prompt for a portrait",
    "Write five prompt ideas for a moody cyberpunk street scene",
    "Turn this into a detailed image prompt: a girl reading in a treehouse",
)

/**
 * The Chat tab: conversations with the studio's assistant, kept forever. [onUsePrompt] sends
 * a reply's prompt to the Image tab, [onUseForThumbnail] to the Thumbnail tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenSettings: () -> Unit,
    onUsePrompt: (String) -> Unit,
    onUseForThumbnail: (String) -> Unit,
) {
    var showHistory by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val chat = vm.current
    val messages = chat?.messages.orEmpty()
    val thinking = vm.thinking != null && vm.thinking == vm.currentId
    val failure = vm.failed?.takeIf { it.first == vm.currentId }?.second

    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    LaunchedEffect(vm.currentId, messages.size, thinking) {
        val count = messages.size + (if (thinking || failure != null) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(chat?.title ?: "New chat", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { showHistory = true }) { Icon(Icons.Filled.History, contentDescription = "All chats") }
                },
                actions = {
                    IconButton(onClick = { vm.open(null) }) { Icon(Icons.Filled.EditNote, contentDescription = "New chat") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { ChatInput(vm, enabled = !thinking) },
    ) { padding ->
        if (messages.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("Let's write some prompts", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text("Describe a character or a scene, or attach a picture or a text file to work from. " +
                    "Every chat is kept.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                CHAT_SUGGESTIONS.forEach { AssistChip(onClick = { vm.draft = it }, label = { Text(it) }) }
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(messages) { m ->
                    if (m.isUser) UserMessage(m, vm)
                    else AssistantMessage(
                        m,
                        onCopy = { clipboard.setText(AnnotatedString(markdown(m.text).text)); vm.message = "Copied" },
                        onImage = { onUsePrompt(ChatViewModel.promptIn(m.text)) },
                        onThumbnail = { onUseForThumbnail(ChatViewModel.promptIn(m.text)) },
                    )
                }
                if (thinking) item { Thinking() }
                failure?.let { item { ChatFailed(it, vm::retry) } }
            }
        }
    }

    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            ListItem(
                headlineContent = { Text("New chat") },
                leadingContent = { Icon(Icons.Filled.EditNote, contentDescription = null) },
                modifier = Modifier.clickable { vm.open(null); showHistory = false },
            )
            HorizontalDivider()
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(vm.chats, key = { it.id }) { c ->
                    ListItem(
                        headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text("${c.messages.size} messages · " +
                                DateUtils.getRelativeTimeSpanString(c.updated).toString())
                        },
                        modifier = Modifier.clickable { vm.open(c.id); showHistory = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun UserMessage(m: ChatMessage, vm: ChatViewModel) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        m.attachments.forEach { a ->
            if (a.isImage) {
                picture(vm, a.name)?.let {
                    Image(it, contentDescription = "Attached picture", contentScale = ContentScale.Crop,
                        modifier = Modifier.padding(bottom = 6.dp).size(160.dp).clip(RoundedCornerShape(16.dp)))
                }
            } else {
                FileChip(a.name)
            }
        }
        if (m.text.isNotBlank()) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp),
                modifier = Modifier.widthIn(max = 320.dp)) {
                SelectionContainer {
                    Text(m.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}

/**
 * The little Markdown the model writes: **bold**, *italic*, "#" headings and "*"/"-"
 * bullets. Everything else is shown as is.
 */
private fun markdown(text: String): AnnotatedString = buildAnnotatedString {
    val inline = Regex("\\*\\*(.+?)\\*\\*|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])")
    text.lines().forEachIndexed { i, raw ->
        if (i > 0) append('\n')
        var line = raw
        var heading = false
        Regex("^\\s*#{1,6}\\s+(.*)$").find(line)?.let { line = it.groupValues[1]; heading = true }
        Regex("^(\\s*)[*-]\\s+(.*)$").find(line)?.let { line = it.groupValues[1] + "• " + it.groupValues[2] }
        val start = length
        var last = 0
        for (m in inline.findAll(line)) {
            append(line.substring(last, m.range.first))
            val bold = m.groups[1] != null
            withStyle(if (bold) SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle(fontStyle = FontStyle.Italic)) {
                append(m.groups[1]?.value ?: m.groups[2]?.value.orEmpty())
            }
            last = m.range.last + 1
        }
        append(line.substring(last))
        if (heading) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
    }
}

@Composable
private fun AssistantMessage(m: ChatMessage, onCopy: () -> Unit, onImage: () -> Unit, onThumbnail: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SelectionContainer { Text(markdown(m.text), style = MaterialTheme.typography.bodyLarge) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp)); Text("Copy")
            }
            TextButton(onClick = onImage) {
                Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp)); Text("Create image")
            }
            TextButton(onClick = onThumbnail) {
                Icon(Icons.Filled.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp)); Text("Thumbnail")
            }
        }
    }
}

@Composable
private fun Thinking() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(12.dp))
        Text("Thinking…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChatFailed(error: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.size(12.dp))
        Text(error, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, contentDescription = "Try again") }
    }
}

@Composable
private fun FileChip(name: String, onRemove: (() -> Unit)? = null) {
    InputChip(
        selected = false,
        onClick = { onRemove?.invoke() },
        label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(18.dp)) },
        trailingIcon = if (onRemove != null) {
            { Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp)) }
        } else null,
        modifier = Modifier.padding(bottom = 6.dp).widthIn(max = 260.dp),
    )
}

@Composable
private fun picture(vm: ChatViewModel, name: String): ImageBitmap? = remember(name) {
    val file = vm.imageFile(name)
    if (file.exists()) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null
}

@Composable
private fun ChatInput(vm: ChatViewModel, enabled: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.attachPicture(uri)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.attachFile(uri)
    }
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (vm.attachments.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    items(vm.attachments.toList()) { d ->
                        val picture = d.picture
                        if (picture != null) {
                            Box {
                                Image(picture.preview.asImageBitmap(), contentDescription = "Picture to send",
                                    contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                                IconButton(onClick = { vm.attachments.remove(d) }, modifier = Modifier.align(Alignment.TopEnd).size(24.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove picture", tint = Color.White)
                                }
                            }
                        } else {
                            FileChip(d.attachment.name) { vm.attachments.remove(d) }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.AttachFile, contentDescription = "Attach") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Picture") }, leadingIcon = { Icon(Icons.Filled.Image, null) }, onClick = {
                            menu = false
                            pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                        DropdownMenuItem(text = { Text("Text file") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) }, onClick = {
                            menu = false
                            pickFile.launch(arrayOf("text/*", "application/json", "application/xml"))
                        })
                    }
                }
                TextField(
                    value = vm.draft,
                    onValueChange = { vm.draft = it },
                    placeholder = { Text("Message") },
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    maxLines = 6,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                FilledIconButton(onClick = vm::send,
                    enabled = enabled && (vm.draft.isNotBlank() || vm.attachments.isNotEmpty())) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Send")
                }
            }
        }
    }
}
