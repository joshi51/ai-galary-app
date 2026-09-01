# Privacy

This document is the full write-up behind the in-app Privacy section (Settings → Privacy) and
Phase 11's audit. Every claim below was verified by reading the actual code (grepping every network
call site, every `logger.*` call, every `build.gradle.kts`, and the full `AndroidManifest.xml`), not
assumed from the architecture design. Where something couldn't be verified, that's said explicitly
— per this project's standing rule, nothing here claims "fully offline" or "fully private" without
the verification to back it.

## What never leaves the device

- **Photos.** Indexing, face detection, embedding generation, and search all read photos through
  Android's `MediaStore`/`ContentResolver` in place. No code path anywhere in the dependency graph
  uploads photo bytes, thumbnails, or metadata anywhere.
- **Face embeddings.** Generated on-device (TFLite) and stored only in the app's local Room
  database. Never serialized to a network call.
- **Natural-language search and photo organization.** The LLM (llama.cpp, running in-process via
  JNI) never makes a network call — its only inputs are the prompt text this app constructs and the
  GBNF grammar; its only output is text, parsed locally into a tool call.

## What does use the network — and only that

The `INTERNET` permission exists for exactly two explicit, user-initiated actions, both in
Settings, both gated behind a visible "Download" button the user must tap:

1. **Face-embedding model** (FaceNet, 23,705,216 bytes) — `HttpModelDownloader`.
2. **Search-assistant model** (Llama-3.2-1B-Instruct GGUF, 807,694,464 bytes) — `HttpLlmModelDownloader`.

Both stream to a temp file first (never mistaking a partial download for a complete model), verify
a pinned SHA-256 hash before the model is considered ready, and are the *only* two `HttpURLConnection`
usages anywhere in the codebase — verified by grepping every module for network client usage, not
assumed from the two Settings buttons alone. Every other feature (indexing, face detection,
embeddings, clustering, deterministic search, duplicate/similarity detection, NL search, and
AI-assisted organization) works with the device fully offline once those two downloads are done.

## Permissions

| Permission | Why | When requested |
|---|---|---|
| `READ_MEDIA_IMAGES` (33+) / `READ_EXTERNAL_STORAGE` (<33) | Read photo bytes/metadata via MediaStore | First time the Photos tab is opened — never at app launch |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Support Android 14+'s partial-photo-access grant | Same as above |
| `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="28"`) | Only needed pre-scoped-storage (API ≤28) for organization file moves | Same as above |
| `INTERNET` | The two explicit model downloads only | Never requested at runtime (a manifest permission) — but only ever *used* behind the Download buttons |

No location permission exists at all. Phase 6's "near a saved location" search filter reads GPS
coordinates already present in a photo's EXIF metadata (captured by the camera app, not this app),
never the device's live location.

## Database and storage

- The Room database and any cached data live in Android's app-private storage
  (`/data/data/com.localphotoai.photomanager/`), readable only by this app's own UID on a
  non-rooted device, and deleted automatically on uninstall.
- `android:allowBackup="false"` on `<application>` — the database cannot be extracted via
  `adb backup` or Android's automatic cloud/system backup.
- **Photo thumbnails are not written to a persistent cache.** This is a correction from an earlier
  version of this document, which assumed Coil's default `ImageLoader` disk-caches thumbnails the
  way Coil 2 used to. Phase 12 verified live that it does not (Coil 3 ships no disk cache by
  default, and — separately — doesn't disk-cache local `content://` fetches at all regardless of
  configuration; see [PERFORMANCE.md](PERFORMANCE.md)). Thumbnails are decoded on demand and held
  only in memory while visible.
- Temporary model-download files are always cleaned up: `HttpModelDownloader`/`HttpLlmModelDownloader`
  download to a temp file first, and either rename it to the final model file on success or
  explicitly `delete()` it on every failure/exception path.

## Logging

- `Logger`/logcat is used throughout for structured, tagged, leveled logging — never persisted to
  disk or uploaded anywhere.
- **A real leak was found and fixed during Phase 11's audit:** `MlKitFaceDetectorImpl`,
  `FaceNetEmbeddingGenerator`, and `MobileNetV3EmbeddingGenerator` each logged a bitmap-decode
  failure with the photo's `content://` URI interpolated into the message — a URI that resolves
  directly back to that specific photo. Fixed by dropping the identifier; the failure is still
  logged, just without anything that lets a logcat reader pull the photo back up.
- The NL-search trace channel (`LlmTrace`, Phase 8) logs the user's raw query text and a numeric
  photo/row id — an explicit, documented Phase 8 design decision (only "counts, tool names, and
  user-entered parameter text — never filenames, paths, or coordinates"), logcat-only, never
  persisted or uploaded. Reviewed again in Phase 11 and left as-is rather than silently unmentioned.

## Analytics and telemetry

**None exists.** Every `build.gradle.kts` in the project was grepped for Firebase, Crashlytics,
Mixpanel, Amplitude, Segment, or any other analytics/crash-reporting SDK — zero matches. "Disabled
by default" is trivially, verifiably true because there is nothing to disable. This also means,
honestly: there is currently no path for a developer to learn about a crash happening in the field
at all (see [ENGINEERING_REVIEW.md](ENGINEERING_REVIEW.md)'s Observability section).

## Diagnostics screen

Settings → Privacy → "View diagnostics" shows, on demand (not live-updating): face-embedding and
search-assistant model download state and version, the bundled similarity model's version, indexed
photo/face/people/duplicate-group/similar-group counts, total photo library size, and database
size — the same underlying data already surfaced through the LLM's `get_storage_statistics` tool,
reused rather than duplicated.

## What this document does not claim

- **Not a claim of accuracy.** Nothing here says face recognition, search, or organization is
  *correct* — only that the data those features operate on stays on-device. See
  [ENGINEERING_REVIEW.md](ENGINEERING_REVIEW.md) and the plan doc for the honest, repeated
  limitation that no real human face photo has ever been available to validate recognition quality.
- **Not a claim about every possible Android device.** ML Kit's face detector depends on Google
  Play Services being present on the device — this app does not itself make a network call, but a
  Play-Services-free device (GrapheneOS, AOSP-only) would need a different face-detection backend
  to run this app's face features at all.
- **Not independently audited.** This document and the findings behind it come from this project's
  own development sessions reading its own code — a real audit by a party with no stake in the
  outcome would be more credible than a self-audit, however carefully done.
