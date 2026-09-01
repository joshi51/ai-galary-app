# photo_app_prompt

Project: Local AI Photo Intelligence & Manager

> Progress tracking: each step below is marked against the corresponding phase's `Status` in
> [2026-08-29-local-ai-photo-manager.md](superpowers/plans/2026-08-29-local-ai-photo-manager.md).
> Steps 0–13 are done; Step 14 onward have not been run yet.

Step 0 — Master project instruction ✅ Done (Phase 0)

Give Claude Code this first and don’t ask it to implement anything yet.

```jsx
You are the lead software architect for a new Android application called Local AI Photo Intelligence & Manager.

The application is a privacy-first, AI-powered photo management application where all photo analysis and AI processing happens locally on the Android device.

Core principle

The application MUST NOT depend on paid cloud AI APIs.

Do not use OpenAI, Anthropic, Gemini, AWS AI services, Firebase AI, or any other mandatory cloud AI service.

The application should be capable of operating without an internet connection after all required models/dependencies have been installed.

Primary capabilities

The application will eventually support:

Scan photos available on the Android device.
Detect faces in photos.
Generate face embeddings locally.
Group similar faces into people.
Allow the user to name people.
Search photos by person.
Search photos containing multiple people.
Search photos by date and metadata.
Search photos using natural language.
Detect visually similar photos.
Detect duplicate photos.
Analyze image metadata.
Categorize photos.
Create organization suggestions.
Allow the user to review and approve organization actions.
Move/copy/rename photos only after explicit user confirmation.
Maintain an operation history.
Support undo for reversible organization operations.
Provide a completely local/private experience.
Proposed technology direction

Use:

Kotlin
Android
Jetpack Compose
Android Jetpack architecture
Coroutines
Flow/StateFlow
Room where appropriate
Android MediaStore for photo access
WorkManager for background processing
ML Kit where appropriate for on-device computer vision
TensorFlow Lite and/or ONNX Runtime where appropriate for local ML inference
A local embedding/vector-search solution appropriate for Android
A local LLM runtime for natural-language functionality

Do NOT blindly select libraries.

Before implementation, evaluate available options and explain the trade-offs.

Architecture principles

Use:

Clean Architecture where it provides value
MVVM or another well-justified presentation architecture
Clear separation between UI, domain logic, data access, ML inference and filesystem operations
Dependency injection
Testable business logic
Repository pattern where appropriate
Background processing for expensive operations
Incremental indexing rather than repeatedly scanning the entire gallery
Critical security principle

The LLM must NEVER receive unrestricted filesystem access.

All filesystem operations must happen through controlled application tools/services.

The AI can propose an action.

The application validates the action.

The user confirms destructive or modifying operations.

Only then does the execution layer perform the operation.

AI principle

Do not use an LLM for tasks that deterministic software can perform more reliably.

For example:

File size calculations → normal code
Date filtering → database query
Duplicate detection → deterministic hashing/perceptual hashing
Face detection → computer vision model
Face similarity → embeddings/vector similarity
Natural language interpretation → LLM

The LLM should primarily provide natural-language understanding, reasoning and orchestration.

Development philosophy

Build incrementally.

Do not implement the entire application in one step.

For every phase:

Inspect the existing code.
Explain the proposed implementation.
Implement the smallest production-quality increment.
Add tests.
Run/build the application.
Fix issues.
Document important architectural decisions.
Do not proceed to the next phase until the current phase is stable.
Code quality expectations

Write production-quality code.

Avoid:

unnecessary abstractions
premature optimization
giant classes
hardcoded paths
magic numbers
global mutable state
unnecessary dependencies
cloud dependencies
TODO-driven incomplete implementations

Prefer simple, maintainable designs.

Important

For now, DO NOT write application code.

First:

Inspect the repository.
Determine what currently exists.
Propose the complete architecture.
Identify major technical risks.
Recommend the local ML/AI stack.
Propose the development phases.
Explain the database/indexing strategy.
Explain how privacy and permissions will work.
Explain how background photo scanning will work.
Produce a detailed implementation plan.

Wait for my approval before implementing Phase 1.
```

Step 1 — Architecture ✅ Done (Phase 0.5)

After Claude gives you the architecture, give it:

```jsx
Based on the architecture you proposed, let’s now finalize the technical architecture before writing application features.

Create the following:

High-level architecture diagram.
Android module/package structure.
Data-flow diagram for photo ingestion.
Data-flow diagram for face recognition.
Data-flow diagram for natural-language search.
Data-flow diagram for organization actions.
Database schema.
Background-processing architecture.
ML model execution architecture.
Security and permission model.

For each major component, explain:

responsibility
inputs
outputs
dependencies
whether it runs on-device
failure scenarios

Pay particular attention to keeping the system completely local.

Do not implement anything yet.

At the end, identify architectural decisions that should be locked before development begins and any decisions that should deliberately remain replaceable.
```

Step 2 — Build the basic Android shell ✅ Done (Phase 1)

```jsx
Now start coding.

Implement Phase 1: the basic Android application foundation.

Requirements:

Kotlin
Jetpack Compose
Modern Android architecture
Dependency injection
Navigation
Proper application/theme structure
Repository/domain/data separation where appropriate
Logging
Error handling
Basic testing infrastructure

Create the initial screens:

Home
Photos
People
Search
Settings

Do NOT implement AI functionality yet.

The application should compile and launch successfully.

Before finishing:

run the relevant tests
build the application
fix compilation issues
ensure navigation works
document the architecture

Do not move to photo indexing yet.
```

Step 3 — Photo indexing ✅ Done (Phase 2)

This is your first genuinely useful feature.

```jsx
Implement Phase 2: local photo indexing.

The application should discover photos using Android MediaStore.

Requirements:

Request the appropriate Android photo/media permissions.
Do not copy the user’s photos unnecessarily.
Store references/URIs rather than duplicating image files.
Extract useful metadata:
URI
filename
MIME type
file size
width
height
creation date
modification date where available
EXIF information where permitted
location metadata where available and appropriate
Store metadata locally.
Detect newly added photos incrementally.
Detect deleted photos.
Avoid rescanning unchanged photos.
Implement background indexing using WorkManager.
Provide indexing progress to the UI.
Allow indexing to resume after interruption.

Create a Photos screen showing indexed photos.

Do not implement face recognition yet.

Add unit/integration tests for the indexing layer.

Measure and report approximate indexing performance on the test device/emulator.
```

Step 4 — Face detection ✅ Done (Phase 3)

```jsx
Now introduce computer vision.

Implement Phase 3: on-device face detection.

Requirements:

Use an on-device face detection solution.
Do not send images to any remote service.
Detect all faces in each indexed photo.
Store:
photo ID
bounding box
detection confidence
orientation/rotation information where required
Process photos in the background.
Do not block the UI.
Avoid repeatedly processing unchanged images.
Allow processing to resume after interruption.

Create a debug UI that allows me to open a photo and see detected face bounding boxes.

Do not attempt person identification/grouping yet.

Add tests and performance measurements.

Pay particular attention to:

memory usage
large images
device rotation
corrupted images
photos with many faces
background processing
cancellation
```

Step 5 — Face embeddings ✅ Done (Phase 4)

```jsx
This is the important ML step.

Implement Phase 4: local face embeddings.

First evaluate suitable on-device face embedding models that can run on Android.

Compare available options based on:

accuracy
model size
inference speed
Android compatibility
CPU/GPU/NPU support
licensing
offline capability
quantization options

Choose a model and explain why.

Then implement:

photo
→ detected face
→ crop/alignment
→ local embedding model
→ normalized embedding
→ local storage

Requirements:

embeddings must never leave the device
store embeddings efficiently
version embeddings by model version
allow embeddings to be regenerated if the model changes
avoid processing the same face repeatedly
handle model loading efficiently
avoid keeping large numbers of image tensors in memory

Create a benchmark for embedding generation.

Document the model and its license.

Do not implement automatic person grouping yet.
```

Step 6 — Automatically discover people ✅ Done (Phase 5)

```jsx
Now things get exciting.

Implement Phase 5: local face clustering and people discovery.

The application should group face embeddings into clusters representing likely individuals.

Do NOT assume that the clustering result is always correct.

Design the system so that:

one person can have multiple clusters initially
users can merge people
users can split incorrectly grouped people
users can mark a face as incorrect
unknown people remain unnamed
clusters can be recalculated
the clustering algorithm/model version is stored

Create a People screen showing:

person/cluster
representative photos
number of photos
number of detected faces
confidence/quality information where useful

Do not automatically assign names.

The UI should allow:

“Name this person”

and:

“Merge with another person”

Add tests for clustering and merge/split behavior.
```

Step 7 — Search by people ✅ Done (Phase 6)

```jsx
Implement Phase 6: people-based photo search.

Support:

photos containing a selected person
photos containing multiple selected people
person + date filtering
person + folder/location filtering where metadata exists

Examples:

“Show photos of Rahul.”

“Show photos of Rahul and Priya.”

“Show photos of Rahul from 2025.”

The first implementation should NOT use an LLM.

Use deterministic database/vector queries.

Optimize queries so the application remains responsive with a large photo library.

Add pagination/lazy loading.

Add appropriate indexes.
Test with a simulated large dataset.
```

Step 8 — Duplicate and similar-photo detection ✅ Done (Phase 7)

```jsx
This gives the app another strong capability.

Implement Phase 7: duplicate and visually similar photo detection.

Implement two separate concepts:

Exact duplicates
Visually similar photos

For exact duplicates, use a deterministic content hash.

For visually similar photos, evaluate an appropriate local image embedding model.

Do not use the LLM.

Support:

duplicate groups
similarity score
visually similar photos
burst-photo grouping where feasible

Create a UI that allows users to inspect groups.

Do NOT delete photos automatically.

Deletion must always require explicit user confirmation.

Document memory/performance implications.
```

Step 9 — Natural-language AI ✅ Done

```jsx
Only now should you introduce the LLM.

Implement Phase 8: local natural-language photo search.

The application must use a locally running/on-device LLM.

No paid or mandatory cloud AI API is allowed.

The LLM’s job is to translate natural language into structured search intent and/or invoke controlled application tools.

Examples:

“Show me photos of Rahul.”

“Show me photos of Rahul from 2025.”

“Find photos with Rahul and Priya.”

“Find my largest photos.”

“Find duplicate photos.”

“Show me photos taken in Delhi.”

Do not allow the LLM to directly access the filesystem or database.

Create a controlled tool layer.

Possible tools:

search_people
search_photos
search_by_date
search_by_location
find_duplicates
find_similar_photos
get_photo_metadata
get_storage_statistics

The tool layer must validate all parameters.

The LLM should produce structured tool calls rather than arbitrary code.

Create an architecture where the local LLM implementation can be replaced later without rewriting the rest of the application.

Add logging/tracing for:

user query
→ interpreted intent
→ selected tool
→ tool parameters
→ tool result
→ final response

Do not store unnecessary private photo content in logs.
```

Step 10 — AI-powered organization ✅ Done (Phase 9)

```jsx
This is where your original idea comes back.

Implement Phase 9: AI-assisted photo organization.

The user should be able to ask:

“Organize my photos.”

“Organize my screenshots.”

“Put photos from my Goa trip into an album.”

“Find photos that should be archived.”

The AI must NOT immediately modify files.

Instead implement:

User request
→ AI analysis
→ Organization Plan
→ Validation
→ User Review
→ User Confirmation
→ Execution

An organization plan should contain explicit operations such as:

MOVE
COPY
RENAME
CREATE_FOLDER
CREATE_ALBUM

Each operation must include:

source
destination
reason
confidence where applicable

Create a review UI where the user can:

approve all
reject all
approve individual operations
modify an operation

The execution layer must validate:

source exists
destination is valid
permissions
collisions
path traversal
duplicate destinations
unsupported operations

Never allow the LLM to execute arbitrary shell commands.
```

Step 11 — Undo / operation history ✅ Done (Phase 10)

```jsx
This is one of the features I’d definitely include because it demonstrates mature engineering.

Implement Phase 10: organization history and undo.

Every modifying operation must generate an operation record.

Track:

operation ID
timestamp
operation type
source
destination
previous state where required
result
failure reason
reversible/non-reversible status

Support:

“Undo last organization”

and allow users to inspect operation history.

Handle partial failures safely.

Example:

If 20 files are requested to move and 2 fail:

report 18 successful
report 2 failed
do not pretend the whole operation succeeded
maintain enough information to safely undo successful operations

Design this as a proper transaction-like workflow even though filesystem operations are not inherently transactional.
```

Step 12 — Privacy/security ✅ Done (Phase 11)

```jsx
This should be a major selling point.

Implement Phase 11: privacy and security hardening.

Audit the entire application for privacy risks.

Verify:

photos never leave the device
face embeddings never leave the device
LLM processing is local
logs do not contain sensitive photo data
analytics/telemetry is disabled by default
no unnecessary internet permission exists
permissions are requested only when required
database contents are protected appropriately
temporary image files are cleaned up
cached thumbnails are handled securely

Create a Privacy section in Settings explaining exactly what data remains on the device.

Add a diagnostic screen showing:

AI model status
local processing status
indexed photo count
face count
people count
database size
model versions

Do not claim something is “fully offline” unless it has actually been verified.
```

Step 13 — Performance optimization ✅ Done (Phase 12)

```jsx
This is particularly important for Android.

Implement Phase 12: performance optimization.

Profile the application using a realistic photo library.

Measure:

initial indexing time
incremental indexing time
face detection throughput
embedding generation time
memory usage
database size
search latency
LLM response latency
battery impact
storage consumption

Optimize:

image decoding
bitmap memory
model loading
batch processing
database queries
vector search
thumbnail generation
background work

The application must remain responsive while background AI processing occurs.

Do not optimize blindly. Profile first, then optimize based on measurements.
```

Step 14 — Final portfolio-grade review

```jsx
Finally, give Claude Code this.

Perform a complete senior/staff-level engineering review of the Local AI Photo Intelligence & Manager project.

Review:

Architecture
Code quality
Android architecture
ML pipeline
Face recognition accuracy risks
Vector search
Database design
Background processing
AI orchestration
Tool-calling architecture
Security
Privacy
Permission handling
Error handling
Performance
Battery usage
Memory usage
Offline capability
Test coverage
Observability
Maintainability

Identify architectural weaknesses and technical debt.

For every significant issue:

explain why it matters
rate its severity
propose a solution
implement the fix if it is safe to do so

Then produce:

architecture documentation
setup instructions
local model installation instructions
developer documentation
testing documentation
performance benchmark results
privacy documentation
known limitations
future roadmap

Finally, create a portfolio-quality README explaining the project as an example of:

On-device AI + Computer Vision + Vector Search + Local LLM + Agentic Tool Calling + Privacy-first Software Architecture

Do not make unsupported claims about AI accuracy or offline operation.
```
