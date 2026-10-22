// JNI bridge: llama.cpp <-> Kotlin. CPU only, mmap, quantized KV cache,
// prompt-prefix KV reuse, streaming with UTF-8 safe token pieces.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "GGUFBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_craze_ggufrunner_LlamaBridge_##name

static llama_model*   g_model = nullptr;
static llama_context* g_ctx   = nullptr;
static std::vector<llama_token> g_cache;      // tokens currently in the KV cache (seq 0)
static std::atomic<bool> g_stop{false};
static std::mutex g_mutex;
static bool g_backend_ready = false;

static void log_cb(ggml_log_level level, const char* text, void*) {
    __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, TAG, "%s", text);
}

static void free_all() {
    g_cache.clear();
    if (g_ctx)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

// Length of the longest prefix of s that does not end in a truncated UTF-8 sequence.
static size_t valid_utf8_prefix(const std::string& s) {
    const size_t n = s.size();
    size_t back = 0;
    for (size_t i = n; i > 0 && back < 4;) {
        --i; ++back;
        unsigned char c = (unsigned char)s[i];
        if ((c & 0xC0) == 0x80) continue;                 // continuation byte, keep looking
        size_t need = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
        return back < need ? i : n;
    }
    return n;
}

static llama_context* make_ctx(int nCtx, int nBatch, int nThreads, ggml_type kv, bool flash) {
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nCtx;
    cp.n_batch = nBatch;
    cp.n_ubatch = nBatch;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    cp.flash_attn = flash;
    cp.type_k = kv;
    cp.type_v = kv;
    cp.no_perf = true;
    return llama_init_from_model(g_model, cp);
}

JNI_FN(jstring, nativeLoad)(JNIEnv* env, jobject, jstring jpath, jint nCtx, jint nThreads, jint nBatch,
                   jint kvType, jboolean useMmap, jboolean flashAttn) {
    std::lock_guard<std::mutex> lk(g_mutex);
    free_all();
    if (!g_backend_ready) { llama_log_set(log_cb, nullptr); llama_backend_init(); g_backend_ready = true; }

    const char* cpath = env->GetStringUTFChars(jpath, nullptr);
    std::string path(cpath);
    env->ReleaseStringUTFChars(jpath, cpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_mmap = useMmap;     // weights stay in page cache -> OS can evict them under pressure
    mp.use_mlock = false;
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) return env->NewStringUTF("Could not load model (unsupported/corrupt GGUF or not enough RAM).");

    int ctx = std::min<int>(nCtx, llama_model_n_ctx_train(g_model));
    ctx = std::max(ctx, 256);
    ggml_type kv = kvType == 0 ? GGML_TYPE_F16 : (kvType == 1 ? GGML_TYPE_Q8_0 : GGML_TYPE_Q4_0);
    bool flash = flashAttn || kvType != 0;      // quantized V cache requires flash attention

    g_ctx = make_ctx(ctx, nBatch, nThreads, kv, flash);
    if (!g_ctx && kv != GGML_TYPE_F16) {
        LOGE("quantized KV failed, retrying with f16");
        g_ctx = make_ctx(ctx, nBatch, nThreads, GGML_TYPE_F16, flashAttn);
    }
    if (!g_ctx) { free_all(); return env->NewStringUTF("Could not create context (try a smaller context size)."); }
    LOGI("loaded %s ctx=%d threads=%d batch=%d", path.c_str(), ctx, (int)nThreads, (int)nBatch);
    return nullptr;
}

JNI_FN(void, nativeUnload)(JNIEnv*, jobject) {
    g_stop = true;
    std::lock_guard<std::mutex> lk(g_mutex);
    free_all();
}

JNI_FN(void, nativeStop)(JNIEnv*, jobject) { g_stop = true; }

JNI_FN(void, nativeResetCache)(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), true);
    g_cache.clear();
}

JNI_FN(jstring, nativeInfo)(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!g_model || !g_ctx) return env->NewStringUTF("");
    char desc[256];
    llama_model_desc(g_model, desc, sizeof(desc));
    char out[512];
    snprintf(out, sizeof(out), "%s|%llu|%llu|%d|%d", desc,
             (unsigned long long)llama_model_size(g_model),
             (unsigned long long)llama_model_n_params(g_model),
             (int)llama_model_n_ctx_train(g_model), (int)llama_n_ctx(g_ctx));
    return env->NewStringUTF(out);
}

JNI_FN(jstring, nativeSystemInfo)(JNIEnv* env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

// returns: >=0 generated tokens | -1 no model | -2 prompt too long | -3 decode error | -4 template/tokenize error
JNI_FN(jint, nativeGenerate)(JNIEnv* env, jobject, jobjectArray jroles, jobjectArray jcontents, jint maxTokens,
                       jfloat temp, jint topK, jfloat topP, jfloat minP, jfloat repeatPenalty,
                       jint seed, jobject cb) {
    std::lock_guard<std::mutex> lk(g_mutex);
    g_stop = false;
    if (!g_model || !g_ctx) return -1;

    jclass cbCls = env->FindClass("com/craze/ggufrunner/TokenCallback");
    jmethodID cbMid = env->GetMethodID(cbCls, "onToken", "([B)Z");
    env->DeleteLocalRef(cbCls);
    if (!cbMid) return -4;

    // ---- read messages ----
    const jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roles(n), contents(n);
    for (jsize i = 0; i < n; i++) {
        jstring r = (jstring)env->GetObjectArrayElement(jroles, i);
        const char* rc = env->GetStringUTFChars(r, nullptr);
        roles[i] = rc;
        env->ReleaseStringUTFChars(r, rc);
        env->DeleteLocalRef(r);
        jbyteArray b = (jbyteArray)env->GetObjectArrayElement(jcontents, i);
        jsize len = env->GetArrayLength(b);
        contents[i].resize(len);
        if (len) env->GetByteArrayRegion(b, 0, len, (jbyte*)contents[i].data());
        env->DeleteLocalRef(b);
    }
    std::vector<llama_chat_message> msgs(n);
    size_t total = 0;
    for (jsize i = 0; i < n; i++) { msgs[i] = {roles[i].c_str(), contents[i].c_str()}; total += contents[i].size() + roles[i].size() + 32; }

    // ---- chat template (model's own, falls back to chatml) ----
    const char* tmpl = llama_model_chat_template(g_model, nullptr);
    std::vector<char> buf(total * 2 + 1024);
    int32_t len = llama_chat_apply_template(tmpl ? tmpl : "chatml", msgs.data(), msgs.size(), true, buf.data(), (int32_t)buf.size());
    if (len < 0) len = llama_chat_apply_template("chatml", msgs.data(), msgs.size(), true, buf.data(), (int32_t)buf.size());
    if (len > (int32_t)buf.size()) {
        buf.resize(len);
        len = llama_chat_apply_template(tmpl ? tmpl : "chatml", msgs.data(), msgs.size(), true, buf.data(), (int32_t)buf.size());
    }
    if (len < 0) return -4;
    std::string prompt(buf.data(), len);

    // ---- tokenize ----
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    int nt = -llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(), nullptr, 0, false, true);
    if (nt <= 0) return -4;
    std::vector<llama_token> toks(nt);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(), toks.data(), nt, false, true) < 0) return -4;
    if (llama_vocab_get_add_bos(vocab) && toks[0] != llama_vocab_bos(vocab))
        toks.insert(toks.begin(), llama_vocab_bos(vocab));

    const int nCtx = (int)llama_n_ctx(g_ctx);
    const int nBatch = (int)llama_n_batch(g_ctx);
    const int genLimit = std::min<int>(maxTokens, nCtx - (int)toks.size() - 1);
    if (genLimit < 16) return -2;

    // ---- reuse KV cache for the common prefix (huge speedup in multi-turn chat) ----
    llama_memory_t mem = llama_get_memory(g_ctx);
    size_t common = 0;
    while (common < g_cache.size() && common + 1 < toks.size() && g_cache[common] == toks[common]) common++;
    if (common < g_cache.size()) {
        if (common == 0 || !llama_memory_seq_rm(mem, 0, (llama_pos)common, -1)) {
            llama_memory_clear(mem, true);
            common = 0;
        }
        g_cache.resize(common);
    }

    // ---- prefill in chunks ----
    for (size_t i = common; i < toks.size();) {
        if (g_stop) return 0;
        int nb = (int)std::min<size_t>(nBatch, toks.size() - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, nb);
        if (llama_decode(g_ctx, b) != 0) { llama_memory_clear(mem, true); g_cache.clear(); return -3; }
        g_cache.insert(g_cache.end(), toks.begin() + i, toks.begin() + i + nb);
        i += nb;
    }

    // ---- sampler ----
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (repeatPenalty > 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, repeatPenalty, 0.0f, 0.0f));
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (topK > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
        if (topP < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
        if (minP > 0.0f) llama_sampler_chain_add(smpl, llama_sampler_init_min_p(minP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed < 0 ? LLAMA_DEFAULT_SEED : (uint32_t)seed));
    }

    auto emit = [&](const std::string& s) -> bool {
        jbyteArray arr = env->NewByteArray((jsize)s.size());
        env->SetByteArrayRegion(arr, 0, (jsize)s.size(), (const jbyte*)s.data());
        jboolean r = env->CallBooleanMethod(cb, cbMid, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        return r == JNI_TRUE;
    };

    // ---- generate ----
    int generated = 0;
    std::string pending;
    char piece[256];
    while (generated < genLimit && !g_stop) {
        llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        int pl = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
        if (pl < 0) {
            std::vector<char> big(-pl);
            pl = llama_token_to_piece(vocab, tok, big.data(), (int32_t)big.size(), 0, true);
            if (pl > 0) pending.append(big.data(), pl);
        } else {
            pending.append(piece, pl);
        }
        generated++;

        bool keep = true;
        size_t k = valid_utf8_prefix(pending);
        if (k > 0) { keep = emit(pending.substr(0, k)); pending.erase(0, k); }
        if (!keep || generated >= genLimit) break;

        llama_batch b = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, b) != 0) { llama_memory_clear(mem, true); g_cache.clear(); llama_sampler_free(smpl); return -3; }
        g_cache.push_back(tok);
    }
    if (!pending.empty()) emit(pending);
    llama_sampler_free(smpl);
    return generated;
}
