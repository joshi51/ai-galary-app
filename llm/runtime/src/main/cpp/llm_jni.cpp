#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "llm_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct EngineHandle {
    llama_model* model;
    llama_context* ctx;
};

std::once_flag g_backendInitFlag;

void ensureBackendInitialized() {
    std::call_once(g_backendInitFlag, []() { llama_backend_init(); });
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_localphotoai_photomanager_llm_runtime_NativeLlamaBridge_nativeLoadModel(
    JNIEnv* env, jobject /* this */, jstring modelPath, jint contextSize) {
    ensureBackendInitialized();

    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    llama_model_params modelParams = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(path, modelParams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (model == nullptr) {
        LOGE("Failed to load model");
        return 0;
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = static_cast<uint32_t>(contextSize);
    llama_context* ctx = llama_init_from_model(model, ctxParams);
    if (ctx == nullptr) {
        LOGE("Failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto* handle = new EngineHandle{model, ctx};
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localphotoai_photomanager_llm_runtime_NativeLlamaBridge_nativeGenerateWithGrammar(
    JNIEnv* env, jobject /* this */, jlong handlePtr, jstring prompt, jstring grammarText, jint maxTokens) {
    auto* handle = reinterpret_cast<EngineHandle*>(handlePtr);
    if (handle == nullptr) return env->NewStringUTF("");

    const char* promptChars = env->GetStringUTFChars(prompt, nullptr);
    const char* grammarChars = env->GetStringUTFChars(grammarText, nullptr);
    std::string promptStr(promptChars);
    std::string grammarStr(grammarChars);
    env->ReleaseStringUTFChars(prompt, promptChars);
    env->ReleaseStringUTFChars(grammarText, grammarChars);

    const llama_vocab* vocab = llama_model_get_vocab(handle->model);

    std::vector<llama_token> tokens(promptStr.size() + 16);
    int nTokens = llama_tokenize(vocab, promptStr.c_str(), static_cast<int32_t>(promptStr.size()),
                                  tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (nTokens < 0) {
        LOGE("Prompt tokenization did not fit the buffer");
        return env->NewStringUTF("");
    }
    tokens.resize(nTokens);

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(handle->ctx, batch) != 0) {
        LOGE("llama_decode failed on prompt");
        return env->NewStringUTF("");
    }

    llama_sampler* grammarSampler = llama_sampler_init_grammar(vocab, grammarStr.c_str(), "root");
    if (grammarSampler == nullptr) {
        LOGE("Grammar failed to parse — refusing to sample without it");
        return env->NewStringUTF("");
    }
    llama_sampler* chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, grammarSampler);
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());

    std::string result;
    for (int i = 0; i < maxTokens; i++) {
        llama_token nextToken = llama_sampler_sample(chain, handle->ctx, -1);
        if (llama_vocab_is_eog(vocab, nextToken)) break;

        char buf[256];
        int len = llama_token_to_piece(vocab, nextToken, buf, sizeof(buf), 0, true);
        if (len > 0) result.append(buf, len);

        llama_batch nextBatch = llama_batch_get_one(&nextToken, 1);
        if (llama_decode(handle->ctx, nextBatch) != 0) {
            LOGE("llama_decode failed mid-generation");
            break;
        }
    }

    llama_sampler_free(chain);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_localphotoai_photomanager_llm_runtime_NativeLlamaBridge_nativeFreeModel(
    JNIEnv* env, jobject /* this */, jlong handlePtr) {
    auto* handle = reinterpret_cast<EngineHandle*>(handlePtr);
    if (handle == nullptr) return;
    llama_free(handle->ctx);
    llama_model_free(handle->model);
    delete handle;
}
