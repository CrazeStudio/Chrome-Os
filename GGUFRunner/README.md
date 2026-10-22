# GGUF Runner (Android)

Offline GGUF chat app on top of **llama.cpp** (CPU, arm64-v8a), Kotlin + Jetpack Compose.

## Build
1. Open this folder in **Android Studio** (Ladybug or newer). Let it sync (it fetches Gradle 8.9, NDK and CMake 3.22.1 via SDK Manager if missing).
2. First build needs internet: CMake downloads llama.cpp (tag `b6000`, see `app/src/main/cpp/CMakeLists.txt`).
   Offline? `git clone https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp` and checkout the same tag - it is picked up automatically.
3. Run on a physical arm64 phone (emulators are x86 and are not built). **Build > Build APK(s)** gives an installable APK (release is debug-signed).

## Use
Tap the RAM chip > **Import .gguf** > Load. Best speed on phones: Q4_0 / Q4_K_M quants of 0.5-4B models
(Qwen3, Gemma 3, Llama 3.2, Phi-4-mini, SmolLM3, DeepSeek-R1-Distill ...).

## Speed / RAM features
- mmap weights (evictable page cache, no second copy in RAM), no mlock
- Quantized KV cache (Q8 = half, Q4 = quarter of F16) + flash attention
- Small default context (2048) and batch (256) = small compute buffers
- KV prefix reuse: follow-up messages only evaluate the new tokens
- NEON + dotprod int8 kernels, runtime weight repacking, threads default to the big-core count
- Live RAM meter in the top bar, one-tap Unload

## Thinking
`<think>...</think>` output is split into a collapsible "Thinking..." card (live while streaming, "Thought for Ns" after).
Works for Qwen3, DeepSeek-R1 distills, etc. The Thinking switch adds `/no_think` for Qwen3.

## Other
Streaming, stop, retry, copy, code blocks, markdown basics, tok/s + prefill stats, chat persistence,
sampling controls (temp, top-k/p, min-p, repeat), system prompt, auto context trimming.

## Notes
- If your phone lacks dotprod (very old), change `GGML_CPU_ARM_ARCH` in CMakeLists.txt to `armv8-a`.
- If a newer llama.cpp tag is used, a few C API names may differ; adjust `gguf_bridge.cpp`.
