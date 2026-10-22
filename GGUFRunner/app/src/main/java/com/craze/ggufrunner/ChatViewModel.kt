package com.craze.ggufrunner

import android.app.Application
import android.net.Uri
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val bridge = LlamaBridge()
    private val engine = Executors.newSingleThreadExecutor { r -> Thread(r, "llama-engine").apply { priority = Thread.MAX_PRIORITY } }.asCoroutineDispatcher()
    private val main = Handler(Looper.getMainLooper())
    private fun ui(b: () -> Unit) { main.post(b) }

    private val settingsStore = SettingsStore(app)
    private val modelStore = ModelStore(app)
    private val chatStore = ChatStore(app)

    var settings by mutableStateOf(settingsStore.load()); private set
    val messages = mutableStateListOf<ChatMessage>()
    var models by mutableStateOf(listOf<ModelFile>()); private set
    var loadedModel by mutableStateOf<String?>(null); private set
    var modelInfo by mutableStateOf<ModelInfo?>(null); private set
    var status by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false); private set
    var generating by mutableStateOf(false); private set
    var importProgress by mutableStateOf<Float?>(null); private set
    var ramMb by mutableStateOf(0); private set
    var systemInfo by mutableStateOf(""); private set

    data class ModelInfo(val desc: String, val sizeBytes: Long, val params: Long, val ctxTrain: Int, val ctxUsed: Int)

    private var nextId = System.currentTimeMillis()
    private fun newId() = nextId++

    init {
        messages.addAll(chatStore.load())
        refreshModels()
        viewModelScope.launch(engine) { val si = bridge.nativeSystemInfo(); ui { systemInfo = si } }
        settingsStore.lastModel?.let { n -> if (models.any { it.name == n }) loadModel(n) }
    }

    fun refreshModels() { models = modelStore.list() }
    fun pollRam() { ramMb = (Debug.getPss() / 1024L).toInt() }

    // ---------- settings ----------
    fun applySettings(s: Settings) {
        val old = settings
        settings = s
        settingsStore.save(s)
        if (s.engineChanged(old)) loadedModel?.let { loadModel(it) }
    }

    // ---------- models ----------
    fun loadModel(name: String) {
        val m = models.firstOrNull { it.name == name } ?: return
        if (generating) return
        loading = true
        status = "Loading ${m.name}…"
        val s = settings
        viewModelScope.launch(engine) {
            val err = bridge.nativeLoad(m.path, s.nCtx, s.threads, s.batch, s.kvType, s.useMmap, s.flashAttn)
            val info = if (err == null) bridge.nativeInfo().split("|").let {
                if (it.size >= 5) ModelInfo(it[0], it[1].toLong(), it[2].toLong(), it[3].toInt(), it[4].toInt()) else null
            } else null
            ui {
                loading = false
                if (err == null) {
                    loadedModel = name; modelInfo = info; status = null; settingsStore.lastModel = name
                } else {
                    loadedModel = null; modelInfo = null; status = err
                }
            }
        }
    }

    fun unloadModel() {
        if (generating) stop()
        viewModelScope.launch(engine) { bridge.nativeUnload(); ui { loadedModel = null; modelInfo = null } }
    }

    fun deleteModel(name: String) {
        if (loadedModel == name) unloadModel()
        viewModelScope.launch(Dispatchers.IO) { modelStore.delete(name); ui { refreshModels() } }
    }

    fun importModel(uri: Uri) {
        if (importProgress != null) return
        importProgress = 0f
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = modelStore.import(uri) { p -> ui { importProgress = p } }
                ui { importProgress = null; refreshModels(); status = "Imported $name" }
            } catch (e: Exception) {
                ui { importProgress = null; status = "Import failed: ${e.message}" }
            }
        }
    }

    // ---------- chat ----------
    fun newChat() {
        if (generating) stop()
        messages.clear()
        chatStore.save(messages)
        viewModelScope.launch(engine) { bridge.nativeResetCache() }
    }

    fun stop() { bridge.nativeStop() }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || generating) return
        if (loadedModel == null) { status = "Load a model first (tap the model chip)."; return }
        messages.add(ChatMessage(newId(), "user", t))
        val aId = newId()
        messages.add(ChatMessage(aId, "assistant", ""))
        generate(aId)
    }

    fun regenerate() {
        if (generating || loadedModel == null) return
        val i = messages.indexOfLast { it.role == "assistant" }
        if (i < 0 || i != messages.lastIndex) return
        messages.removeAt(i)
        val aId = newId()
        messages.add(ChatMessage(aId, "assistant", ""))
        generate(aId)
    }

    private fun update(id: Long, f: (ChatMessage) -> ChatMessage) {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages[i] = f(messages[i])
    }

    private fun generate(aId: Long) {
        generating = true
        status = null
        val s = settings
        val modelName = loadedModel.orEmpty()
        val snapshot = messages.filter { it.id != aId }
        viewModelScope.launch(engine) {
            var hist = snapshot
            val sb = StringBuilder()
            var tStart = 0L; var tFirst = 0L; var thinkEnd = 0L; var lastUi = 0L
            var rc = 0
            while (true) {
                val roles = ArrayList<String>(); val contents = ArrayList<ByteArray>()
                if (s.system.isNotBlank()) { roles += "system"; contents += s.system.toByteArray() }
                hist.forEachIndexed { i, m ->
                    roles += m.role
                    var c = if (m.role == "assistant") parseThinking(m.text).answer else m.text
                    // Qwen3 "soft switch" to disable reasoning
                    if (i == hist.lastIndex && m.role == "user" && !s.thinking && modelName.contains("qwen3", true)) c += " /no_think"
                    contents += c.toByteArray()
                }
                sb.setLength(0); tFirst = 0L; thinkEnd = 0L; lastUi = 0L
                tStart = SystemClock.elapsedRealtime()
                val cb = TokenCallback { bytes ->
                    val now = SystemClock.elapsedRealtime()
                    if (tFirst == 0L) tFirst = now
                    sb.append(String(bytes, Charsets.UTF_8))
                    if (now - lastUi >= 60) {
                        lastUi = now
                        val snap = sb.toString()
                        if (thinkEnd == 0L && snap.contains("</think>")) thinkEnd = now
                        val tm = if (thinkEnd > 0) thinkEnd - tFirst else 0L
                        ui { update(aId) { it.copy(text = snap, thinkingMs = tm) } }
                    }
                    true
                }
                rc = bridge.nativeGenerate(
                    roles.toTypedArray(), contents.toTypedArray(), s.maxTokens,
                    s.temp, s.topK, s.topP, s.minP, s.repeat, -1, cb
                )
                if (rc == -2 && hist.size > 2) { hist = hist.drop(2); continue }   // context full: forget oldest turn
                break
            }
            val end = SystemClock.elapsedRealtime()
            val finalText = sb.toString()
            if (thinkEnd == 0L && finalText.contains("</think>")) thinkEnd = end
            val genMs = if (tFirst > 0) end - tFirst else 0L
            val tps = if (rc > 1 && genMs > 0) (rc - 1) * 1000f / genMs else 0f
            val prefill = if (tFirst > 0) tFirst - tStart else 0L
            val thinkMs = if (thinkEnd > 0 && tFirst > 0) thinkEnd - tFirst else 0L
            ui {
                update(aId) {
                    it.copy(
                        text = when {
                            rc == -2 -> "⚠ The message is too long for the context window. Increase “Context size” in Settings."
                            rc == -3 -> "⚠ Decode error. Try reloading the model or a smaller context."
                            rc == -4 -> "⚠ Could not apply the chat template / tokenize."
                            rc == -1 -> "⚠ No model loaded."
                            else -> finalText
                        },
                        thinkingMs = thinkMs, tokens = maxOf(rc, 0), tps = tps, prefillMs = prefill
                    )
                }
                generating = false
                chatStore.save(messages)
            }
        }
    }

    override fun onCleared() {
        bridge.nativeStop()
        bridge.nativeUnload()
        super.onCleared()
    }
}
