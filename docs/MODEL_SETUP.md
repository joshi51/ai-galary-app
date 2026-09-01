# Local Model Setup

This app uses three ML models, all of which run entirely on-device (see [PRIVACY.md](PRIVACY.md)).
None are bundled in the APK except the smallest one — the other two are downloaded once, over your
own connection, only when you explicitly tap a "Download" button in Settings.

| Model | Purpose | Size | Bundled or downloaded? |
|---|---|---|---|
| MobileNetV3-Small (feature vector) | Visual-similarity/near-duplicate embeddings | ~1.07MB, quantized | **Bundled** as an app asset |
| FaceNet 128-d | Face-recognition embeddings | 23,705,216 bytes (~22.6MB) | Downloaded on first use, from Settings |
| Llama-3.2-1B-Instruct (Q4_K_M GGUF) | Natural-language search / AI-assisted organization | 807,694,464 bytes (~770MB) | Downloaded on first use, from Settings |

## The normal path: download from Settings

1. Build and install the app (see the [README](../README.md)).
2. Open the app → **Settings** tab.
3. Under **AI Models**, tap **"Download face-embedding model"**. This needs a real internet
   connection (over your own network — never proxied through anything this project controls) the
   first time; it downloads to a temp file, verifies a pinned SHA-256 hash, and only then marks the
   model ready. A failed or interrupted download leaves no partial model in place — retry the same
   button.
4. Under **Search assistant model**, tap **"Download search assistant model"**. Same mechanism, a
   much larger (~770MB) file — expect this to take longer depending on your connection.
5. Once both show "ready," face detection/clustering and natural-language search/organization are
   fully functional offline from then on. The bundled MobileNetV3 model needs no action — it's
   already in the APK.

You never need to do anything for MobileNetV3; face detection (ML Kit) needs no model
download either — it's provided by Google Play Services on the device, not by this app.

## Model identity, provenance, and licensing

Full detail (including the license-compliance writeup) lives in each model's own spec object in
the codebase — this table is the summary:

| Model | Source | License | Spec file |
|---|---|---|---|
| FaceNet 128-d | [shubham0204/FaceRecognition_With_FaceNet_Android](https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android) (traces to `davidsandberg/facenet`, MIT) | MIT chain | `ml/embeddings/.../ModelFileStore.kt` (`FaceNetModelSpec`) |
| Llama-3.2-1B-Instruct GGUF | [bartowski/Llama-3.2-1B-Instruct-GGUF](https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF) (base model: Meta) | Llama 3.2 Community License (`llama3.2`) — **"Built with Llama" attribution required**, shown in Settings | `llm/runtime/.../Llama32ModelSpec.kt` |
| MobileNetV3-Small | Converted from `tf.keras.applications.MobileNetV3Small` (official TF/Keras team) | Apache 2.0 | `ml/embeddings/.../MobileNetV3ModelSpec.kt` |

Every downloaded model is verified against a SHA-256 hash pinned in the app's own source (not
fetched from a third party at runtime) before it's marked usable — the app will never silently
accept a corrupted or tampered download.

## Manually placing a model file (offline development, CI, or a device with no network)

If you need the app to have a model ready without going through the in-app download (e.g. building
in an environment with no outbound network access), push the file directly to the app's private
model directory using the exact filename each spec expects:

```bash
# Face-embedding model — filename must match FaceNetModelSpec.FILENAME exactly
adb push facenet_v1.tflite /data/local/tmp/facenet_v1.tflite
adb shell run-as com.localphotoai.photomanager \
  cp /data/local/tmp/facenet_v1.tflite /data/data/com.localphotoai.photomanager/files/models/facenet_v1.tflite

# Search-assistant model — filename must match Llama32ModelSpec.FILENAME exactly
adb push llama32_1b_instruct_q4.gguf /data/local/tmp/llama32_1b_instruct_q4.gguf
adb shell run-as com.localphotoai.photomanager \
  cp /data/local/tmp/llama32_1b_instruct_q4.gguf /data/data/com.localphotoai.photomanager/files/models/llama32_1b_instruct_q4.gguf
```

The app checks only file *presence and non-zero size* to decide a model is "ready" once it's
already on disk this way (the SHA-256 check only happens during the in-app download itself) — make
sure whatever file you push is actually the right one; verify with `shasum -a 256` against the
hashes in the spec files above before pushing, since nothing else will catch a mismatch for a
manually-placed file.

## Verifying a model is present

```bash
adb shell run-as com.localphotoai.photomanager \
  ls -la /data/data/com.localphotoai.photomanager/files/models/
```

Should list `facenet_v1.tflite` and/or `llama32_1b_instruct_q4.gguf` once downloaded/placed — the
sizes above are the exact expected byte counts.

## Known limitations

- The download-progress UI reports percent complete from the server's `Content-Length` header; a
  server that omits it (neither model's host currently does, as verified in Phase 4/8) would show
  no percentage, just an indeterminate "downloading" state.
- Manually-placed model files (the offline-development path above) bypass the SHA-256 check — only
  the in-app download path verifies file integrity.
