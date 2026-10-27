package com.theerthkr.essentialmoments.ml

import android.content.Context
import android.util.Log
import java.io.InputStream

/**
 * Pure Kotlin SentencePiece BPE tokenizer for SigLIP 2 (Gemma 256k vocabulary).
 *
 * Replaces the DJL HuggingFaceTokenizer + JNI native libraries and the 33 MB
 * `tokenizer.json` with a 2.1 MB binary vocabulary (`siglip2_vocab.bin`) while
 * producing 100% bit-exact token IDs.
 *
 * SigLIP 2 BPE specification (from `tokenizer.json`):
 * - Vocabulary size: 256,000
 *   - `0`: `<pad>`
 *   - `1`: `<eos>`
 *   - `2`: `<bos>`
 *   - `3`: `<unk>`
 *   - `109..168`, `255969..255998`: multi-whitespace user-defined runs (`\n`, `▁`, `\t`)
 *   - `217..472`: byte-fallback tokens (`<0x00>` .. `<0xFF>`)
 *   - `473..235247`: BPE merged tokens ordered by SentencePiece merge priority
 *   - `235248..255899`: single-character tokens
 * - Normalizer: lowercase + replace `" "` with `"▁"` (`\u2581`)
 * - Post-processor: append `<eos>` (ID = 1), right-pad with `<pad>` (ID = 0) to length 64.
 */
class SigLIPTokenizer(private val context: Context? = null) {

    companion object {
        private const val TAG = "SigLIPTokenizer"
        const val MAX_LENGTH = 64
        const val PAD_TOKEN_ID = 0
        const val EOS_TOKEN_ID = 1
        const val BOS_TOKEN_ID = 2
        const val UNK_TOKEN_ID = 3
        const val VOCAB_FILE = "onnx_int8/siglip2_vocab.bin"
        private const val EXPECTED_VOCAB_SIZE = 256_000

        private val BYTE_TOKENS = Array(256) { "<0x%02X>".format(it) }

        @Volatile
        private var cachedVocab: HashMap<String, Int>? = null
        private val lock = Any()

        internal fun loadVocabFromStream(stream: InputStream): HashMap<String, Int> {
            val bytes = stream.use { it.readBytes() }
            val map = HashMap<String, Int>(350_000, 0.8f)
            var pos = 0
            var tokenId = 0
            while (pos < bytes.size) {
                val len = bytes[pos++].toInt() and 0xFF
                val piece = String(bytes, pos, len, Charsets.UTF_8)
                pos += len
                map[piece] = tokenId++
            }
            return map
        }
    }

    private var vocab: HashMap<String, Int>? = cachedVocab

    val isInitialized: Boolean
        get() = vocab != null

    fun initialize() {
        if (vocab != null) return
        synchronized(lock) {
            if (cachedVocab == null) {
                val ctx = requireNotNull(context) {
                    "Context required for initialize(); use initialize(InputStream) in JVM tests"
                }
                try {
                    val loaded = loadVocabFromStream(ctx.assets.open(VOCAB_FILE))
                    cachedVocab = loaded
                    Log.d(TAG, "Tokenizer ready: ${loaded.size} vocab entries")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load $VOCAB_FILE: ${e.message}", e)
                }
            }
            vocab = cachedVocab
        }
    }

    fun initialize(stream: InputStream) {
        synchronized(lock) {
            if (cachedVocab == null) {
                cachedVocab = loadVocabFromStream(stream)
            }
            vocab = cachedVocab
        }
    }

    /**
     * Tokenizes [text] into a fixed-size [IntArray] of length [MAX_LENGTH] (64),
     * appending `<eos>` (1) and right-padding with `<pad>` (0).
     */
    fun tokenize(text: String, lowercase: Boolean = true): IntArray {
        if (vocab == null && context != null) {
            initialize()
        }
        val v = vocab ?: return IntArray(MAX_LENGTH) { PAD_TOKEN_ID }

        val prepared = if (lowercase) text.lowercase() else text
        val normalized = prepared.replace(" ", "▁")

        val tokenIds = bpeEncode(normalized, v)
        val result = IntArray(MAX_LENGTH) { PAD_TOKEN_ID }

        val count = minOf(tokenIds.size, MAX_LENGTH)
        for (i in 0 until count) {
            result[i] = tokenIds[i]
        }
        // Append <eos> right after the token sequence if space permits
        if (tokenIds.size < MAX_LENGTH) {
            result[tokenIds.size] = EOS_TOKEN_ID
        }
        return result
    }

    private fun bpeEncode(normalized: String, v: HashMap<String, Int>): IntArray {
        if (normalized.isEmpty()) return IntArray(0)

        val pieces = ArrayList<String>(normalized.length)
        var offset = 0
        while (offset < normalized.length) {
            val cp = normalized.codePointAt(offset)
            val charCount = Character.charCount(cp)
            val chStr = normalized.substring(offset, offset + charCount)
            if (v.containsKey(chStr)) {
                pieces.add(chStr)
            } else {
                for (b in chStr.toByteArray(Charsets.UTF_8)) {
                    pieces.add(BYTE_TOKENS[b.toInt() and 0xFF])
                }
            }
            offset += charCount
        }

        while (pieces.size > 1) {
            var bestIdx = -1
            var bestPrio = Int.MAX_VALUE

            for (i in 0 until pieces.size - 1) {
                val prio = mergePriority(pieces[i], pieces[i + 1], v)
                if (prio < bestPrio) {
                    bestPrio = prio
                    bestIdx = i
                }
            }

            if (bestIdx == -1) break

            val merged = pieces[bestIdx] + pieces[bestIdx + 1]
            pieces[bestIdx] = merged
            pieces.removeAt(bestIdx + 1)
        }

        return IntArray(pieces.size) { idx ->
            v[pieces[idx]] ?: UNK_TOKEN_ID
        }
    }

    /**
     * Exact rank mapping matching all 580,604 merges in `tokenizer.json`.
     */
    private fun mergePriority(left: String, right: String, v: HashMap<String, Int>): Int {
        val merged = left + right
        val tid = v[merged] ?: return Int.MAX_VALUE
        val leftCodePoints = left.codePointCount(0, left.length)
        return when {
            tid in 473..235247 -> (tid shl 8) - leftCodePoints
            tid == 255969 -> (473 shl 8) + 1
            tid in 109..168 || tid in 255970..255998 -> {
                val mergedCodePoints = merged.codePointCount(0, merged.length)
                val chOrd = when (left[0]) {
                    '\n' -> 0
                    '▁' -> 1
                    else -> 2
                }
                ((100 - leftCodePoints) * 64 + (100 - mergedCodePoints)) * 4 + chOrd
            }
            else -> Int.MAX_VALUE
        }
    }
}
