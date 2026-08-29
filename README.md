# Local AI Photo Intelligence & Manager

A privacy-first, AI-powered Android photo manager. All photo analysis and AI
processing — face detection, embeddings, clustering, duplicate detection,
natural-language search, and AI-assisted organization — runs entirely
on-device. No mandatory cloud AI APIs (no OpenAI, Anthropic, Gemini, AWS AI,
Firebase AI). The app is designed to work fully offline once its local
models are downloaded.

## Status

Currently **Phase 1** of 14 (basic Android app shell — no AI functionality
yet). See [docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md](docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md)
for the full phase-by-phase roadmap and what's done so far.

## Documentation

| Doc | What's in it |
|---|---|
| [docs/superpowers/specs/2026-08-29-local-ai-photo-manager-design.md](docs/superpowers/specs/2026-08-29-local-ai-photo-manager-design.md) | Project spec: goals, constraints, non-negotiable principles |
| [docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md](docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md) | Phase-by-phase implementation plan and progress |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Full system design: module structure, data flows, DB schema, background processing, ML/security architecture, toolchain notes |
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
domain/               Use cases, domain models, repository interfaces — pure Kotlin
data/preferences/     DataStore-backed settings repository implementation
feature/home/         Home screen
feature/photos/       Photos screen (indexing arrives in Phase 2)
feature/people/       People screen (face detection/clustering arrive in Phases 3–5)
feature/search/       Search screen (structured search in Phase 6, NL search in Phase 8)
feature/settings/     Settings screen (theme preference, privacy info)
```

Module boundaries follow [ARCHITECTURE.md](docs/ARCHITECTURE.md) §11; modules not
yet needed (`:data:media`, `:ml:*`, `:llm:*`, `:tools`, `:fsops`) are added in
the phases that introduce them.

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

Run tests for a specific module (e.g. the domain layer, where most business
logic lives — see [CLAUDE.md](CLAUDE.md) for this project's testing scope):

```bash
./gradlew :domain:test
./gradlew :core:common:test
```

There is no instrumented/UI test suite by design — this project only unit
tests business logic (domain use cases, clustering/matching logic, tool and
filesystem-operation validation), not UI, ViewModels, or DI wiring.

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

## Privacy

No photo, face, embedding, or model data ever leaves the device, and no
`INTERNET` permission is used outside an explicit, user-initiated model
download. See [ARCHITECTURE.md](docs/ARCHITECTURE.md) §6 and the in-app Privacy
section (Settings, from Phase 11 onward) for details.
