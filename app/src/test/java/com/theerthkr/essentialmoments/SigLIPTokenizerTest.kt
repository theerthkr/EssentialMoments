package com.theerthkr.essentialmoments

import com.theerthkr.essentialmoments.ml.SigLIPTokenizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SigLIPTokenizerTest {

    private lateinit var tokenizer: SigLIPTokenizer

    @Before
    fun setUp() {
        val vocabFile = sequenceOf(
            File("src/main/assets/siglip2_vocab.bin"),
            File("app/src/main/assets/siglip2_vocab.bin")
        ).first { it.exists() }

        tokenizer = SigLIPTokenizer()
        tokenizer.initialize(vocabFile.inputStream())
    }

    @Test
    fun `tokenizes SigLIP2 prompt with exact Gemma 256k BPE IDs`() {
        assertTrue(tokenizer.isInitialized)

        val ids = tokenizer.tokenize("a photo of a cute cat")
        assertEquals(64, ids.size)

        // Expected BPE tokens: "a"=235250, "▁photo"=2686, "▁of"=576, "▁a"=476, "▁cute"=9786, "▁cat"=4401, "<eos>"=1
        val expectedPrefix = intArrayOf(235250, 2686, 576, 476, 9786, 4401, 1, 0, 0, 0)
        assertArrayEquals(expectedPrefix, ids.take(10).toIntArray())
    }

    @Test
    fun `normalizes uppercase queries to match lowercase token IDs`() {
        val lowerIds = tokenizer.tokenize("sunset at the beach")
        val upperIds = tokenizer.tokenize("Sunset At The Beach")
        assertArrayEquals(lowerIds, upperIds)
    }

    @Test
    fun `handles byte fallback for emojis and special characters`() {
        val ids = tokenizer.tokenize("sunset 🌅")
        assertEquals(64, ids.size)
        // Ensure non-empty tokenization ending with EOS (1) followed by PAD (0)
        val nonPadCount = ids.indexOfFirst { it == 0 }
        assertTrue(nonPadCount > 2)
        assertEquals(1, ids[nonPadCount - 1])
    }
}
