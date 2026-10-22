package com.craze.ggufrunner

import androidx.annotation.Keep

@Keep
fun interface TokenCallback {
    /** Called with a complete UTF-8 chunk. Return false to stop generating. */
    fun onToken(bytes: ByteArray): Boolean
}

@Keep
class LlamaBridge {
    companion object {
        init { System.loadLibrary("gguf_bridge") }
    }

    /** Returns null on success, otherwise an error message. */
    external fun nativeLoad(
        path: String, nCtx: Int, nThreads: Int, nBatch: Int,
        kvType: Int, useMmap: Boolean, flashAttn: Boolean
    ): String?

    external fun nativeUnload()
    external fun nativeStop()
    external fun nativeResetCache()
    external fun nativeInfo(): String
    external fun nativeSystemInfo(): String

    /** >=0 tokens generated | -1 no model | -2 prompt too long | -3 decode error | -4 template error */
    external fun nativeGenerate(
        roles: Array<String>, contents: Array<ByteArray>, maxTokens: Int,
        temp: Float, topK: Int, topP: Float, minP: Float, repeatPenalty: Float,
        seed: Int, cb: TokenCallback
    ): Int
}
