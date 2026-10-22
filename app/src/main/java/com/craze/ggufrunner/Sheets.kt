package com.craze.ggufrunner

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private fun gb(b: Long) = if (b >= 1L shl 30) "%.2f GB".format(b / 1073741824.0) else "%d MB".format(b shr 20)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importModel(it) } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Models", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            vm.modelInfo?.let {
                Card { Column(Modifier.padding(12.dp)) {
                    Text(it.desc, fontWeight = FontWeight.Medium)
                    Text("${gb(it.sizeBytes)} · ${"%.2f".format(it.params / 1e9)}B params · ctx ${it.ctxUsed}/${it.ctxTrain}", style = MaterialTheme.typography.bodySmall)
                    Text("App memory now: ${vm.ramMb} MB (mmap'd weights are reclaimable by the OS)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(0.7f))
                } }
            }
            vm.importProgress?.let {
                Text("Importing… ${(it * 100).roundToInt()}%")
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = vm.importProgress == null, modifier = Modifier.fillMaxWidth()) { Text("Import .gguf from storage") }
            if (vm.models.isEmpty()) Text("No models yet. Tip: Q4_0 / Q4_K_M quants of 0.5–4B models are the fastest on phones.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(0.7f))
            vm.models.forEach { m ->
                val active = vm.loadedModel == m.name
                Card(colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontWeight = FontWeight.Medium, maxLines = 2)
                            Text(gb(m.sizeBytes), style = MaterialTheme.typography.bodySmall)
                        }
                        if (active) TextButton(onClick = { vm.unloadModel() }) { Text("Unload") }
                        else TextButton(onClick = { vm.loadModel(m.name); onDismiss() }, enabled = !vm.loading && !vm.generating) { Text("Load") }
                        IconButton(onClick = { vm.deleteModel(m.name) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    var d by remember { mutableStateOf(vm.settings) }
    val cores = Runtime.getRuntime().availableProcessors()
    ModalBottomSheet(onDismissRequest = { vm.applySettings(d); onDismiss() }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            Section("Chat")
            SwitchRow("Thinking mode", "Show reasoning in a collapsible card. Off adds /no_think for Qwen3.", d.thinking) { d = d.copy(thinking = it) }
            OutlinedTextField(d.system, { d = d.copy(system = it) }, label = { Text("System prompt") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)

            Section("Sampling")
            SliderRow("Temperature", d.temp, 0f..1.5f, 29, "%.2f") { d = d.copy(temp = it) }
            SliderRow("Top-K", d.topK.toFloat(), 0f..100f, 19, "%.0f") { d = d.copy(topK = it.roundToInt()) }
            SliderRow("Top-P", d.topP, 0.1f..1f, 17, "%.2f") { d = d.copy(topP = it) }
            SliderRow("Min-P", d.minP, 0f..0.3f, 29, "%.2f") { d = d.copy(minP = it) }
            SliderRow("Repeat penalty", d.repeat, 1f..1.5f, 24, "%.2f") { d = d.copy(repeat = it) }
            SliderRow("Max new tokens", d.maxTokens.toFloat(), 128f..4096f, 30, "%.0f") { d = d.copy(maxTokens = (it / 128f).roundToInt() * 128) }

            Section("Performance & memory (reloads the model)")
            ChipRow("Context size", listOf("1024" to 1024, "2048" to 2048, "4096" to 4096, "8192" to 8192), d.nCtx) { d = d.copy(nCtx = it) }
            SliderRow("CPU threads (device has $cores)", d.threads.toFloat(), 1f..cores.toFloat(), (cores - 2).coerceAtLeast(0), "%.0f") { d = d.copy(threads = it.roundToInt()) }
            ChipRow("Batch size", listOf("128" to 128, "256" to 256, "512" to 512), d.batch) { d = d.copy(batch = it) }
            ChipRow("KV cache", listOf("F16 (best)" to 0, "Q8 (½ RAM)" to 1, "Q4 (¼ RAM)" to 2), d.kvType) { d = d.copy(kvType = it) }
            SwitchRow("Flash attention", "Faster + lower memory. Required for quantized KV.", d.flashAttn) { d = d.copy(flashAttn = it) }
            SwitchRow("Memory-map model (mmap)", "Keep on: weights are paged in lazily and can be evicted instead of killing the app.", d.useMmap) { d = d.copy(useMmap = it) }

            Section("Device")
            Text(vm.systemInfo.take(240), style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(0.6f))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { d = Settings() }, modifier = Modifier.weight(1f)) { Text("Defaults") }
                Button(onClick = { vm.applySettings(d); onDismiss() }, modifier = Modifier.weight(1f)) { Text("Save") }
            }
        }
    }
}

@Composable private fun Section(t: String) {
    Spacer(Modifier.height(8.dp))
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, fmt: String, onChange: (Float) -> Unit) {
    Column {
        Row { Text(label, Modifier.weight(1f)); Text(fmt.format(value), fontWeight = FontWeight.Medium) }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, v: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); Text(sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(0.65f)) }
        Switch(v, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChipRow(label: String, opts: List<Pair<String, Int>>, sel: Int, onSel: (Int) -> Unit) {
    Column {
        Text(label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            opts.forEach { (t, v) -> FilterChip(selected = sel == v, onClick = { onSel(v) }, label = { Text(t) }) }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Saved chats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Chats are stored on this device. Up to 100 recent conversations are kept.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(0.7f))
            if (vm.savedChats.isEmpty()) {
                Text("No saved chats yet. Start a conversation and it will appear here.", modifier = Modifier.padding(vertical = 16.dp))
            } else {
                vm.savedChats.forEach { chat ->
                    Card {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f).clickable {
                                vm.openChat(chat.id)
                                onDismiss()
                            }.padding(vertical = 4.dp)) {
                                Text(chat.title.ifBlank { "New chat" }, maxLines = 2, fontWeight = FontWeight.Medium)
                                Text("${chat.messages.size} messages · " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(chat.updatedAt)),
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(0.65f))
                            }
                            IconButton(onClick = { vm.deleteChat(chat.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete saved chat")
                            }
                        }
                    }
                }
            }
        }
    }
}
