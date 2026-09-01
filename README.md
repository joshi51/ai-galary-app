# Local AI Photo Intelligence & Manager

A privacy-first Android photo manager built as a working example of **on-device AI**: computer
vision (face detection/recognition), vector search, a local LLM doing agentic tool-calling, and a
software architecture built around the constraint that none of it may leave the device.

Photo indexing, face detection, face-embedding generation, clustering, duplicate/similarity
detection, natural-language search, and AI-assisted photo organization all run entirely on-device —
no mandatory cloud AI APIs (no OpenAI, Anthropic, Gemini, AWS AI, Firebase AI), and no analytics or
telemetry SDK of any kind. See [PRIVACY.md](docs/PRIVACY.md) for exactly what was verified, not just
designed, to stay on-device.

**What this project demonstrates, concretely:**

- **On-device computer vision** — ML Kit face detection → TFLite (FaceNet) face embeddings →
  greedy nearest-centroid clustering into people, with user-correctable merge/split/mark-incorrect
  actions and no automatic name assignment.
- **On-device vector search** — brute-force cosine similarity over Room-stored embedding vectors,
  used for both face clustering and visual-similarity/near-duplicate photo grouping, with the
  scaling tradeoff (and its documented upgrade path) made explicit rather than hidden.
- **A local LLM doing agentic tool-calling, not open-ended generation** — llama.cpp running
  Llama-3.2-1B-Instruct in-process via JNI, constrained by a GBNF grammar to five validated tool
  schemas (`:tools`), with the LLM never given direct filesystem or database access — only
  validated tool calls, and every destructive filesystem operation gated behind the OS's own
  write-consent dialog.
- **Privacy-first architecture as a load-bearing constraint, not a marketing line** — verified
  (not assumed) in [PRIVACY.md](docs/PRIVACY.md): every network call site in the codebase, every
  manifest permission, every logging call site that could leak a photo identifier.

## Status

All 13 planned phases are complete — indexing, face detection/recognition, deterministic and
natural-language search, duplicate/similarity detection, AI-assisted organization with undo,
privacy hardening, on-device performance profiling, and this final engineering review. See
[docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md](docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md)
for the full phase-by-phase record — what was built, how it was verified, and every known
limitation, per phase, including negative findings (things tried and reverted after verification).

## Documentation

| Doc | What's in it |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Full system design: module structure, data flows, DB schema, background processing, ML/security architecture |
| [ENGINEERING_REVIEW.md](docs/ENGINEERING_REVIEW.md) | Senior-level review across 21 dimensions: what was found, severity, what was fixed, what's deferred and why, future roadmap |
| [PERFORMANCE.md](docs/PERFORMANCE.md) | Real, measured on-device benchmark numbers — indexing/detection/embedding throughput, memory, search/LLM latency, a real bug found and fixed, an optimization tried and reverted |
| [PRIVACY.md](docs/PRIVACY.md) | The full privacy audit: what never leaves the device, what does (and only that), permissions, logging, a leak found and fixed |
| [TESTING.md](docs/TESTING.md) | What's unit tested and why, what isn't and how it's verified instead, current coverage |
| [DEVELOPMENT.md](docs/DEVELOPMENT.md) | Module graph, coding conventions, how to add a migration/tool/feature safely |
| [MODEL_SETUP.md](docs/MODEL_SETUP.md) | The three models this app uses, their provenance/licensing, how to download or manually install them |
| [docs/superpowers/specs/2026-08-29-local-ai-photo-manager-design.md](docs/superpowers/specs/2026-08-29-local-ai-photo-manager-design.md) | Original project spec: goals, constraints, non-negotiable principles |
| [docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md](docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md) | Phase-by-phase implementation plan and the full build/verify record for every phase |
| [CLAUDE.md](CLAUDE.md) | Project-specific working preferences (commit policy, testing scope) |

## Tech stack

- Kotlin, Jetpack Compose, Android Jetpack architecture (MVVM), Coroutines/Flow
- Hilt for dependency injection, Compose Navigation
- Room, MediaStore, WorkManager, DataStore (introduced in later phases as needed)
- ML Kit, TensorFlow Lite, llama.cpp (local LLM) — introduced from Phase 3 onward
- Gradle 9.7.1, Android Gradle Plugin 9.3.2 (built-in Kotlin support — no separate `kotlin-android` plugin), Kotlin 2.3.20, KSP 2.3.11
- compileSdk / targetSdk 37, minSdk 26

## Project structure

```
app/                  App entry point: Application/Activity, DI wiring, NavHost, theme
core/common/          Logger, AppResult/AppError, AppDispatchers — pure Kotlin, no Android deps
core/ui/              Shared Compose theme, components, navigation destinations
domain/               Use cases, domain models, repository interfaces — pure Kotlin, no Android deps
data/preferences/     DataStore-backed settings repository
data/database/        Room: entities, DAOs, AppDatabase + migrations, repository implementations
data/media/           MediaStore access, WorkManager workers/schedulers, pipeline chaining
ml/vision/            ML Kit face detection
ml/embeddings/        TFLite face-embedding (FaceNet) + similarity-embedding (MobileNetV3), model downloaders
llm/orchestration/     Plain Kotlin: GBNF grammar, tool-call parsing, retry/fallback loop
llm/runtime/           llama.cpp JNI bridge, model download/lifecycle
tools/                 The LLM's only interface to the app: five validated Tool implementations
fsops/                 The only module with real filesystem write access: plan validation + execution + undo
feature/home/          Home screen
feature/photos/        Photos, indexing progress, duplicate/similar-photo review
feature/people/        People, face clustering review (name/merge/split/mark-incorrect)
feature/search/        Deterministic + natural-language search, AI organization review, operation history/undo
feature/settings/       Theme, AI model downloads, Privacy section, diagnostics screen
```

Module boundaries and the reasoning behind them are in
[ARCHITECTURE.md](docs/ARCHITECTURE.md) §11 and [DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Prerequisites

- JDK 17+ on `PATH` (no explicit Gradle toolchain is pinned — see
  [ARCHITECTURE.md](docs/ARCHITECTURE.md) §23 for why)
- Android SDK with `platforms;android-37.1` and a recent build-tools version
  installed, either via Android Studio or `sdkmanager`
- A `local.properties` file at the repo root pointing `sdk.dir` at your SDK
  (gitignored — create it yourself, e.g. `sdk.dir=/Users/you/Library/Android/sdk`)

## Build

```bash
./gradlew build
```

Build just the app module (faster while iterating):

```bash
./gradlew :app:assembleDebug
```

Clean build outputs:

```bash
./gradlew clean
```

## Test

Run all unit tests across every module:

```bash
./gradlew test
```

Run the four modules with real test suites (166 tests total — domain use cases/algorithms, tool
validation, LLM orchestration, filesystem-operation validation):

```bash
./gradlew :domain:test :tools:test :llm:orchestration:test :fsops:test
```

There is no instrumented/UI test suite by design — this project only unit tests business logic
(domain use cases, clustering/matching logic, tool and filesystem-operation validation), not UI,
ViewModels, DI wiring, or Room DAOs. See [TESTING.md](docs/TESTING.md) for the full breakdown, the
philosophy behind the split, and a real bug this scope let through as an honest cost example.

## Run

**Via Android Studio:** open the project root and run the `app` configuration
on a device or emulator (API 26+; a Google Play system image is recommended
for later phases that need ML Kit/Play Services).

**Via command line**, with a device or emulator already running and visible
to `adb devices`:

```bash
./gradlew :app:installDebug
adb shell am start -n com.localphotoai.photomanager/.MainActivity
```

**Create and boot a headless emulator** (useful for CI or a machine without
Android Studio):

```bash
sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-35" "system-images;android-35;google_apis_playstore;arm64-v8a"
avdmanager create avd -n dev -k "system-images;android-35;google_apis_playstore;arm64-v8a"
emulator -avd dev -no-window -no-audio -no-boot-anim &
adb wait-for-device
```

## Debug on a real device

**1. Enable Developer Options and USB debugging on the device:** Settings →
About phone → tap "Build number" 7 times → back out to Settings →
Developer options → enable "USB debugging".

**2. Connect the device via USB** (or set up wireless debugging, step 5) and
authorize the "Allow USB debugging?" prompt that appears on the device the
first time you connect.

**3. Confirm the device is visible:**

```bash
adb devices
# should list the device as "device", not "unauthorized" or "offline"
```

If it shows `unauthorized`, check the device screen for the authorization
prompt and accept it. If nothing is listed, check the USB cable (must
support data, not charge-only) and that the correct USB driver is installed
(mainly a Windows/Linux concern; macOS generally works out of the box).

**4. Install, launch, and view logs** — same commands as an emulator, since
`adb` treats a real device and an emulator identically:

```bash
./gradlew :app:installDebug
adb shell am start -n com.localphotoai.photomanager/.MainActivity
adb logcat --pid=$(adb shell pidof com.localphotoai.photomanager)
```

If multiple devices/emulators are connected at once, target one explicitly
with `-s <device-serial>` (get the serial from `adb devices`):

```bash
adb -s <device-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device-serial> shell am start -n com.localphotoai.photomanager/.MainActivity
```

**5. Wireless debugging (no cable needed, Android 11+):** on the device, go
to Developer options → Wireless debugging → enable it, then either:

- **Pair with a QR code:** Developer options → Wireless debugging → "Pair
  device with QR code", then scan it from your terminal with a tool that
  supports it, or
- **Pair with a pairing code:**
  ```bash
  adb pair <device-ip>:<pairing-port>   # code shown on the device's pairing screen
  adb connect <device-ip>:<debug-port>  # port shown on the main Wireless debugging screen
  adb devices                           # confirm it's connected
  ```
  Both devices must be on the same Wi-Fi network.

**6. Debugging with breakpoints:** for step-through debugging (not just
logcat), open the project in Android Studio, select the connected device
from the device dropdown, and use Run → Debug 'app' instead of the CLI
`installDebug`/`am start` flow above.

**7. Uninstall when done:**

```bash
adb uninstall com.localphotoai.photomanager
```

## Debug via Android Studio

**1. Open the project:** File → Open, select the repo root (the folder with
`settings.gradle.kts`). Let Gradle sync finish before doing anything else —
watch the status bar / the "Build" tool window for errors.

**2. Select a target device:** use the device dropdown in the toolbar to
pick a connected real device (see the section above) or a virtual device.
To create/manage virtual devices: Tools → Device Manager → "Create Device".

**3. Run without debugging:** click the green ▶ Run button (or
Run → Run 'app') with the `app` run configuration selected.

**4. Run with the debugger attached:** click the 🐞 Debug button instead
(or Run → Debug 'app'). This is what lets breakpoints actually stop
execution, as opposed to `installDebug` from the CLI which just installs
and launches without attaching a debugger.

**5. Set breakpoints:** click in the gutter (left margin) next to a line
number in the editor — a red dot marks the breakpoint. Composables,
ViewModels, and use cases all support breakpoints normally; suspend
functions pause correctly too. Use the **Debug** tool window
(⌥⌘6 / Alt+6) to step over/into/out (F8/F7/⇧F8), inspect variables, and
evaluate expressions once a breakpoint is hit.

**6. Attach the debugger to an already-running process:** if the app is
already running (e.g. launched via `adb shell am start`), use
Run → Attach to Process… and pick `com.localphotoai.photomanager` instead
of relaunching it.

**7. View logs:** the **Logcat** tool window (⌥⌘6 / Alt+6, or the tab next
to Debug) shows the same output as `adb logcat`, filterable by process,
log level, and a search/regex box — generally more convenient than the CLI
for anything beyond a quick check.

**8. Inspect UI state live:** Tools → Layout Inspector attaches to the
running app and shows the live Compose UI tree, recomposition counts, and
each node's semantics — useful once screens have real content from Phase 2
onward.

**9. Profile the app:** View → Tool Windows → Profiler (or the Profiler tab)
gives live CPU, memory, and network graphs for the running process —
relevant starting around Phase 3, once background ML work exists to profile.

## Useful Gradle tasks

```bash
./gradlew tasks              # list all available tasks
./gradlew lint               # Android lint across modules
./gradlew dependencies       # dependency tree for the root project
./gradlew :app:dependencies  # dependency tree for a specific module
```

## Local model setup

Face detection needs no download (it's provided by Google Play Services on the device). Face
embeddings and natural-language search each need one model, downloaded once from Settings, with a
pinned-hash integrity check before it's trusted. See [MODEL_SETUP.md](docs/MODEL_SETUP.md) for
sizes, licenses, provenance, and how to place a model manually (e.g. for offline development).

## Performance

Real, on-device measured numbers (not projections) live in [PERFORMANCE.md](docs/PERFORMANCE.md) —
indexing/face-detection/embedding throughput, memory, search and LLM latency, plus one real bug
found and fixed and one optimization tried and honestly reverted after live verification showed it
did nothing. Headline numbers from the most recent (3,322-photo, realistic-resolution) profiling
pass: indexing ~146 photos/sec, face detection ~10.5 photos/sec, face embeddings ~7/sec, deterministic
search 1–4ms, NL search 16s (warm), 199MB peak measured memory, zero ANRs under a deliberate
background-work stress test.

## Privacy

No photo, face embedding, or model data ever leaves the device, and no `INTERNET` permission is
used outside the two explicit, user-initiated model downloads in Settings. This is verified, not
just designed — see [PRIVACY.md](docs/PRIVACY.md) for the full audit (every network call site,
every permission, every logging call site checked for photo-identifying content, including a real
leak found and fixed), plus the in-app Privacy section (Settings → Privacy) and its Diagnostics
screen.

## Known limitations

The single most important one: **face/embedding/clustering accuracy has never been validated
against a real human face photo**, in any development session — only pipeline mechanics (decode,
throughput, error handling, resumability) have real verification. Every similarity/clustering
threshold in the codebase is named, documented, and honestly untuned against real data as a result.
This — along with every other known limitation (Room migrations have no automated test coverage,
battery impact is unmeasurable in this project's available environments, tool-selection reliability
for novel NL phrasings is unverified, and more) — is catalogued with severity ratings in
[ENGINEERING_REVIEW.md](docs/ENGINEERING_REVIEW.md).

## Roadmap

See [ENGINEERING_REVIEW.md](docs/ENGINEERING_REVIEW.md#future-roadmap) for the full, prioritized
list. The single highest-value next step: validating against a real (consented) photo library, to
finally measure — and recalibrate — recognition accuracy rather than just pipeline mechanics.
