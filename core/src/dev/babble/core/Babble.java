package dev.babble.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.function.BooleanSupplier;

/** Entry point and runtime state used by the version-specific mod classes. */
public final class Babble {
    private Babble() {}

    private static final String BUNDLE = "/babble/";

    private static volatile VoiceConfig cfg;
    private static volatile SpeechEngine engine;
    private static volatile String backend = "none";
    private static volatile String loading;
    private static volatile String error;
    private static final Object LOAD_LOCK = new Object();

    /**
     * Unpacks the bundled native libraries and model into {@code workDir}, loads whisper and starts
     * listening. Blocking: call from a background thread.
     */
    public static SpeechEngine start(VoiceConfig config, File workDir, BooleanSupplier active, SpeechEngine.Listener listener)
            throws IOException {
        cfg = config;
        Log.verbose = config.verbose;
        try {
            Properties bundle = new Properties();
            try (InputStream in = Babble.class.getResourceAsStream(BUNDLE + "bundle.properties")) {
                if (in == null) throw new IOException("bundle.properties missing from the mod jar");
                bundle.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
            String stamp = bundle.getProperty("stamp", "0");
            File dir = new File(workDir, stamp);

            // natives.<platform> lists the backends in this jar, best first: e.g. cuda,vulkan,cpu.
            // macOS jars carry one universal (arm64 + x86_64) library under "macos-universal".
            String platform = platform();
            String dirName = platform;
            String list = bundle.getProperty("natives." + platform);
            if (list == null && platform.startsWith("macos")) list = bundle.getProperty("natives." + (dirName = "macos-universal"));
            if (list == null) {
                throw new IOException("this Babble jar has no speech engine for " + platform
                        + " (use the universal jar, or build one on this computer)");
            }
            File bundled = extract(BUNDLE + "model/" + bundle.getProperty("model"), dir);
            if (bundled == null) throw new IOException("model missing from the mod jar");
            java.util.List<File> libs = new java.util.ArrayList<File>();
            for (String name : list.split(",")) {
                File f = extract(BUNDLE + "natives/" + dirName + "/" + System.mapLibraryName("babble_" + name.trim()), dir);
                if (f != null) libs.add(f);
            }
            deleteOldStamps(workDir, stamp);
            Models.init(workDir, bundled);

            // Every GPU build also contains the CPU backend, so the first library that loads (its GPU
            // runtime present: CUDA, the Vulkan loader, Metal) lets the player flip GPU on/off in game.
            // The CPU-only build is the last resort.
            File lib = WhisperNative.load(libs.toArray(new File[0]));
            backend = lib.getName().replaceAll("^(lib)?babble_|\\.(so|dll|dylib)$", "");
            Log.info("Using the " + backend + " speech engine (" + lib.getName() + ")");
            WhisperNative.setVerbose(config.verbose);

            String id = configuredModel();
            loading = id;
            WhisperNative.Model m = load(id);
            SpeechEngine e = new SpeechEngine(config, m, active, listener);
            e.start();
            engine = e;
            return e;
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            error = e.toString();
            throw e;
        } finally {
            loading = null;
        }
    }

    private static WhisperNative.Model load(String id) {
        File f = Models.file(id);
        if (f == null) throw new IllegalStateException("model " + id + " is not installed");
        long t0 = System.nanoTime();
        WhisperNative.Model m = new WhisperNative.Model(f.getAbsolutePath(), id, cfg.useGpu && gpuAvailable(), cfg.flashAttention);
        // Warm-up: the first GPU call pays for kernel/pipeline setup, don't let a player's first word pay it.
        m.transcribe(new float[Microphone.SAMPLE_RATE], Microphone.SAMPLE_RATE, cfg.prompt, cfg.threads, 128);
        Log.info(String.format("Loaded %s on %s in %d ms", id, m.devices, (System.nanoTime() - t0) / 1_000_000));
        return m;
    }

    /** Load the configured model/acceleration in the background and hot-swap it into the engine. */
    public static void reload() {
        final SpeechEngine e = engine;
        if (e == null) return;
        final String id = configuredModel();
        loading = id;
        Thread t = new Thread(() -> {
            synchronized (LOAD_LOCK) {
                try {
                    e.swapModel(load(id));
                    error = null;
                } catch (Throwable ex) {
                    error = "could not load " + id + ": " + ex.getMessage();
                    Log.error("Model reload failed", ex);
                } finally {
                    loading = null;
                }
            }
        }, "Babble-load");
        t.setDaemon(true);
        t.start();
    }

    /** The model the config asks for, or the bundled one if it's blank or missing. */
    private static String configuredModel() {
        return !cfg.model.isEmpty() && Models.available(cfg.model) ? cfg.model : Models.bundledId();
    }

    public static SpeechEngine engine() {
        return engine;
    }

    /** Whether a GPU-capable library loaded (CUDA, Vulkan or Metal), i.e. whether GPU can be switched on at all. */
    public static boolean gpuAvailable() {
        return !backend.equals("cpu") && !backend.equals("none");
    }

    /** The loaded speech engine: "cuda", "vulkan", "metal", "cpu", or "none" before it starts. */
    public static String backend() {
        return backend;
    }

    /** Model id currently being loaded, or null. */
    public static String loading() {
        return loading;
    }

    /** One-line state for the settings screen. */
    public static String status() {
        SpeechEngine e = engine;
        if (error != null) return "§c" + error;
        if (e == null) return loading != null ? "§eLoading " + loading + "…" : "§7Voice is off";
        WhisperNative.Model m = e.model();
        String model = loading != null ? "§eloading " + loading + "…" : "§f" + m.name + " on " + m.devices;
        return "§a● §f" + e.micStatus() + " §7· " + model;
    }

    public static String platform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String o = os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
        String a = arch.equals("amd64") || arch.equals("x86_64") ? "x86_64" : arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : arch;
        return o + "-" + a;
    }

    private static File extract(String resource, File dir) throws IOException {
        File out = new File(dir, resource.substring(resource.lastIndexOf('/') + 1));
        try (InputStream in = Babble.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            if (out.isFile()) return out; // stamp dir is unique per build, so existing files are current
            dir.mkdirs();
            File tmp = new File(dir, out.getName() + ".tmp");
            try (OutputStream os = new FileOutputStream(tmp)) {
                byte[] b = new byte[1 << 16];
                int r;
                while ((r = in.read(b)) > 0) os.write(b, 0, r);
            }
            if (!tmp.renameTo(out)) throw new IOException("could not write " + out);
        }
        return out;
    }

    /** Removes files unpacked by older builds (stamp dirs), leaving downloaded models alone. */
    private static void deleteOldStamps(File workDir, String keep) {
        File[] dirs = workDir.listFiles(f -> f.isDirectory() && f.getName().matches("[0-9a-f]{12}"));
        if (dirs == null) return;
        for (File d : dirs) {
            if (d.getName().equals(keep)) continue;
            File[] files = d.listFiles();
            if (files != null) for (File f : files) f.delete();
            d.delete();
        }
    }
}
