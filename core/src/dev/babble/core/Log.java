package dev.babble.core;

/** Logs through stdout, which every Forge version captures into latest.log. */
public final class Log {
    private Log() {}

    public static volatile boolean verbose = false;

    public static void info(String msg) {
        System.out.println("[Babble] " + msg);
    }

    public static void debug(String msg) {
        if (verbose) info(msg);
    }

    public static void error(String msg, Throwable t) {
        System.out.println("[Babble] ERROR " + msg + (t == null ? "" : ": " + t));
        if (t != null && verbose) t.printStackTrace(System.out);
    }
}
