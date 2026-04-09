package com.theerthkr.essentialmoments.ml

import android.content.Context
import android.util.Log

/**
 * SentencePiece Unigram LM tokenizer for SigLIP2.
 *
 * ROOT CAUSE OF "Parsed 0 vocab entries" BUG:
 *   Old code assumed pieces were nested: ModelProto → trainer_spec(field=1) → pieces(field=4)
 *
 *   ACTUAL SentencePieceModel proto structure:
 *     message SentencePieceModel {
 *       repeated SentencePiece pieces = 1;  ← FIELD 1 AT TOP LEVEL, not nested
 *       TrainerSpec trainer_spec = 2;
 *     }
 *     message SentencePiece { string piece = 1; float score = 2; int type = 3; }
 *
 *   The parser entered field 1 as a sub-message (expecting trainer_spec), then
 *   searched for field 4 inside it — which doesn't exist. Result: 0 entries.
 *   Fix: read field 1 directly at the top level as SentencePiece entries.
 */
class SigLIPTokenizer(private val context: Context) {

    companion object {
        private const val TAG           = "SigLIPTokenizer"
        const val MAX_LENGTH            = 64
        private const val BOS           = 1
        private const val EOS           = 2
        private const val PAD           = 0
        private const val UNK           = 0
        private const val VOCAB_FILE    = "spiece.model"
        private const val MAX_PIECE_LEN = 32
    }

    private val vocab = HashMap<String, Pair<Int, Float>>(40000)
    private var initialized = false

    fun initialize() {
        if (initialized) return
        try {
            val bytes = context.assets.open(VOCAB_FILE).readBytes()
            parseModel(bytes)
            initialized = true
            Log.d(TAG, "✅ Tokenizer ready: ${vocab.size} pieces")
            if (vocab.size < 1000)
                Log.e(TAG, "❌ Vocab only ${vocab.size} entries — parse likely failed")
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}", e)
        }
    }

    // ── Public ────────────────────────────────────────────────────

    fun tokenize(text: String): IntArray {
        if (!initialized) { Log.e(TAG, "Not initialized"); return IntArray(MAX_LENGTH) { PAD } }
        val tokens = unigramSegment(text.trim().lowercase())
        return buildSequence(tokens)
    }

    // ── Viterbi segmentation ──────────────────────────────────────

    private fun unigramSegment(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()
        val s = "▁" + text.replace(" ", "▁")
        val n = s.length
        val bestScore = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val bestStart = IntArray(n + 1) { -1 }
        bestScore[0] = 0f

        for (end in 1..n) {
            for (start in maxOf(0, end - MAX_PIECE_LEN) until end) {
                if (bestScore[start] == Float.NEGATIVE_INFINITY) continue
                val piece = s.substring(start, end)
                val entry = vocab[piece] ?: continue
                val score = bestScore[start] + entry.second
                if (score > bestScore[end]) {
                    bestScore[end] = score
                    bestStart[end] = start
                }
            }
        }

        val pieces = ArrayDeque<String>()
        var pos = n
        while (pos > 0) {
            val start = bestStart[pos]
            if (start < 0) { pieces.addFirst(s.substring(pos - 1, pos)); pos-- }
            else           { pieces.addFirst(s.substring(start, pos));    pos = start }
        }
        return pieces.map { vocab[it]?.first ?: UNK }
    }

    private fun buildSequence(tokens: List<Int>): IntArray {
        val result = IntArray(MAX_LENGTH) { PAD }
        result[0]  = BOS
        val usable = tokens.take(MAX_LENGTH - 2)
        usable.forEachIndexed { i, id -> result[i + 1] = id }
        result[usable.size + 1] = EOS
        Log.d(TAG, "tokenize: ${tokens.size} pieces → ids[0..5]=${result.take(6).toList()}")
        return result
    }

    // ── Proto parser ──────────────────────────────────────────────

    private fun parseModel(bytes: ByteArray) {
        val r = ProtoReader(bytes)
        var tokenId = 0
        while (r.hasMore()) {
            val tag = r.readVarint()
            val field = (tag ushr 3).toInt()
            val wire  = (tag and 0x7).toInt()
            if (field == 1 && wire == 2) {
                parsePiece(r.readBytes(), tokenId++)
            } else {
                r.skipField(wire)
            }
        }
        Log.d(TAG, "Parsed ${vocab.size} vocab entries from $tokenId proto entries")
    }

    private fun parsePiece(bytes: ByteArray, tokenId: Int) {
        val r = ProtoReader(bytes)
        var piece = ""; var score = 0f
        while (r.hasMore()) {
            val tag = r.readVarint()
            val field = (tag ushr 3).toInt()
            val wire  = (tag and 0x7).toInt()
            when {
                field == 1 && wire == 2 -> piece = String(r.readBytes(), Charsets.UTF_8)
                field == 2 && wire == 5 -> score = r.readFloat32()
                else -> r.skipField(wire)
            }
        }
        if (piece.isNotEmpty()) vocab[piece] = Pair(tokenId, score)
    }

    // ── Debug ─────────────────────────────────────────────────────

    fun debugTokenize(text: String): String {
        if (!initialized) return "Not initialized"
        val ids = tokenize(text)
        val nonPad = ids.drop(1).takeWhile { it != EOS && it != PAD }
        val idToPiece = HashMap<Int, String>(vocab.size)
        vocab.forEach { (k, v) -> idToPiece[v.first] = k }
        val pieces = nonPad.map { idToPiece[it] ?: "<unk:$it>" }
        return "'$text' → [${pieces.joinToString("|")}] (${pieces.size} pieces)"
    }

    // ── ProtoReader ───────────────────────────────────────────────

    private class ProtoReader(private val b: ByteArray) {
        var pos = 0
        fun hasMore() = pos < b.size

        fun readVarint(): Long {
            var r = 0L; var s = 0
            while (pos < b.size) {
                val byte = b[pos++].toLong() and 0xFF
                r = r or ((byte and 0x7F) shl s)
                if (byte and 0x80 == 0L) break
                s += 7
            }
            return r
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            return b.copyOfRange(pos, pos + len).also { pos += len }
        }

        fun readFloat32(): Float {
            val bits = ((b[pos].toLong() and 0xFF)      ) or
                    ((b[pos+1].toLong() and 0xFF) shl 8 ) or
                    ((b[pos+2].toLong() and 0xFF) shl 16) or
                    ((b[pos+3].toLong() and 0xFF) shl 24)
            pos += 4
            return java.lang.Float.intBitsToFloat(bits.toInt())
        }

        fun skipField(wireType: Int) = when (wireType) {
            0 -> { readVarint(); Unit }
            1 -> { pos += 8 }
            2 -> { readBytes(); Unit }
            5 -> { pos += 4 }
            else -> { pos = b.size }
        }
    }
}