package dev.babble.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whisper models the player can pick in game. One is bundled in the jar (so the mod works offline
 * straight away); the others are downloaded on demand into {@code <game dir>/babble/models}.
 */
public final class Models {
    private Models() {}

    public static final class Info {
        public final String id;
        public final int sizeMb;
        public final String blurb;

        Info(String id, int sizeMb, String blurb) {
            this.id = id;
            this.sizeMb = sizeMb;
            this.blurb = blurb;
        }

        public String fileName() {
            return "ggml-" + id + ".bin";
        }
    }

    public static final List<Info> ALL = Collections.unmodifiableList(Arrays.asList(
            new Info("tiny.en-q8_0", 42, "fastest"),
            new Info("tiny.en", 74, "fastest, full precision"),
            new Info("base.en-q5_1", 57, "more accurate"),
            new Info("base.en", 141, "more accurate, full precision"),
            new Info("small.en-q5_1", 181, "most accurate, GPU recommended")));

    private static final String URL_PATTERN = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/%s";

    private static volatile File downloadDir;
    private static volatile String bundledId;
    private static volatile File bundledFile;
    /** id -> 0..100 while downloading; -1 after a failed download. */
    private static final Map<String, Integer> progress = new ConcurrentHashMap<String, Integer>();
    private static final Map<String, String> errors = new ConcurrentHashMap<String, String>();

    /** Where downloaded models live; set at startup so the settings screen works even with voice off. */
    public static void setWorkDir(File workDir) {
        downloadDir = new File(workDir, "models");
    }

    static void init(File workDir, File bundled) {
        setWorkDir(workDir);
        bundledFile = bundled;
        bundledId = idOf(bundled.getName());
    }

    private static String idOf(String fileName) {
        return fileName.substring("ggml-".length(), fileName.length() - ".bin".length());
    }

    public static Info info(String id) {
        for (Info i : ALL) if (i.id.equals(id)) return i;
        return null;
    }

    public static String bundledId() {
        if (bundledId == null) {
            try (java.io.InputStream in = Models.class.getResourceAsStream("/babble/bundle.properties")) {
                java.util.Properties p = new java.util.Properties();
                if (in != null) p.load(in);
                String name = p.getProperty("model");
                if (name != null) bundledId = idOf(name);
            } catch (java.io.IOException ignored) {
            }
        }
        return bundledId;
    }

    /** The model file for an id, or null if it is neither bundled nor downloaded. */
    public static File file(String id) {
        if (id == null || id.isEmpty() || id.equals(bundledId())) return bundledFile != null ? bundledFile : null;
        File f = downloadDir == null ? null : new File(downloadDir, "ggml-" + id + ".bin");
        return f != null && f.isFile() ? f : null;
    }

    public static boolean available(String id) {
        // Before the engine has unpacked the jar the bundled model has no file yet, but it is available.
        return id != null && (id.isEmpty() || id.equals(bundledId())) || file(id) != null;
    }

    /** 0..100 while downloading, -1 if the last attempt failed, null otherwise. */
    public static Integer progress(String id) {
        return progress.get(id);
    }

    public static String error(String id) {
        return errors.get(id);
    }

    /** Starts a background download; {@code onDone} runs on the download thread when it succeeds. */
    public static void download(final String id, final Runnable onDone) {
        final Info info = info(id);
        if (info == null || downloadDir == null || progress.containsKey(id) && progress.get(id) >= 0) return;
        progress.put(id, 0);
        errors.remove(id);
        Thread t = new Thread(() -> {
            File tmp = new File(downloadDir, info.fileName() + ".part");
            try {
                downloadDir.mkdirs();
                HttpURLConnection c = open(String.format(URL_PATTERN, info.fileName()));
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    long done = 0;
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                        done += r;
                        if (total > 0) progress.put(id, (int) Math.min(99, done * 100 / total));
                    }
                    if (total > 0 && done != total) throw new java.io.IOException("download truncated");
                }
                File dest = new File(downloadDir, info.fileName());
                if (!tmp.renameTo(dest)) throw new java.io.IOException("could not write " + dest);
                progress.remove(id);
                Log.info("Downloaded model " + id);
                if (onDone != null) onDone.run();
            } catch (Exception e) {
                tmp.delete();
                progress.put(id, -1);
                errors.put(id, String.valueOf(e.getMessage()));
                Log.error("Model download failed: " + id, e);
            }
        }, "Babble-download-" + id);
        t.setDaemon(true);
        t.start();
    }

    /** HttpURLConnection won't follow cross-host redirects on its own (Hugging Face -> CDN). */
    private static HttpURLConnection open(String url) throws java.io.IOException {
        for (int hops = 0; hops < 8; hops++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Babble");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            return c;
        }
        throw new java.io.IOException("too many redirects");
    }
}
