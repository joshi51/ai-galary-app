# Performance

All numbers below are **measured**, not estimated — either live on an Android emulator (AVD
`phase1_test`, API 35, arm64-v8a, running on a shared/contended host — not a clean benchmark rig)
or via direct `sqlite3` timing against a pulled copy of the on-device database. Where a number
comes from an earlier phase's own testing session rather than Phase 12's consolidated pass, that's
noted explicitly. Nothing here is a projection or a "should be roughly" guess.

## Phase 12 profiling run (the primary reference numbers)

**Test setup:** a 3,322-photo library (2,377 photos carried over from Phases 6/7/9's own testing +
945 new synthetic JPEGs/PNGs generated for this pass, at realistic phone-camera resolutions —
1600×1200 to 1920×1080, a deliberate change from earlier phases' tiny 120×120 test images, since
this phase specifically needed decode/bitmap-memory costs to be visible), including 40 exact-
duplicate pairs, 30 re-encoded near-duplicates, and 25 screenshot-named files. 300 synthetic `faces`
rows were inserted directly against real, decodable photos (the same technique Phase 4/5 used) to
get real embedding-generation/clustering throughput, since — see [Known Limitations](#known-limitations) —
no real human face photo has been available in any session that built this project.

| Metric | Result | Method |
|---|---|---|
| Indexing (945 new photos into a 2,377-photo library — i.e. incremental, the common real-world case) | 6.489s, **~145.6 photos/sec** | `adb logcat` timestamps, `IndexWorker` start → `IndexPhotosUseCase` completion |
| Face detection | 90.15s for 945 photos, **~10.5 photos/sec** | same, realistic resolution (not Phase 3's tiny test images — see note below) |
| Hashing (SHA-256 + dHash) | ~15.4s for 946 photos, **~61 photos/sec** | same |
| Duplicate grouping (exact) | ~48ms for 91 groups | same |
| Near-duplicate/burst grouping | ~2.66s for 121 groups | same |
| Similarity embedding (MobileNetV3) | 27.334s for 945 photos, **~34.6 photos/sec** | same |
| Visually-similar grouping | ~1.95s | same |
| Face embedding generation (FaceNet) | 42.056s for 300 faces, **~140ms/face (~7.1/sec)** | same, on real photos via directly-inserted face rows |
| Clustering | ~484ms for 300 faces | same |
| Database size | 15MB | `du -sh` on the app's `databases/` dir — 3,322 photos + 300 faces + every group/embedding table |
| Storage consumption | 126MB photo library + 809MB app-private storage (793MB of which is the two downloaded models) | `du -sh` |
| Memory | 199MB PSS / 313MB RSS, no `OutOfMemoryError` | `dumpsys meminfo` at the end of the full pipeline run |
| Search latency (person filter) | 4ms at 3,322-photo scale | `sqlite3 .timer on` against a pulled DB copy |
| Search latency (date range) | 1ms at 3,322-photo scale, `EXPLAIN QUERY PLAN` confirms index usage | same |
| `get_storage_statistics` tool query | 73ms | `LlmTrace` `tool_result` log line |
| LLM response latency (NL search, warm model) | 16,092ms total | real query through the Search UI, `LlmTrace` `response=` log line |
| UI responsiveness during background work | 0 ANRs, 0 "Skipped N frames" warnings | `adb logcat`, including a deliberate rapid-tab-switch stress test mid-pipeline |
| Battery impact | **unmeasurable** | `dumpsys batterystats` reports every power-model field `-1 (unsupported)` on this AVD — a known emulator limitation |

### Face detection: why 90.15s, not Phase 3's ~16/sec

Phase 3 originally measured ~16 photos/sec, but that number came from 120×120 synthetic test
images — far smaller than any real photo. This phase's 90.15s / 945-photo run used realistic
1600×1200–1920×1080 source images (still decoded down to `MlKitFaceDetectorImpl`'s existing
1024px-longest-side cap before inference, per Phase 3's design), and the resulting ~10.5 photos/sec
is the more representative number. This isn't a code regression — re-reading the decode path
confirmed it's already minimal (bounded `inSampleSize`, immediate `recycle()`, a reused ML Kit
client) — it's ML Kit's own `PERFORMANCE_MODE_ACCURATE` inference cost at a realistic input size, a
deliberate accuracy-over-speed tradeoff made explicitly in Phase 3. See
[ENGINEERING_REVIEW.md](ENGINEERING_REVIEW.md) finding 7 for why this wasn't "fixed" by silently
switching to a faster mode.

## A real bug found and fixed by this profiling pass

`PhotoDao.getPhotosNeedingHash()` (`SELECT ... WHERE contentHash IS NULL`) never excluded rows
whose `hashError` was already set — unlike every other pipeline stage (face detection, face
embeddings, similarity embeddings), which correctly exclude permanent failures. One permanently
undecodable photo already present in the test library was silently re-hashed, and re-failed, on
*every single* incremental scan pass, forever:

```
# Before the fix — two separate scan passes, same photo, same failure, every time:
14:19:44.156  W HashPhotosUseCase: Hashing failed for photo 327
...
14:24:24.865  W HashPhotosUseCase: Hashing failed for photo 327

# After changing the query to `WHERE contentHash IS NULL AND hashError IS NULL`:
14:40:53.961  D WM-WorkerWrapper: Starting work for HashWorker
14:40:54.053  I WM-WorkerWrapper: Worker result SUCCESS   # no failure line — 0 photos needed hashing
```

This is exactly the kind of unbounded, silently-wasted background CPU/battery cost that compounds
as a real library accumulates even one bad file over its lifetime — fixed with a one-line query
change, no schema/migration needed.

## An investigated optimization that turned out to be a no-op

Hypothesis: Coil 3's `ImageLoader` (unlike Coil 2's) ships no thumbnail disk cache by default, so
every Photos-grid re-render was re-decoding each photo from scratch. Live testing confirmed the
cache directory *was* empty after repeated scrolling. A `SingletonImageLoader.Factory` with an
explicit 100MB disk cache + memory cache was implemented, built, installed, and re-tested — the
cache directory **still stayed empty**. Root cause: Coil's disk cache only avoids re-*fetching*
bytes (a network concern); a local `content://` file's bytes are already free to re-read, so Coil
never writes local fetches to disk regardless of configuration — only the in-memory decoded-bitmap
cache (already present, no code needed) helps for local URIs. The change was reverted rather than
shipped as a no-op "fix" — see [ENGINEERING_REVIEW.md](ENGINEERING_REVIEW.md) for the full account,
including the correction this required to Phase 11's privacy documentation (which had assumed a
Coil disk cache existed).

## Numbers from earlier phases (for historical continuity)

These predate Phase 12's consolidated pass and used different (usually smaller/tinier) test data —
kept here for continuity with each phase's own verification record, not as current headline numbers:

- **Indexing** (Phase 2): ~90 photos/sec on 305 photos with real EXIF/dimension extraction.
- **Face detection** (Phase 3): ~16 photos/sec on 304 tiny (120×120) synthetic photos.
- **Face embeddings** (Phase 4): ~1.0s one-time interpreter init, ~111ms/face (~9/sec) steady-state
  on 60 faces from 800×600 source photos — the same order of magnitude as Phase 12's ~140ms/face on
  larger source photos.
- **Duplicate/similarity pipeline** (Phase 7): ~2,377 photos, full chain (index→hash→embed→group)
  in ~80s total; 50/50 intentional exact duplicates correctly detected.
- **Search at scale** (Phase 6): ~10,000 synthetic photos/~5,775 faces — person/date/location
  queries all under 8ms; `EXPLAIN QUERY PLAN` confirmed the Phase 6 indexes change a full table scan
  into an index seek (the honest evidence, since wall-clock time didn't yet show a difference at
  that scale).
- **LLM cold/warm latency** (Phase 8): ~50–59s cold (model load + first generation), ~15–20s warm,
  on a memory/CPU-constrained shared host — consistent with Phase 12's 16.1s warm measurement.

## Known Limitations

- **Battery impact is genuinely unmeasured**, not conservatively estimated — `dumpsys batterystats`
  reports every power-model field as unsupported on every AVD used across this project's history.
  A real measurement needs a physical device or Android Studio's Energy Profiler against one.
- **Face/embedding/clustering throughput is real; quality is not measured.** All synthetic-face
  numbers come from directly-inserted rows on real (but face-less, generated) photos — the same
  root limitation as every ML phase since Phase 3: no real human face photo has been available in
  any session.
- **The 3,322-photo library is smaller than Phase 6/7's dedicated 10,000-photo scale test** — this
  phase's contribution is realistic-*resolution* throughput, which the smaller/tinier-image tests
  didn't measure; it doesn't re-prove search/duplicate-detection scaling already established there.
- **No batch-processing/concurrency change was made** to face detection or embedding generation —
  see [ENGINEERING_REVIEW.md](ENGINEERING_REVIEW.md) finding 7 for why.
