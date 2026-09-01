package com.localphotoai.photomanager.domain.tool

/** The on-device LLM's inference surface — deliberately minimal (prompt + grammar in, raw text
 * out) so the engine implementation (`:llm:runtime`) stays fully swappable, per ARCHITECTURE.md
 * §2's "LLM engine/model must be replaceable later" requirement. */
interface LlmEngine {
    suspend fun generate(prompt: String, grammar: String): String
}
