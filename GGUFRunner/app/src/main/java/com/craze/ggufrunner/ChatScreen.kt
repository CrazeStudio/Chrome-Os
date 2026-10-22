package com.craze.ggufrunner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { while (true) { vm.pollRam(); delay(1500) } }
    LaunchedEffect(vm.status) {
        val s = vm.status
        if (s != null && !vm.loading) { snackbar.showSnackbar(s); vm.status = null }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GGUF Runner", fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                vm.loading -> "Loading model…"
                                vm.loadedModel != null -> vm.loadedModel!!
                                else -> "No model loaded"
                            },
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    AssistChip(onClick = { showModels = true }, label = { Text("${vm.ramMb} MB") },
                        leadingIcon = { Icon(Icons.Default.List, null, Modifier.size(16.dp)) })
                    IconButton(onClick = { vm.newChat() }) { Icon(Icons.Default.Add, "New chat") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (vm.messages.isEmpty()) {
                    EmptyState(vm, onPick = { showModels = true }, onSuggest = { vm.send(it) })
                } else {
                    val state = rememberLazyListState()
                    LazyColumn(
                        state = state, reverseLayout = true,
                        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val rev = vm.messages.asReversed()
                        items(rev, key = { it.id }) { m ->
                            MessageItem(
                                m, streaming = vm.generating && m.id == vm.messages.last().id,
                                isLast = m.id == vm.messages.last().id && !vm.generating,
                                onRegenerate = { vm.regenerate() }
                            )
                        }
                    }
                }
            }
            InputBar(
                input, { input = it }, vm.generating, vm.loading,
                onSend = { vm.send(input); input = "" }, onStop = { vm.stop() }
            )
        }
    }

    if (showSettings) SettingsSheet(vm, onDismiss = { showSettings = false })
    if (showModels) ModelsSheet(vm, onDismiss = { showModels = false })
}

@Composable
private fun EmptyState(vm: ChatViewModel, onPick: () -> Unit, onSuggest: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Run any GGUF model on-device", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("100% offline · CPU · memory-mapped · quantized KV cache", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.alpha(0.7f))
        Spacer(Modifier.height(20.dp))
        if (vm.loadedModel == null && !vm.loading) {
            Button(onClick = onPick) { Text(if (vm.models.isEmpty()) "Import a .gguf model" else "Choose a model") }
        } else if (vm.loading) {
            CircularProgressIndicator()
        } else {
            listOf("Explain quantum computing simply", "Write a Python function to merge intervals", "Give me 5 ideas for a weekend project").forEach {
                SuggestionChip(onClick = { onSuggest(it) }, label = { Text(it) }, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String, onChange: (String) -> Unit, generating: Boolean, loading: Boolean,
    onSend: () -> Unit, onStop: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = value, onValueChange = onChange, modifier = Modifier.weight(1f),
                placeholder = { Text("Message") }, maxLines = 5, shape = RoundedCornerShape(24.dp), enabled = !loading
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { if (generating) onStop() else onSend() }, enabled = generating || (value.isNotBlank() && !loading)) {
                if (generating) Box(Modifier.size(14.dp).background(LocalContentColor.current, RoundedCornerShape(3.dp)))
                else Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

@Composable
private fun MessageItem(m: ChatMessage, streaming: Boolean, isLast: Boolean, onRegenerate: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    if (m.role == "user") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)) {
                SelectionContainer { Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }
        return
    }
    val p = remember(m.text) { parseThinking(m.text) }
    Column(Modifier.fillMaxWidth()) {
        if (p.thinking != null) ThinkingCard(p.thinking, p.thinkDone, m.thinkingMs)
        if (p.answer.isNotEmpty()) {
            SelectionContainer { RichText(p.answer) }
        } else if (streaming && (p.thinking == null || p.thinkDone)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Processing prompt…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.alpha(0.7f))
            }
        }
        if (!streaming && m.tokens > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        append("%d tok".format(m.tokens))
                        if (m.tps > 0) append(" · %.1f tok/s".format(m.tps))
                        if (m.prefillMs > 0) append(" · prefill %.1fs".format(m.prefillMs / 1000f))
                    },
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.6f)
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { clipboard.setText(AnnotatedString(p.answer.ifEmpty { m.text })) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Copy", fontSize = 12.sp) }
                if (isLast) TextButton(onClick = onRegenerate, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Retry", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ThinkingCard(text: String, done: Boolean, ms: Long) {
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: !done
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { userExpanded = !expanded }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!done) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (!done) "Thinking…" else if (ms > 0) "Thought for %.1fs".format(ms / 1000f) else "Thought",
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
                )
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(0.8f))
                }
            }
        }
    }
}

// ---------- tiny markdown renderer (code fences, **bold**, `code`, # headings) ----------
@Composable
private fun RichText(text: String) {
    val parts = remember(text) { splitFences(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEach { (isCode, body) ->
            if (isCode) {
                Surface(color = codeBg, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        body.trimEnd(), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)
                    )
                }
            } else if (body.isNotBlank()) {
                Text(inlineMd(body.trim('\n'), codeBg), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun splitFences(s: String): List<Pair<Boolean, String>> {
    val out = ArrayList<Pair<Boolean, String>>()
    val cur = StringBuilder()
    var code = false
    for (line in s.split("\n")) {
        if (line.trimStart().startsWith("```")) {
            if (cur.isNotEmpty() || code) out.add(code to cur.toString())
            cur.setLength(0); code = !code
        } else cur.append(line).append('\n')
    }
    if (cur.isNotEmpty()) out.add(code to cur.toString())
    return out
}

private fun inlineMd(s: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    s.split("\n").forEachIndexed { li, rawLine ->
        if (li > 0) append("\n")
        var line = rawLine
        val head = Regex("^#{1,6}\\s+").find(line)
        val boldLine = head != null
        if (head != null) line = line.removePrefix(head.value)
        var i = 0
        fun emit(t: String, st: SpanStyle? = null) {
            val base = if (boldLine) SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp) else SpanStyle()
            withStyle(base.merge(st ?: SpanStyle())) { append(t) }
        }
        while (i < line.length) {
            if (line.startsWith("**", i)) {
                val e = line.indexOf("**", i + 2)
                if (e > 0) { emit(line.substring(i + 2, e), SpanStyle(fontWeight = FontWeight.Bold)); i = e + 2; continue }
            }
            if (line[i] == '`') {
                val e = line.indexOf('`', i + 1)
                if (e > 0) { emit(line.substring(i + 1, e), SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)); i = e + 1; continue }
            }
            emit(line[i].toString()); i++
        }
    }
}
