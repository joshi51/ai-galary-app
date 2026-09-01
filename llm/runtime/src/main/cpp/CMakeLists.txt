cmake_minimum_required(VERSION 3.22)
project(llm_jni)

# AGP passes CMAKE_BUILD_TYPE=Debug for an Android debug variant, which disables optimizations
# (-O0) — fine for our own thin JNI wrapper, but catastrophic for llama.cpp/ggml's CPU-bound
# matrix-multiply-heavy inference: an unoptimized build measured ~100s for a single short
# generation on-device during Phase 8 verification, entirely a build-flag problem, not a model
# or algorithm issue. Force Release optimization for the native build regardless of the Android
# build variant — there's no real benefit to debugging ggml/llama.cpp internals with a native
# debugger in this project, so trading that away for correct-order-of-magnitude performance is
# the right default.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# Trim llama.cpp's build to only what we need: no server, no CLI examples, no tests.
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(llm_jni SHARED llm_jni.cpp)

find_library(log-lib log)

target_link_libraries(llm_jni
    llama
    ggml
    ${log-lib}
)

target_include_directories(llm_jni PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
)
