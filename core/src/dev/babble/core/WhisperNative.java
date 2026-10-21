package dev.babble.core;

import java.io.File;
import java.nio.charset.StandardCharsets;

/** JNI surface of native/babble_jni.cpp. */
public final class WhisperNative {
    private WhisperNative() {}

    static native void setVerbose(boolean verbose);

    static native long init(String modelPath, boolean gpu, boolean flashAttn);

    static native void free(long ctx);

    static native byte[] transcribe(long ctx, float[] pcm, int n, String prompt, int threads, int audioCtx, int maxTokens);

    static native byte[] gpuDevices();

    /** Loads the first library that links successfully (CUDA build first, then CPU). Returns the one used. */
    static File load(File... candidates) {
        UnsatisfiedLinkError last = null;
        for (File lib : candidates) {
            if (lib == null || !lib.isFile()) continue;
            try {
                System.load(lib.getAbsolutePath());
                return lib;
            } catch (UnsatisfiedLinkError e) {
                Log.info("Could not load " + lib.getName() + ": " + e.getMessage());
                last = e;
            }
        }
        throw last != null ? last : new UnsatisfiedLinkError("No Babble native library for this platform");
    }

    /** A loaded whisper model. Not thread-safe: use from one thread. */
    public static final class Model implements AutoCloseable {
        private long ctx;
        public final String name;
        public final boolean gpu;
        public final String devices;

        Model(String path, String name, boolean useGpu, boolean flashAttn) {
            this.name = name;
            long c = useGpu ? init(path, true, flashAttn) : 0;
            boolean onGpu = c != 0;
            if (c == 0) c = init(path, false, false);
            if (c == 0) throw new IllegalStateException("whisper failed to load model " + path);
            String gpus = new String(gpuDevices(), StandardCharsets.UTF_8);
            this.ctx = c;
            this.gpu = onGpu && !gpus.isEmpty();
            this.devices = this.gpu ? gpus : "CPU";
        }

        public String transcribe(float[] pcm, int n, String prompt, int threads, int audioCtx) {
            if (ctx == 0) throw new IllegalStateException("model closed");
            // Speech is ~3 words/s and ~1.5 tokens/word; allow generous headroom but no runaway loops.
            int maxTokens = 8 + n * 8 / 16000;
            byte[] out = WhisperNative.transcribe(ctx, pcm, n, prompt, threads, audioCtx, maxTokens);
            return out == null ? "" : new String(out, StandardCharsets.UTF_8).trim();
        }

        @Override
        public void close() {
            if (ctx != 0) free(ctx);
            ctx = 0;
        }
    }
}
