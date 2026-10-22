package com.craze.ggufrunner

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ---------------- Settings ----------------
data class Settings(
    val nCtx: Int = 2048,
    val threads: Int = defaultThreads(),
    val batch: Int = 256,
    val kvType: Int = 1,            // 0=f16 1=q8_0 2=q4_0
    val flashAttn: Boolean = true,
    val useMmap: Boolean = true,
    val thinking: Boolean = true,
    val temp: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val minP: Float = 0.05f,
    val repeat: Float = 1.0f,
    val maxTokens: Int = 1024,
    val system: String = "You are a helpful assistant."
) {
    /** True if a model reload is needed when moving from [o] to this. */
    fun engineChanged(o: Settings) =
        nCtx != o.nCtx || threads != o.threads || batch != o.batch ||
            kvType != o.kvType || flashAttn != o.flashAttn || useMmap != o.useMmap
}

fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)

class SettingsStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var lastModel: String?
        get() = p.getString("lastModel", null)
        set(v) { p.edit().putString("lastModel", v).apply() }

    fun load(): Settings {
        val d = Settings()
        return Settings(
            nCtx = p.getInt("nCtx", d.nCtx), threads = p.getInt("threads", d.threads),
            batch = p.getInt("batch", d.batch), kvType = p.getInt("kvType", d.kvType),
            flashAttn = p.getBoolean("flash", d.flashAttn), useMmap = p.getBoolean("mmap", d.useMmap),
            thinking = p.getBoolean("thinking", d.thinking), temp = p.getFloat("temp", d.temp),
            topK = p.getInt("topK", d.topK), topP = p.getFloat("topP", d.topP),
            minP = p.getFloat("minP", d.minP), repeat = p.getFloat("repeat", d.repeat),
            maxTokens = p.getInt("maxTokens", d.maxTokens), system = p.getString("system", d.system) ?: d.system
        )
    }

    fun save(s: Settings) {
        p.edit().putInt("nCtx", s.nCtx).putInt("threads", s.threads).putInt("batch", s.batch)
            .putInt("kvType", s.kvType).putBoolean("flash", s.flashAttn).putBoolean("mmap", s.useMmap)
            .putBoolean("thinking", s.thinking).putFloat("temp", s.temp).putInt("topK", s.topK)
            .putFloat("topP", s.topP).putFloat("minP", s.minP).putFloat("repeat", s.repeat)
            .putInt("maxTokens", s.maxTokens).putString("system", s.system).apply()
    }
}

// ---------------- Models ----------------
data class ModelFile(val name: String, val path: String, val sizeBytes: Long)

class ModelStore(private val ctx: Context) {
    private val dir get() = File(ctx.filesDir, "models").apply { mkdirs() }

    fun list(): List<ModelFile> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { ModelFile(it.name, it.absolutePath, it.length()) } ?: emptyList()

    fun delete(name: String) { File(dir, name).delete() }

    /** Copies the picked file into app storage (so it can be mmap'ed). Returns the stored name. */
    fun import(uri: Uri, onProgress: (Float) -> Unit): String {
        val cr = ctx.contentResolver
        var name = "model.gguf"
        var total = -1L
        cr.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) c.getString(ni)?.let { name = it }
                if (si >= 0 && !c.isNull(si)) total = c.getLong(si)
            }
        }
        name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!name.endsWith(".gguf", true)) name += ".gguf"
        val tmp = File(dir, "$name.part")
        val out = File(dir, name)
        val input = cr.openInputStream(uri) ?: throw IllegalStateException("Cannot open file")
        input.use { ins ->
            tmp.outputStream().use { os ->
                val buf = ByteArray(1 shl 20)
                var done = 0L
                var lastPct = -1
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                    }
                }
            }
        }
        val magic = tmp.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        if (String(magic, Charsets.ISO_8859_1) != "GGUF") {
            tmp.delete(); throw IllegalStateException("Not a GGUF file")
        }
        if (out.exists()) out.delete()
        tmp.renameTo(out)
        return name
    }
}

// ---------------- Chat ----------------
data class ChatMessage(
    val id: Long,
    val role: String,               // "user" | "assistant"
    val text: String,               // raw model output (may contain <think>…</think>)
    val thinkingMs: Long = 0,
    val tokens: Int = 0,
    val tps: Float = 0f,
    val prefillMs: Long = 0
)

class ChatStore(ctx: Context) {
    private val file = File(ctx.filesDir, "chat.json")

    fun save(list: List<ChatMessage>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("role", it.role).put("text", it.text)
                .put("thinkingMs", it.thinkingMs).put("tokens", it.tokens)
                .put("tps", it.tps.toDouble()).put("prefillMs", it.prefillMs))
        }
        runCatching { file.writeText(arr.toString()) }
    }

    fun load(): List<ChatMessage> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ChatMessage(o.getLong("id"), o.getString("role"), o.getString("text"),
                o.optLong("thinkingMs"), o.optInt("tokens"), o.optDouble("tps").toFloat(), o.optLong("prefillMs"))
        }
    }.getOrDefault(emptyList())
}

// ---------------- <think> parsing ----------------
data class Parsed(val thinking: String?, val answer: String, val thinkDone: Boolean)

fun parseThinking(raw: String): Parsed {
    val close = raw.indexOf("</think>")
    if (close >= 0) {
        val t = raw.substring(0, close).trimStart().removePrefix("<think>").trim()
        return Parsed(t.ifBlank { null }, raw.substring(close + 8).trimStart(), true)
    }
    val s = raw.trimStart()
    if (s.startsWith("<think>")) return Parsed(s.removePrefix("<think>").trimStart(), "", false)
    return Parsed(null, raw, true)
}
