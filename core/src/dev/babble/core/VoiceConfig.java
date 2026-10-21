package dev.babble.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Plain .properties config so it works the same on every Forge version. */
public final class VoiceConfig {
    public boolean enabled = true;
    public int radius = 6;
    public int maxRadius = 16;
    public boolean sphere = true;
    public boolean waitForLongerNames = false;
    public boolean dropItems = true;
    public String excludedBlocks = "minecraft:air,minecraft:cave_air,minecraft:void_air";

    public String model = "";
    public boolean useGpu = true;
    public boolean flashAttention = true;
    public int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
    public int audioCtx = 1500;
    public boolean modVocabulary = true;
    public String prompt = "Minecraft blocks: stone, dirt, grass block, sand, gravel, cobblestone, oak log, oak leaves, glass, diamond ore, iron ore, coal ore, deepslate, netherrack, obsidian, water, lava, TNT.";

    public String micBackend = "auto";
    public String micDevice = "";
    public int silenceMs = 220;
    public int partialIntervalMs = 120;
    public int minPartialMs = 400;
    public int maxUtteranceMs = 5000;
    public int repeatCooldownMs = 1000;
    public int minVoicedMs = 120;
    public double vadMarginDb = 12;
    public double vadMinDb = -50;

    public boolean showHeard = true;
    public boolean showTranscripts = false;
    public boolean debugChat = false;
    public boolean verbose = false;

    private static final String HEADER = String.join("\n",
            "Babble config. Say a block's name to destroy every block of that type around you.",
            "",
            "radius / maxRadius   blocks around the player (maxRadius is enforced by the server)",
            "sphere               true = sphere, false = cube",
            "waitForLongerNames   hold names like 'stone' until the next word rules out 'stone bricks' (slower)",
            "dropItems            drop the destroyed blocks as items",
            "excludedBlocks       comma-separated block ids that are never destroyed",
            "model                whisper model id (blank = the one bundled in the jar); pick and download others in game",
            "useGpu               run whisper on the GPU when the CUDA build loads, else CPU",
            "flashAttention       faster GPU attention (turn off if transcripts look broken)",
            "threads              CPU threads for whisper",
            "audioCtx             whisper encoder window: 1500 = standard (accurate); smaller is faster on CPU but tends to hallucinate repeats; 0 = sized to each clip",
            "prompt               text that biases whisper toward Minecraft vocabulary",
            "modVocabulary        append distinctive words from installed mods' block names to the prompt",
            "micBackend           auto | openal | javasound",
            "micDevice            capture device name (blank = system default; available names are printed to the log)",
            "silenceMs            silence that ends an utterance; lower = snappier, higher = fewer cut-off words",
            "partialIntervalMs    re-transcribe while you are still talking this often, so blocks break mid-sentence",
            "minPartialMs         audio (incl. 200 ms lead-in) needed before the first mid-sentence transcription; too low and whisper echoes the prompt",
            "maxUtteranceMs       audio window while talking non-stop; older audio slides out of it",
            "repeatCooldownMs     the same block can't fire again within this long (stops echoes / double hearing)",
            "minVoicedMs          sounds with less loud audio than this are ignored (clicks, pops, breaths)",
            "vadMarginDb          how far above background noise counts as speech",
            "vadMinDb             absolute loudness floor for speech (dBFS)",
            "showHeard            action-bar message when a block name is heard",
            "showTranscripts      action-bar message with every transcript (for tuning)",
            "debugChat            chat message for every heard block (toggle in game with /babble debug)",
            "verbose              extra logging");

    public static VoiceConfig load(File file) {
        VoiceConfig cfg = new VoiceConfig();
        Properties p = new Properties();
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.error("Could not read " + file, e);
            }
        }
        cfg.enabled = bool(p, "enabled", cfg.enabled);
        cfg.radius = integer(p, "radius", cfg.radius);
        cfg.maxRadius = integer(p, "maxRadius", cfg.maxRadius);
        cfg.sphere = bool(p, "sphere", cfg.sphere);
        cfg.waitForLongerNames = bool(p, "waitForLongerNames", cfg.waitForLongerNames);
        cfg.dropItems = bool(p, "dropItems", cfg.dropItems);
        cfg.excludedBlocks = p.getProperty("excludedBlocks", cfg.excludedBlocks);
        cfg.model = p.getProperty("model", cfg.model).trim();
        cfg.useGpu = bool(p, "useGpu", cfg.useGpu);
        cfg.flashAttention = bool(p, "flashAttention", cfg.flashAttention);
        cfg.threads = integer(p, "threads", cfg.threads);
        cfg.audioCtx = integer(p, "audioCtx", cfg.audioCtx);
        cfg.prompt = p.getProperty("prompt", cfg.prompt);
        cfg.modVocabulary = bool(p, "modVocabulary", cfg.modVocabulary);
        cfg.micBackend = p.getProperty("micBackend", cfg.micBackend).trim();
        cfg.micDevice = p.getProperty("micDevice", cfg.micDevice).trim();
        cfg.silenceMs = integer(p, "silenceMs", cfg.silenceMs);
        cfg.partialIntervalMs = integer(p, "partialIntervalMs", cfg.partialIntervalMs);
        cfg.minPartialMs = integer(p, "minPartialMs", cfg.minPartialMs);
        cfg.maxUtteranceMs = integer(p, "maxUtteranceMs", cfg.maxUtteranceMs);
        cfg.repeatCooldownMs = integer(p, "repeatCooldownMs", cfg.repeatCooldownMs);
        cfg.minVoicedMs = integer(p, "minVoicedMs", cfg.minVoicedMs);
        cfg.vadMarginDb = dbl(p, "vadMarginDb", cfg.vadMarginDb);
        cfg.vadMinDb = dbl(p, "vadMinDb", cfg.vadMinDb);
        cfg.showHeard = bool(p, "showHeard", cfg.showHeard);
        cfg.showTranscripts = bool(p, "showTranscripts", cfg.showTranscripts);
        cfg.debugChat = bool(p, "debugChat", cfg.debugChat);
        cfg.verbose = bool(p, "verbose", cfg.verbose);
        int version = integer(p, "configVersion", 1);
        if (version < 2 && cfg.partialIntervalMs == 200) cfg.partialIntervalMs = 120; // old default, now faster
        if (version < 3 && cfg.repeatCooldownMs == 2000) cfg.repeatCooldownMs = 1000; // stopgap no longer needed
        cfg.file = file;
        cfg.save(file); // writes defaults for any missing keys
        return cfg;
    }

    /** Where this config was loaded from (null if never loaded). */
    public File file;

    public void save() {
        if (file != null) save(file);
    }

    public void save(File file) {
        Properties p = new Properties() {
            // keep a stable, readable key order
            @Override
            public synchronized java.util.Enumeration<Object> keys() {
                return java.util.Collections.enumeration(new java.util.TreeSet<Object>(super.keySet()));
            }

            @Override
            public java.util.Set<java.util.Map.Entry<Object, Object>> entrySet() {
                java.util.Set<java.util.Map.Entry<Object, Object>> s = new java.util.TreeSet<java.util.Map.Entry<Object, Object>>(
                        (a, b) -> a.getKey().toString().compareTo(b.getKey().toString()));
                s.addAll(super.entrySet());
                return s;
            }
        };
        p.setProperty("enabled", String.valueOf(enabled));
        p.setProperty("radius", String.valueOf(radius));
        p.setProperty("maxRadius", String.valueOf(maxRadius));
        p.setProperty("sphere", String.valueOf(sphere));
        p.setProperty("waitForLongerNames", String.valueOf(waitForLongerNames));
        p.setProperty("dropItems", String.valueOf(dropItems));
        p.setProperty("excludedBlocks", excludedBlocks);
        p.setProperty("model", model);
        p.setProperty("useGpu", String.valueOf(useGpu));
        p.setProperty("flashAttention", String.valueOf(flashAttention));
        p.setProperty("threads", String.valueOf(threads));
        p.setProperty("audioCtx", String.valueOf(audioCtx));
        p.setProperty("prompt", prompt);
        p.setProperty("modVocabulary", String.valueOf(modVocabulary));
        p.setProperty("micBackend", micBackend);
        p.setProperty("micDevice", micDevice);
        p.setProperty("silenceMs", String.valueOf(silenceMs));
        p.setProperty("partialIntervalMs", String.valueOf(partialIntervalMs));
        p.setProperty("minPartialMs", String.valueOf(minPartialMs));
        p.setProperty("maxUtteranceMs", String.valueOf(maxUtteranceMs));
        p.setProperty("repeatCooldownMs", String.valueOf(repeatCooldownMs));
        p.setProperty("minVoicedMs", String.valueOf(minVoicedMs));
        p.setProperty("vadMarginDb", String.valueOf(vadMarginDb));
        p.setProperty("vadMinDb", String.valueOf(vadMinDb));
        p.setProperty("showHeard", String.valueOf(showHeard));
        p.setProperty("showTranscripts", String.valueOf(showTranscripts));
        p.setProperty("debugChat", String.valueOf(debugChat));
        p.setProperty("verbose", String.valueOf(verbose));
        p.setProperty("configVersion", "3");
        try {
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir != null) dir.mkdirs();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                p.store(w, HEADER);
            }
        } catch (Exception e) {
            Log.error("Could not write " + file, e);
        }
    }

    private static boolean bool(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int integer(Properties p, String k, int def) {
        try {
            String v = p.getProperty(k);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double dbl(Properties p, String k, double def) {
        try {
            String v = p.getProperty(k);
            return v == null ? def : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
