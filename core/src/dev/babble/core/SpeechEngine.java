package dev.babble.core;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Mic -> energy VAD -> whisper. While someone is talking the audio so far is re-transcribed every
 * {@code partialIntervalMs}, so a block name can fire mid-sentence; a final pass runs once
 * {@code silenceMs} of quiet ends the utterance. Talking non-stop for longer than
 * {@code maxUtteranceMs} slides the window forward instead of cutting words in half.
 */
public final class SpeechEngine {
    public interface Listener {
        /**
         * Called on the engine thread.
         *
         * @param isFinal   the speaker stopped (silence) and this covers the whole stretch of speech
         * @param latencyMs how long whisper took
         * @param startMs   where the transcribed audio starts on the engine's audio clock
         * @param lenMs     how much audio was transcribed
         */
        void onTranscript(String text, boolean isFinal, long latencyMs, long startMs, long lenMs);
    }

    private static final int FRAME = Microphone.FRAME;
    private static final int PREROLL_FRAMES = 10; // 200 ms kept from before speech was detected
    private static final int START_FRAMES = 2;    // 40 ms above threshold to start an utterance
    public static final int MAX_UTTERANCE_S = 30;

    private final VoiceConfig cfg;
    private WhisperNative.Model model;
    private final BooleanSupplier active;
    private final Listener listener;
    private volatile boolean running = true;
    private volatile String prompt;
    private final AtomicReference<WhisperNative.Model> pendingModel = new AtomicReference<WhisperNative.Model>();
    private volatile boolean reopenMic;
    private volatile String micStatus = "not listening (join a world)";
    private volatile WhisperNative.Model currentModel;
    private Thread thread;

    private final float[] buf;
    private int len;
    private final float[][] preroll = new float[PREROLL_FRAMES][FRAME];
    private int prerollPos, prerollCount;
    private double noiseDb = -60;
    private boolean speaking;
    private int loudFrames, quietFrames, lastPartialAt, voicedFrames;
    /** Audio clock: samples seen so far, and the clock position of buf[0]. */
    private long totalSamples, bufStart;
    private long lastInferMs;

    public SpeechEngine(VoiceConfig cfg, WhisperNative.Model model, BooleanSupplier active, Listener listener) {
        this.cfg = cfg;
        this.model = model;
        this.currentModel = model;
        this.active = active;
        this.listener = listener;
        this.prompt = cfg.prompt;
        // Sized for the longest phrase the settings allow, so maxUtteranceMs can change at runtime.
        this.buf = new float[Microphone.SAMPLE_RATE * MAX_UTTERANCE_S + FRAME];
    }

    public void start() {
        thread = new Thread(this::run, "Babble-speech");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    /** Swap the whisper prompt, e.g. once the installed mods' vocabulary is known. */
    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    /** Hand over a freshly loaded model; the engine thread switches to it and frees the old one. */
    public void swapModel(WhisperNative.Model next) {
        WhisperNative.Model stale = pendingModel.getAndSet(next);
        if (stale != null && stale != next) stale.close(); // superseded before it was picked up
    }

    public WhisperNative.Model model() {
        WhisperNative.Model p = pendingModel.get();
        return p != null ? p : currentModel;
    }

    /** Re-open the microphone, e.g. after the device setting changed. */
    public void reopenMic() {
        reopenMic = true;
    }

    public String micStatus() {
        return micStatus;
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void run() {
        Microphone mic = null;
        float[] frame = new float[FRAME];
        try {
            while (running) {
                WhisperNative.Model next = pendingModel.getAndSet(null);
                if (next != null) {
                    if (next != model) model.close();
                    model = currentModel = next;
                    reset();
                }
                if (reopenMic && mic != null) {
                    mic.close();
                    mic = null;
                }
                reopenMic = false;
                if (!active.getAsBoolean()) {
                    // Mic only open while in a world with voice on (and Minecraft's sound engine is up by then).
                    if (mic != null) {
                        mic.close();
                        mic = null;
                    }
                    micStatus = "not listening";
                    reset();
                    Thread.sleep(250);
                    continue;
                }
                if (mic == null) {
                    try {
                        mic = Microphone.open(cfg.micBackend, cfg.micDevice);
                    } catch (Exception e) {
                        micStatus = "no microphone: " + e.getMessage();
                        Log.error("Could not open a microphone, retrying", e);
                        Thread.sleep(3000);
                        continue;
                    }
                    micStatus = mic.describe();
                    Log.info("Listening on " + micStatus);
                }
                if (!mic.read(frame)) {
                    Log.info("Microphone stopped delivering audio, reopening");
                    mic.close();
                    mic = null;
                    Thread.sleep(1000);
                    continue;
                }
                process(frame);
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            Log.error("Speech engine crashed", t);
        } finally {
            if (mic != null) mic.close();
        }
    }

    /** Feed one 20 ms frame. Public so tests can drive the engine from a file. */
    public void process(float[] frame) {
        totalSamples += FRAME;
        double db = levelDb(frame);
        double threshold = Math.max(noiseDb + cfg.vadMarginDb, cfg.vadMinDb);

        if (!speaking) {
            // Track the background level: fall quickly, rise slowly.
            noiseDb += (db < noiseDb ? 0.2 : 0.01) * (db - noiseDb);
            System.arraycopy(frame, 0, preroll[prerollPos], 0, FRAME);
            prerollPos = (prerollPos + 1) % PREROLL_FRAMES;
            prerollCount = Math.min(prerollCount + 1, PREROLL_FRAMES);
            loudFrames = db > threshold ? loudFrames + 1 : 0;
            if (loudFrames < START_FRAMES) return;
            speaking = true;
            voicedFrames = loudFrames;
            len = 0;
            bufStart = totalSamples - (long) prerollCount * FRAME;
            quietFrames = 0;
            lastPartialAt = 0;
            for (int i = prerollCount; i > 0; i--) {
                append(preroll[(prerollPos - i + PREROLL_FRAMES) % PREROLL_FRAMES]);
            }
            Log.debug(String.format("speech start (%.1f dB, noise %.1f dB)", db, noiseDb));
            return;
        }

        append(frame);
        if (db > threshold) voicedFrames++;
        quietFrames = db > threshold - 3 ? 0 : quietFrames + 1; // 3 dB hysteresis
        // Clicks, pops, breaths and block-break sounds are too short to be a word: never show them to
        // whisper, which would happily "hear" a prompt word in them.
        boolean enoughVoice = voicedFrames * 20 >= cfg.minVoicedMs;
        int ms = len * 1000 / Microphone.SAMPLE_RATE;

        if (quietFrames * 20 >= cfg.silenceMs) {
            // Drop most of the trailing silence; whisper does better without it and it's faster.
            int keep = Math.max(FRAME, len - Math.max(0, quietFrames - 5) * FRAME);
            if (enoughVoice) transcribe(keep, true);
            else Log.debug("ignored " + voicedFrames * 20 + " ms of sound (too short to be a word)");
            reset();
            return;
        }
        if (ms >= cfg.maxUtteranceMs || len + FRAME > buf.length) {
            // Still talking: keep the most recent audio rather than cutting the phrase off.
            int keep = Math.max(Microphone.SAMPLE_RATE, (cfg.maxUtteranceMs - 1500) * (Microphone.SAMPLE_RATE / 1000));
            keep = Math.min(keep, len) / FRAME * FRAME;
            int drop = len - keep;
            System.arraycopy(buf, drop, buf, 0, keep);
            len = keep;
            bufStart += drop;
            lastPartialAt = Math.max(0, lastPartialAt - drop);
        }
        if (enoughVoice && cfg.partialIntervalMs > 0 && ms >= cfg.minPartialMs
                && (len - lastPartialAt) * 1000L / Microphone.SAMPLE_RATE >= partialInterval()) {
            lastPartialAt = len;
            transcribe(len, false);
        }
    }

    private void transcribe(int n, boolean isFinal) {
        long t0 = System.nanoTime();
        String text;
        try {
            text = model.transcribe(buf, n, prompt, cfg.threads, audioCtx(n));
        } catch (Throwable t) {
            Log.error("transcription failed", t);
            return;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        lastInferMs = ms;
        Log.debug(String.format("%s %4dms audio=%dms: %s", isFinal ? "final  " : "partial", ms, n / 16, text));
        if (looksHallucinated(text)) {
            Log.debug("  ignored (repetition)");
            return;
        }
        if (!text.isEmpty()) listener.onTranscript(text, isFinal, ms, bufStart * 1000 / Microphone.SAMPLE_RATE, n * 1000L / Microphone.SAMPLE_RATE);
    }

    /** Never re-transcribe faster than inference can keep up with (matters on the CPU fallback). */
    private long partialInterval() {
        return Math.max(cfg.partialIntervalMs, lastInferMs * 3 / 2);
    }

    /** Whisper's encoder covers 30 s in 1500 positions; 0 sizes it to the clip (fast, but prone to repetition loops). */
    private int audioCtx(int n) {
        if (cfg.audioCtx > 0) return Math.min(1500, cfg.audioCtx);
        int padded = Math.max(n, Microphone.SAMPLE_RATE + Microphone.SAMPLE_RATE / 10);
        int positions = (padded + 319) / 320 + 64;
        return Math.min(1500, (positions + 63) / 64 * 64);
    }

    /** Whisper's classic failure on short or quiet audio: "stone, stone, stone, stone...". */
    static boolean looksHallucinated(String text) {
        String[] words = text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        java.util.Set<String> distinct = new java.util.HashSet<String>();
        int n = 0;
        for (String w : words) {
            if (w.isEmpty()) continue;
            distinct.add(w);
            n++;
        }
        return n >= 5 && distinct.size() * 2 < n;
    }

    private void append(float[] frame) {
        if (len + FRAME > buf.length) return;
        System.arraycopy(frame, 0, buf, len, FRAME);
        len += FRAME;
    }

    private void reset() {
        speaking = false;
        loudFrames = 0;
        quietFrames = 0;
        len = 0;
        prerollCount = 0;
    }

    private static double levelDb(float[] f) {
        double sum = 0;
        for (float v : f) sum += v * v;
        return 10 * Math.log10(sum / f.length + 1e-10);
    }
}
