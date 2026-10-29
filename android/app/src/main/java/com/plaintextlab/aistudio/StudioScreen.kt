package com.plaintextlab.aistudio

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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
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
import kotlinx.coroutines.launch

private val SUGGESTIONS = listOf(
    "A lighthouse on a cliff at sunset, realistic photo",
    "A cozy reading nook with rain on the window",
    "Portrait of an old fisherman, soft window light",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(vm: StudioViewModel) {
    var showSettings by rememberSaveable { mutableStateOf(vm.server.isBlank()) }
    var viewing by remember { mutableStateOf<Result?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
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
    LaunchedEffect(vm.turns.size) {
        if (vm.turns.isNotEmpty()) listState.animateScrollToItem(vm.turns.size - 1)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("AI Studio") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            InputBar(
                vm = vm,
                text = text,
                onText = { text = it },
                onPick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onPickFace = { pickFace.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onSend = { vm.send(text); text = "" },
            )
        },
    ) { padding ->
        if (vm.turns.isEmpty()) {
            EmptyState(Modifier.padding(padding)) { text = it }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(vm.turns, key = { it.id }) { turn ->
                    TurnItem(
                        turn = turn,
                        onOpen = { viewing = it },
                        onSave = vm::save,
                        onEdit = vm::editResult,
                        onRetry = { vm.retry(turn) },
                    )
                }
            }
        }
    }

    viewing?.let { result ->
        Viewer(result, onClose = { viewing = null }, onSave = { vm.save(result) },
            onEdit = { vm.editResult(result); viewing = null })
    }
    if (showSettings) {
        SettingsDialog(vm, onClose = { showSettings = false })
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onSuggestion: (String) -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("What do you want to create?", style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Describe an image, or add a photo and say what to change.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        SUGGESTIONS.forEach {
            AssistChip(onClick = { onSuggestion(it) }, label = { Text(it) })
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
                Text(turn.prompt, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                turn.face?.let {
                    Spacer(Modifier.size(10.dp))
                    Image(it.preview.asImageBitmap(), contentDescription = "Face used",
                        contentScale = ContentScale.Crop, modifier = Modifier.size(28.dp).clip(CircleShape))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        when (turn.state) {
            Turn.State.Waiting, Turn.State.Working -> Pending(turn)
            Turn.State.Failed -> Failed(turn.error.orEmpty(), onRetry)
            Turn.State.Done -> turn.result?.let { result ->
                Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Image(
                        result.bitmap.asImageBitmap(), contentDescription = turn.prompt,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                            .aspectRatio(result.bitmap.width.toFloat() / result.bitmap.height)
                            .clickable { onOpen(result) },
                    )
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onSave(result) }) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp)); Text("Save")
                    }
                    TextButton(onClick = { onEdit(result) }) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp)); Text("Edit this")
                    }
                    Spacer(Modifier.weight(1f))
                    result.model?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp))
                    }
                }
                // The enhanced prompt, when the enhancer rewrote it: tap to read it all.
                result.promptUsed?.takeIf { it.isNotBlank() && it != turn.prompt }?.let { used ->
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
                    turn.input != null -> "Editing…"
                    else -> "Creating…"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    text: String,
    onText: (String) -> Unit,
    onPick: () -> Unit,
    onPickFace: () -> Unit,
    onSend: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            vm.face?.let { face ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Image(face.preview.asImageBitmap(), contentDescription = "Face to use",
                        contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(CircleShape))
                    Spacer(Modifier.size(12.dp))
                    Text("This face goes on the main person", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = vm::clearFace) {
                        Icon(Icons.Filled.Close, contentDescription = "Stop using this face")
                    }
                }
            }
            val attachment = vm.attachment
            if (attachment != null) {
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
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    Shape.entries.forEach { s ->
                        FilterChip(selected = vm.shape == s, onClick = { vm.shape = s }, label = { Text(s.label) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPick) {
                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Add a picture to edit")
                }
                IconButton(onClick = onPickFace) {
                    Icon(Icons.Filled.Face, contentDescription = "Choose a face to swap in")
                }
                TextField(
                    value = text,
                    onValueChange = onText,
                    placeholder = { Text(if (attachment != null) "Describe the change" else "Describe an image") },
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

@Composable
private fun Viewer(result: Result, onClose: () -> Unit, onSave: () -> Unit, onEdit: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Box {
                Image(result.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize())
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
private fun SettingsDialog(vm: StudioViewModel, onClose: () -> Unit) {
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
