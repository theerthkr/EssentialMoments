package com.theerthkr.essentialmoments.ml

import android.content.Context
import android.util.Log
import java.io.DataInputStream

/**
 * Pure Kotlin SentencePiece tokenizer for SigLIP2.
 * Reads spiece.model directly — no external library needed.
 *
 * SigLIP2 text encoder contract:
 *   - Vocab size : 32,000
 *   - Max length : 64 tokens
 *   - Format     : [BOS=1] + token_ids + [EOS=2] + [PAD=0 ...]
 *   - Lowercase  : yes (model trained on lowercased text)
 */
class SigLIPTokenizer(private val context: Context) {

    companion object {
        private const val TAG       = "SigLIPTokenizer"
        const val MAX_LENGTH        = 64
        private const val BOS       = 1
        private const val EOS       = 2
        private const val PAD       = 0
        private const val UNK       = 0
        private const val VOCAB_FILE = "spiece.model"

        // SentencePiece proto field numbers we care about
        private const val PIECE_FIELD   = 1
        private const val SCORE_FIELD   = 2
        private const val TRAINER_FIELD = 1
        private const val PIECES_FIELD  = 4
    }

    // token string → token id
    private val vocab = mutableMapOf<String, Int>()
    // token id → token string
    private val idToToken = mutableMapOf<Int, String>()
    // BPE merge scores: pair of strings → score (lower = higher priority)
    private val mergeScores = mutableMapOf<Pair<String, String>, Float>()

    private var initialized = false

    // ── Init ──────────────────────────────────────────────────────

    fun initialize() {
        if (initialized) return
        try {
            val bytes = context.assets.open(VOCAB_FILE).readBytes()
            parseSentencePieceModel(bytes)
            initialized = true
            Log.d(TAG, "✅ Tokenizer ready: ${vocab.size} tokens")
        } catch (e: Exception) {
            Log.e(TAG, "Tokenizer init failed: ${e.message}", e)
        }
    }

    // ── Public API ────────────────────────────────────────────────

    fun tokenize(text: String): IntArray {
        if (!initialized) {
            Log.e(TAG, "Not initialized"); return IntArray(MAX_LENGTH) { PAD }
        }

        val normalized = text.trim().lowercase()
        val tokens = encode(normalized)
        return buildSequence(tokens)
    }

    // ── BPE Encoding ──────────────────────────────────────────────

    private fun encode(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()

        // 1. Character-level split with ▁ (U+2581) as word-start marker
        val words = text.split(" ").filter { it.isNotEmpty() }
        val allPieces = mutableListOf<String>()

        words.forEachIndexed { i, word ->
            val prefix = if (i == 0) "▁" else "▁"
            val chars = (prefix + word).map { it.toString() }
            allPieces.addAll(chars)
            allPieces.add(" ")  // word boundary marker
        }
        if (allPieces.isNotEmpty()) allPieces.removeLastOrNull()

        // 2. BPE merges
        val pieces = allPieces.toMutableList()
        while (pieces.size > 1) {
            var bestScore = Float.MAX_VALUE
            var bestIdx = -1

            for (i in 0 until pieces.size - 1) {
                val pair = Pair(pieces[i], pieces[i + 1])
                val score = mergeScores[pair]
                if (score != null && score < bestScore) {
                    bestScore = score
                    bestIdx = i
                }
            }

            if (bestIdx == -1) break  // no more merges possible

            val merged = pieces[bestIdx] + pieces[bestIdx + 1]
            pieces[bestIdx] = merged
            pieces.removeAt(bestIdx + 1)
        }

        // 3. Map pieces to IDs
        return pieces.map { piece ->
            vocab[piece] ?: vocab["▁$piece"] ?: UNK
        }
    }

    private fun buildSequence(tokens: List<Int>): IntArray {
        val result = IntArray(MAX_LENGTH) { PAD }
        result[0] = BOS
        val usable = tokens.take(MAX_LENGTH - 2)
        usable.forEachIndexed { i, id -> result[i + 1] = id }
        result[usable.size + 1] = EOS

        Log.d(TAG, "tokenize: ${tokens.size} pieces → " +
                "ids[0..5]=${result.take(6).toIntArray().toList()}")
        return result
    }

    // ── SentencePiece Proto Parser ────────────────────────────────

    /**
     * Minimal proto parser for SentencePieceModel.
     * Only reads the fields we need: trainer_spec.pieces[].piece and .score
     *
     * Proto wire format:
     *   tag = (field_number << 3) | wire_type
     *   wire_type 0 = varint, 2 = length-delimited
     */
    private fun parseSentencePieceModel(bytes: ByteArray) {
        var pos = 0

        fun readVarint(): Long {
            var result = 0L; var shift = 0
            while (pos < bytes.size) {
                val b = bytes[pos++].toLong() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0L) break
                shift += 7
            }
            return result
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            val out = bytes.copyOfRange(pos, pos + len)
            pos += len; return out
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> { pos += 8 }
                2 -> readBytes()
                5 -> { pos += 4 }
            }
        }

        // Parse top-level ModelProto
        while (pos < bytes.size) {
            val tag = readVarint()
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()

            if (fieldNum == TRAINER_FIELD && wireType == 2) {
                // This is trainer_spec — parse its contents
                val trainerBytes = readBytes()
                parseTrainerSpec(trainerBytes)
            } else {
                skipField(wireType)
            }
        }
    }

    private fun parseTrainerSpec(bytes: ByteArray) {
        var pos = 0
        var tokenId = 0

        fun readVarint(): Long {
            var result = 0L; var shift = 0
            while (pos < bytes.size) {
                val b = bytes[pos++].toLong() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0L) break
                shift += 7
            }
            return result
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            val out = bytes.copyOfRange(pos, pos + len)
            pos += len; return out
        }

        fun readFloat(): Float {
            val bits = (bytes[pos].toLong() and 0xFF) or
                    ((bytes[pos+1].toLong() and 0xFF) shl 8) or
                    ((bytes[pos+2].toLong() and 0xFF) shl 16) or
                    ((bytes[pos+3].toLong() and 0xFF) shl 24)
            pos += 4
            return java.lang.Float.intBitsToFloat(bits.toInt())
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> { pos += 8 }
                2 -> readBytes()
                5 -> { pos += 4 }
            }
        }

        while (pos < bytes.size) {
            val tag = readVarint()
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()

            if (fieldNum == PIECES_FIELD && wireType == 2) {
                // SentencePiece entry — parse piece + score
                val pieceBytes = readBytes()
                parsePiece(pieceBytes, tokenId++)
            } else {
                skipField(wireType)
            }
        }

        Log.d(TAG, "Parsed ${vocab.size} vocab entries")
        buildMergeScores()
    }

    private fun parsePiece(bytes: ByteArray, tokenId: Int) {
        var pos = 0
        var piece = ""
        var score = 0f

        fun readVarint(): Long {
            var result = 0L; var shift = 0
            while (pos < bytes.size) {
                val b = bytes[pos++].toLong() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0L) break
                shift += 7
            }
            return result
        }

        while (pos < bytes.size) {
            val tag = readVarint()
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()

            when {
                fieldNum == 1 && wireType == 2 -> {
                    // piece string
                    val len = readVarint().toInt()
                    piece = String(bytes, pos, len, Charsets.UTF_8)
                    pos += len
                }
                fieldNum == 2 && wireType == 5 -> {
                    // score (float32)
                    val bits = (bytes[pos].toLong() and 0xFF) or
                            ((bytes[pos+1].toLong() and 0xFF) shl 8) or
                            ((bytes[pos+2].toLong() and 0xFF) shl 16) or
                            ((bytes[pos+3].toLong() and 0xFF) shl 24)
                    score = java.lang.Float.intBitsToFloat(bits.toInt())
                    pos += 4
                }
                else -> {
                    when (wireType) {
                        0 -> readVarint()
                        2 -> { val l = readVarint().toInt(); pos += l }
                        5 -> pos += 4
                        1 -> pos += 8
                    }
                }
            }
        }

        if (piece.isNotEmpty()) {
            vocab[piece] = tokenId
            idToToken[tokenId] = piece
        }
    }

    /**
     * SentencePiece BPE merges are implicit — a merge is valid if the
     * concatenation of two adjacent tokens exists in vocab.
     * Score = the vocab score of the merged token (lower = higher priority).
     */
    private fun buildMergeScores() {
        // For every token of length > 1, register the last-char split as a merge
        for ((piece, id) in vocab) {
            if (piece.length <= 1) continue
            // Try all split points
            for (split in 1 until piece.length) {
                val left  = piece.substring(0, split)
                val right = piece.substring(split)
                if (vocab.containsKey(left) && vocab.containsKey(right)) {
                    val score = -(id.toFloat()) // lower id = higher priority merge
                    val key = Pair(left, right)
                    if (!mergeScores.containsKey(key) ||
                        mergeScores[key]!! > score) {
                        mergeScores[key] = score
                    }
                }
            }
        }
        Log.d(TAG, "Built ${mergeScores.size} merge rules")
    }
}