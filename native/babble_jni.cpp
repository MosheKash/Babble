// Minimal JNI bridge between dev.babble.core.WhisperNative and whisper.cpp.
// whisper.cpp + ggml are linked statically into this library, so the mod ships a
// single self-contained .so per backend (CUDA / CPU).

#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "whisper.h"

static bool g_verbose = false;

static void log_cb(enum ggml_log_level level, const char *text, void *) {
    if (g_verbose || level >= GGML_LOG_LEVEL_ERROR) {
        fputs(text, stderr);
    }
}

static jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray out = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(out, 0, (jsize) s.size(), (const jbyte *) s.data());
    return out;
}

extern "C" {

JNIEXPORT void JNICALL Java_dev_babble_core_WhisperNative_setVerbose(JNIEnv *, jclass, jboolean verbose) {
    g_verbose = verbose;
    whisper_log_set(log_cb, nullptr);
}

JNIEXPORT jlong JNICALL Java_dev_babble_core_WhisperNative_init(JNIEnv *env, jclass, jstring jpath,
                                                                     jboolean gpu, jboolean flash) {
    whisper_log_set(log_cb, nullptr);
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = gpu;
    cp.flash_attn = flash;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(jpath, path);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL Java_dev_babble_core_WhisperNative_free(JNIEnv *, jclass, jlong ctx) {
    if (ctx) whisper_free((whisper_context *) ctx);
}

// Returns the UTF-8 transcript (as bytes, since NewStringUTF chokes on 4-byte chars), or null on failure.
JNIEXPORT jbyteArray JNICALL Java_dev_babble_core_WhisperNative_transcribe(
        JNIEnv *env, jclass, jlong jctx, jfloatArray jpcm, jint n, jstring jprompt, jint threads, jint audio_ctx,
        jint max_tokens) {
    whisper_context *ctx = (whisper_context *) jctx;
    if (!ctx) return nullptr;

    // whisper.cpp refuses input shorter than 1s, so zero-pad short clips.
    const int min_samples = WHISPER_SAMPLE_RATE + WHISPER_SAMPLE_RATE / 10;
    std::vector<float> pcm(n < min_samples ? min_samples : n, 0.0f);
    env->GetFloatArrayRegion(jpcm, 0, n, pcm.data());

    std::string prompt;
    if (jprompt) {
        const char *p = env->GetStringUTFChars(jprompt, nullptr);
        prompt = p;
        env->ReleaseStringUTFChars(jprompt, p);
    }

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads = threads;
    wp.language = "en";
    wp.translate = false;
    wp.no_context = true;
    wp.single_segment = true;
    wp.no_timestamps = true;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_special = false;
    wp.print_timestamps = false;
    wp.suppress_blank = true;
    wp.suppress_nst = true;
    wp.temperature_inc = 0.0f;  // never re-decode at higher temperature: one pass, predictable latency
    wp.max_tokens = max_tokens;  // caps runaway repetition loops (and their latency)
    wp.audio_ctx = audio_ctx;
    wp.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();

    if (whisper_full(ctx, wp, pcm.data(), (int) pcm.size()) != 0) return nullptr;

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) {
        text += whisper_full_get_segment_text(ctx, i);
    }
    return to_bytes(env, text);
}

// Comma-separated names of the GPU devices ggml can see (empty when running on CPU only).
JNIEXPORT jbyteArray JNICALL Java_dev_babble_core_WhisperNative_gpuDevices(JNIEnv *env, jclass) {
    std::string out;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU) {
            if (!out.empty()) out += ", ";
            out += ggml_backend_dev_description(dev);
        }
    }
    return to_bytes(env, out);
}

}  // extern "C"
