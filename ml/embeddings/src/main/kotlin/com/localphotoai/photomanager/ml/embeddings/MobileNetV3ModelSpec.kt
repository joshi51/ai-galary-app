package com.localphotoai.photomanager.ml.embeddings

/**
 * The bundled (not downloaded — see ARCHITECTURE.md's Phase 7 notes) image-similarity model's
 * identity. Converted from `tf.keras.applications.MobileNetV3Small` (Apache 2.0, official
 * TensorFlow/Keras team), `include_top=False, pooling="avg", include_preprocessing=True` — the
 * model itself applies MobileNetV3's expected input scaling, so raw `[0,255]` RGB pixel values
 * are fed directly. OUTPUT_SIZE (576) was verified by printing `model.output_shape` during the
 * actual conversion, not assumed.
 */
object MobileNetV3ModelSpec {
    const val MODEL_VERSION = 1
    const val ASSET_FILENAME = "mobilenet_v3_small_feature_vector.tflite"
    const val INPUT_SIZE = 224
    const val OUTPUT_SIZE = 576
}
