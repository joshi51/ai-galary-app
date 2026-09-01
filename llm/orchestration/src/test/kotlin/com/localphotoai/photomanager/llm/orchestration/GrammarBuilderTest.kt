package com.localphotoai.photomanager.llm.orchestration

import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarBuilderTest {

    @Test
    fun `grammar references every tool id`() {
        // The grammar text contains GBNF string literals like "\"search_photos\"" — a literal
        // backslash-quote sequence (correct GBNF escaping for a literal quote character in the
        // model's JSON output), so the search substrings here include the same backslashes.
        val grammar = GrammarBuilder.build()
        assertTrue(grammar.contains("\\\"search_photos\\\""))
        assertTrue(grammar.contains("\\\"find_duplicates\\\""))
        assertTrue(grammar.contains("\\\"find_similar_photos\\\""))
        assertTrue(grammar.contains("\\\"get_photo_metadata\\\""))
        assertTrue(grammar.contains("\\\"get_storage_statistics\\\""))
    }

    @Test
    fun `grammar references the organization-plan tool id`() {
        val grammar = GrammarBuilder.build()
        assertTrue(grammar.contains("\\\"build_organization_plan\\\""))
    }

    @Test
    fun `grammar declares a root rule`() {
        val grammar = GrammarBuilder.build()
        assertTrue(grammar.lineSequence().any { it.trim().startsWith("root ::=") })
    }

    @Test
    fun `grammar is stable across calls`() {
        assertTrue(GrammarBuilder.build() == GrammarBuilder.build())
    }
}
