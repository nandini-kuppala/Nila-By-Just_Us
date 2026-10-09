package com.nila.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Prompt.chatMl] stands in for Qwen3.5's own chat template, so it has to say
 * exactly what that template says: ChatML turns, earlier answers without a
 * think block, and an empty think block opening the reply because reasoning is
 * off. The expected strings below are what the template embedded in
 * Qwen3.5-2B-Q4_0.gguf renders for the same messages.
 */
class PromptChatMlTest {

    @Test
    fun `a single question renders as the template does`() {
        val prompt = Prompt.chatMl(listOf("system" to "Be brief.", "user" to " Is she ok? "))
        assertEquals(
            "<|im_start|>system\nBe brief.<|im_end|>\n" +
                "<|im_start|>user\nIs she ok?<|im_end|>\n" +
                "<|im_start|>assistant\n<think>\n\n</think>\n\n",
            prompt,
        )
    }

    @Test
    fun `earlier turns carry no think block, only the reply does`() {
        val prompt = Prompt.chatMl(
            listOf(
                "system" to "Be brief.",
                "user" to "How much crying is normal?",
                "assistant" to "Up to three hours a day.",
                "user" to "Is her sleep normal?",
            )
        )
        assertEquals(
            "<|im_start|>system\nBe brief.<|im_end|>\n" +
                "<|im_start|>user\nHow much crying is normal?<|im_end|>\n" +
                "<|im_start|>assistant\nUp to three hours a day.<|im_end|>\n" +
                "<|im_start|>user\nIs her sleep normal?<|im_end|>\n" +
                "<|im_start|>assistant\n<think>\n\n</think>\n\n",
            prompt,
        )
        assertEquals(1, Regex("<think>").findAll(prompt).count())
    }

    @Test
    fun `the prompt ends where the model starts answering`() {
        assertTrue(Prompt.chatMl(listOf("user" to "Hi")).endsWith("</think>\n\n"))
    }
}
