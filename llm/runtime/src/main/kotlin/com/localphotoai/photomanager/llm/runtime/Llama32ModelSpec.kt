package com.localphotoai.photomanager.llm.runtime

/**
 * Llama-3.2-1B-Instruct, GGUF, Q4_K_M quantization (~0.77GB).
 *
 * **License/provenance record** (verified 2026-09-01, mirroring Phase 4 §33's FaceNet rigor):
 * - Base model: `meta-llama/Llama-3.2-1B-Instruct` — licensed under Meta's **Llama 3.2 Community
 *   License** (HF license tag `llama3.2`). Key terms relevant to this app: (1) attribution —
 *   any distribution must display "Built with Llama" (added to Settings' Privacy/About section);
 *   (2) the >700M-monthly-active-user clause requiring a separate Meta license does not apply to
 *   this app; (3) the Acceptable Use Policy (no illegal use, no CSAM, etc.) applies to end users,
 *   same as any other software license's AUP.
 * - GGUF conversion/quantization published by: [bartowski/Llama-3.2-1B-Instruct-GGUF]
 *   (https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF) — a widely-used, actively
 *   maintained community conversion; the repo inherits the base model's `llama3.2` license (no
 *   additional restriction added by the conversion).
 * - Download verified as a direct, unauthenticated HTTPS GET (no HF token/login required for
 *   this public repo) via `resolve/main/<filename>`, confirmed via `curl -IL`.
 * - SHA-256 computed locally after downloading the file once (`shasum -a 256`, 807,694,464
 *   bytes, matching the server's `Content-Length`), not taken from any third-party listing.
 */
object Llama32ModelSpec {
    const val MODEL_VERSION = 1
    const val FILENAME = "llama32_1b_instruct_q4.gguf"
    const val DOWNLOAD_URL =
        "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
    const val SHA256 = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83"
    const val CONTEXT_SIZE = 2048
}
