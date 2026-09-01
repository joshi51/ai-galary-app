package com.localphotoai.photomanager.llm.orchestration

/**
 * Builds the GBNF grammar passed to llama.cpp's grammar-constrained sampler (`:llm:runtime`),
 * so the model's output is *structurally* guaranteed to be one of the five tool-call shapes —
 * see ARCHITECTURE.md §19 and the Phase 8 design spec §5. A malformed *value* inside a
 * syntactically-valid call (e.g. a hallucinated photoId) is still possible and is caught by
 * `:tools`' `ToolValidator`, not by this grammar.
 */
object GrammarBuilder {

    // Kotlin triple-quoted strings are raw strings: a literal `\"` below is exactly two
    // characters, backslash then quote — NOT an escape sequence — which is precisely the GBNF
    // syntax for "match a literal quote character" inside a string-literal terminal. This is
    // deliberate and correct GBNF, not a stray/unintended backslash: the model's JSON output
    // needs literal `"` characters around each field name, and GBNF string terminals must
    // escape a literal quote as `\"` the same way JSON itself does.
    fun build(): String = """
        root ::= search-photos-call | find-duplicates-call | find-similar-photos-call | get-photo-metadata-call | get-storage-statistics-call | build-organization-plan-call

        search-photos-call ::= "{" ws "\"tool\":" ws "\"search_photos\"" "," ws "\"params\":" ws search-photos-params "}"
        search-photos-params ::= "{" ws (search-photos-field ("," ws search-photos-field)*)? ws "}"
        search-photos-field ::= people-field | start-date-field | end-date-field | location-field | sort-by-field
        people-field ::= "\"people\":" ws string-array
        start-date-field ::= "\"startDate\":" ws date-string
        end-date-field ::= "\"endDate\":" ws date-string
        location-field ::= "\"location\":" ws string
        sort-by-field ::= "\"sortBy\":" ws ("\"newest\"" | "\"largest\"" | "\"smallest\"")

        find-duplicates-call ::= "{" ws "\"tool\":" ws "\"find_duplicates\"" "," ws "\"params\":" ws "{" ws "}" ws "}"
        find-similar-photos-call ::= "{" ws "\"tool\":" ws "\"find_similar_photos\"" "," ws "\"params\":" ws "{" ws "}" ws "}"
        get-storage-statistics-call ::= "{" ws "\"tool\":" ws "\"get_storage_statistics\"" "," ws "\"params\":" ws "{" ws "}" ws "}"

        get-photo-metadata-call ::= "{" ws "\"tool\":" ws "\"get_photo_metadata\"" "," ws "\"params\":" ws "{" ws "\"photoId\":" ws number ws "}" ws "}"

        build-organization-plan-call ::= "{" ws "\"tool\":" ws "\"build_organization_plan\"" "," ws "\"params\":" ws build-organization-plan-params "}"
        build-organization-plan-params ::= "{" ws "\"category\":" ws ("\"SCREENSHOTS\"" | "\"BY_DATE\"" | "\"TRIP\"" | "\"ARCHIVE\"") (organize-optional-field)* ws "}"
        organize-optional-field ::= "," ws ("\"dateHint\":" ws date-string | "\"nameHint\":" ws string)

        string-array ::= "[" ws (string ("," ws string)*)? ws "]"
        date-string ::= "\"" [0-9] [0-9] [0-9] [0-9] "-" [0-9] [0-9] "-" [0-9] [0-9] "\""
        string ::= "\"" ([^"\\])* "\""
        number ::= [0-9]+
        ws ::= [ \t\n]*
    """.trimIndent()
}
