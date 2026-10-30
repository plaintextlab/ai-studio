package com.plaintextlab.aistudio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val SUGGESTIONS = listOf(
    "A lighthouse on a cliff at sunset, realistic photo",
    "A cozy reading nook with rain on the window",
    "Portrait of an old fisherman, soft window light",
)

private val THUMBNAIL_SUGGESTIONS = listOf(
    "Cooking the spiciest ramen in the world" to "WORLDS HOTTEST RAMEN",
    "Travel vlog: a night in Tokyo on a budget" to "TOKYO ON $20",
    "Testing a new phone camera against a DSLR" to "PHONE VS PRO",
)

/** The Image tab (Quick image) and the Thumbnail tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(vm: StudioViewModel, mode: Mode, onOpenSettings: () -> Unit, onEditInImageTab: () -> Unit) {
    val thumbnail = mode == Mode.Thumbnail
    val turns = vm.turnsFor(mode)
    var viewing by remember { mutableStateOf<Result?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val editResult = { r: Result -> vm.editResult(r); if (thumbnail) onEditInImageTab() }
    val listState = rememberLazyListState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.attach(uri)
    }
    val pickFace = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setFace(uri)
    }

    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }
    // Open at the newest request, and follow new ones (not older ones brought in from the PC).
    var scrolledOnce by remember { mutableStateOf(false) }
    LaunchedEffect(turns.lastOrNull()?.id) {
        if (turns.isEmpty()) return@LaunchedEffect
        if (scrolledOnce) listState.animateScrollToItem(turns.size - 1) else listState.scrollToItem(turns.size - 1)
        scrolledOnce = true
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (thumbnail) "YouTube thumbnail" else "AI Studio") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            InputBar(
                vm = vm,
                mode = mode,
                onPick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onPickFace = { pickFace.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onSend = {
                    if (thumbnail) vm.sendThumbnail(vm.thumbnailIdea, vm.thumbnailText).also { vm.thumbnailIdea = "" }
                    else vm.send(vm.imageDraft).also { vm.imageDraft = "" }
                },
            )
        },
    ) { padding ->
        if (turns.isEmpty()) {
            EmptyState(Modifier.padding(padding), mode) { idea, text ->
                if (thumbnail) { vm.thumbnailIdea = idea; vm.thumbnailText = text } else vm.imageDraft = idea
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(turns, key = { it.id }) { turn ->
                    TurnItem(
                        turn = turn,
                        onOpen = { viewing = it },
                        onSave = vm::save,
                        onEdit = editResult,
                        onRetry = { vm.retry(turn) },
                        onChooseFace = { vm.chooseFace(turn, it) },
                        onAgain = { vm.again(turn) },
                    )
                }
            }
        }
    }

    viewing?.let { result ->
        Viewer(result, onClose = { viewing = null }, onSave = { vm.save(result) },
            onEdit = { editResult(result); viewing = null })
    }
}

@Composable
private fun EmptyState(modifier: Modifier, mode: Mode, onSuggestion: (String, String) -> Unit) {
    val thumbnail = mode == Mode.Thumbnail
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(if (thumbnail) Icons.Filled.Movie else Icons.Filled.AutoAwesome, contentDescription = null,
            modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(if (thumbnail) "What is your video about?" else "What do you want to create?",
            style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            if (thumbnail) "Describe the video and type the words for the thumbnail. They come out exactly as typed."
            else "Describe an image, or add a photo and say what to change.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        if (thumbnail) {
            THUMBNAIL_SUGGESTIONS.forEach { (idea, text) ->
                AssistChip(onClick = { onSuggestion(idea, text) }, label = { Text("$idea: \"$text\"") })
            }
        } else {
            SUGGESTIONS.forEach { AssistChip(onClick = { onSuggestion(it, "") }, label = { Text(it) }) }
        }
    }
}

@Composable
private fun TurnItem(
    turn: Turn,
    onOpen: (Result) -> Unit,
    onSave: (Result) -> Unit,
    onEdit: (Result) -> Unit,
    onRetry: () -> Unit,
    onChooseFace: (Int) -> Unit,
    onAgain: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        // The request, on the right like a sent message.
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.align(Alignment.End).widthIn(max = 320.dp),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                turn.input?.let {
                    Image(it.preview.asImageBitmap(), contentDescription = "Picture to edit",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                    Spacer(Modifier.size(10.dp))
                }
                // Long prompts show 6 lines: tap to read them all.
                var expanded by remember { mutableStateOf(false) }
                Text(if (turn.text.isNotBlank()) "${turn.prompt}\n\"${turn.text}\"" else turn.prompt,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).clickable { expanded = !expanded })
                turn.face?.let {
                    Spacer(Modifier.size(10.dp))
                    Image(it.preview.asImageBitmap(), contentDescription = "Face used",
                        contentScale = ContentScale.Crop, modifier = Modifier.size(28.dp).clip(CircleShape))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        when (turn.state) {
            Turn.State.Waiting, Turn.State.Working ->
                turn.question?.let { FaceChoice(it, onChooseFace) } ?: Pending(turn)
            Turn.State.Failed -> Failed(turn.error.orEmpty(), onRetry)
            Turn.State.Done -> turn.results.firstOrNull()?.let { first ->
                if (turn.results.size == 1) {
                    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        ResultImage(first, turn.shape, turn.prompt, Modifier.fillMaxWidth()) { onOpen(first) }
                    }
                } else {
                    // Several images: two per row; tap one to save or edit it.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        turn.results.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { r ->
                                    Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                                        ResultImage(r, turn.shape, turn.prompt, Modifier.fillMaxWidth()) { onOpen(r) }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (turn.results.size == 1) {
                        TextButton(onClick = { onSave(first) }) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp)); Text("Save")
                        }
                        TextButton(onClick = { onEdit(first) }) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp)); Text("Edit this")
                        }
                    } else {
                        Text("Tap a picture to save or edit it", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                    }
                    IconButton(onClick = onAgain) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Again: same prompt, new images")
                    }
                    Spacer(Modifier.weight(1f))
                    first.model?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp))
                    }
                }
                // The enhanced prompt, when the enhancer rewrote it: tap to read it all.
                first.promptUsed?.takeIf { it.isNotBlank() && it != turn.prompt }?.let { used ->
                    var expanded by remember { mutableStateOf(false) }
                    Text(
                        "Enhanced prompt: $used",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp).clickable { expanded = !expanded },
                    )
                }
            }
        }
    }
}

@Composable
private fun Pending(turn: Turn) {
    val ratio = turn.input?.preview?.let { it.width.toFloat() / it.height }
        ?: (turn.shape.width.toFloat() / turn.shape.height)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
    ) {
        Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    turn.state == Turn.State.Waiting -> "Waiting for ComfyUI…"
                    turn.mode == Mode.Thumbnail && turn.count > 1 -> "Designing ${turn.count} thumbnails…"
                    turn.mode == Mode.Thumbnail -> "Designing your thumbnail…"
                    turn.input != null && turn.count > 1 -> "Making ${turn.count} edits…"
                    turn.input != null -> "Editing…"
                    turn.count > 1 -> "Creating ${turn.count} images…"
                    else -> "Creating…"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A result, loaded from the history in the background (a placeholder of [shape] till then). */
@Composable
private fun ResultImage(result: Result, shape: Shape, description: String, modifier: Modifier, onClick: () -> Unit) {
    val bitmap = rememberBitmap(result.file)
    if (bitmap == null) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = modifier.aspectRatio(shape.width.toFloat() / shape.height)) {}
    } else {
        Image(
            bitmap.asImageBitmap(), contentDescription = description, contentScale = ContentScale.FillWidth,
            modifier = modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height).clickable(onClick = onClick),
        )
    }
}

@Composable
private fun rememberBitmap(file: File, maxSide: Int = 1080): android.graphics.Bitmap? {
    val bitmap by produceState<android.graphics.Bitmap?>(null, file, maxSide) {
        value = withContext(Dispatchers.IO) { runCatching { Images.load(file, maxSide) }.getOrNull() }
    }
    return bitmap
}

/** The PC found several faces in a picture: which one gets the chosen face? */
@Composable
private fun FaceChoice(question: FaceQuestion, onChoose: (Int) -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Which face should be replaced?" +
                    if (question.pictures > 1) " (picture ${question.picture} of ${question.pictures})" else "",
                style = MaterialTheme.typography.titleMedium,
            )
            Image(
                question.image.asImageBitmap(), contentDescription = "The picture, with its faces numbered",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
                    .aspectRatio(question.image.width.toFloat() / question.image.height)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                question.faces.forEachIndexed { i, face ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onChoose(i + 1) }.padding(4.dp),
                    ) {
                        Image(face.asImageBitmap(), contentDescription = "Face ${i + 1}", contentScale = ContentScale.Crop,
                            modifier = Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)))
                        Spacer(Modifier.height(4.dp))
                        Text("Face ${i + 1}", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            TextButton(onClick = { onChoose(0) }, modifier = Modifier.align(Alignment.End)) {
                Text("Keep all faces")
            }
        }
    }
}

@Composable
private fun Failed(error: String, onRetry: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.size(12.dp))
            Text(error, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, contentDescription = "Try again") }
        }
    }
}

@Composable
private fun InputBar(
    vm: StudioViewModel,
    mode: Mode,
    onPick: () -> Unit,
    onPickFace: () -> Unit,
    onSend: () -> Unit,
) {
    val thumbnail = mode == Mode.Thumbnail
    val text = if (thumbnail) vm.thumbnailIdea else vm.imageDraft
    val onText = { t: String -> if (thumbnail) vm.thumbnailIdea = t else vm.imageDraft = t }
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            vm.face?.let { face ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Image(face.preview.asImageBitmap(), contentDescription = "Face to use",
                        contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(CircleShape))
                    Spacer(Modifier.size(12.dp))
                    Text("This face goes on the person (you pick which when there are several)",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = vm::clearFace) {
                        Icon(Icons.Filled.Close, contentDescription = "Stop using this face")
                    }
                }
            }
            val attachment = if (thumbnail) null else vm.attachment
            if (thumbnail) {
                TextField(
                    value = vm.thumbnailText,
                    onValueChange = { vm.thumbnailText = it },
                    placeholder = { Text("Words on the thumbnail (optional)") },
                    leadingIcon = { Icon(Icons.Filled.TextFields, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            } else if (attachment != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Image(attachment.preview.asImageBitmap(), contentDescription = "Picture to edit",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.size(12.dp))
                    Text("Editing this picture", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { vm.attachment = null }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove picture")
                    }
                }
            }
            // Shape (new images only) and how many images per run.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            ) {
                if (!thumbnail && attachment == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        Shape.entries.filter { it != Shape.Thumbnail }.forEach { s ->
                            FilterChip(selected = vm.shape == s, onClick = { vm.shape = s }, label = { Text(s.label) })
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                CountChip(vm.count, vm::updateCount)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!thumbnail) {
                    IconButton(onClick = onPick) {
                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Add a picture to edit")
                    }
                }
                IconButton(onClick = onPickFace) {
                    Icon(Icons.Filled.Face, contentDescription = "Choose a face to swap in")
                }
                TextField(
                    value = text,
                    onValueChange = onText,
                    placeholder = {
                        Text(when {
                            thumbnail -> "What is the video about?"
                            attachment != null -> "Describe the change"
                            else -> "Describe an image"
                        })
                    },
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    maxLines = 5,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                FilledIconButton(onClick = onSend, enabled = text.isNotBlank() && vm.server.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Create")
                }
            }
        }
    }
}

/** "2 images": how many images each request makes. */
@Composable
private fun CountChip(count: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(if (count == 1) "1 image" else "$count images") },
            leadingIcon = { Icon(Icons.Filled.Collections, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            IMAGE_COUNTS.forEach { n ->
                DropdownMenuItem(
                    text = { Text(if (n == 1) "1 image per run" else "$n images per run") },
                    onClick = { onChange(n); open = false },
                )
            }
        }
    }
}

@Composable
private fun Viewer(result: Result, onClose: () -> Unit, onSave: () -> Unit, onEdit: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Box {
                rememberBitmap(result.file, 2048)?.let {
                    Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize())
                }
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp),
                ) {
                    FilledTonalButton(onClick = onSave) {
                        Icon(Icons.Filled.Download, contentDescription = null); Spacer(Modifier.size(8.dp)); Text("Save")
                    }
                    FilledTonalButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = null); Spacer(Modifier.size(8.dp)); Text("Edit this")
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(vm: StudioViewModel, onClose: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(vm.server) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("ComfyUI address") },
        text = {
            Column {
                Text("The address ComfyUI shows when started with --lan, e.g. http://192.168.0.10:8188",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = url, onValueChange = { url = it; status = null }, singleLine = true,
                    placeholder = { Text("192.168.0.10:8188") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { status = "Checking…"; scope.launch { status = vm.testServer(url) } }) {
                    Text("Test connection")
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Enhance prompts", style = MaterialTheme.typography.bodyLarge)
                        Text("Rewrite your prompt in detail before creating or editing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = vm.enhance, onCheckedChange = vm::updateEnhance)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.updateServer(url); onClose() }, enabled = url.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
