package dev.babble.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Whisper only knows the words it was trained on, so names like "Yellorite" or "Certus Quartz" get
 * transcribed as something else. Its initial prompt biases decoding toward words it has "seen", so we
 * append the words that make the installed mods' block names distinctive, most common first, within
 * whisper's prompt budget (~224 tokens).
 */
public final class PromptBuilder {
    private PromptBuilder() {}

    /** ~4 characters per token; leaves headroom under whisper's 224-token prompt limit. */
    private static final int MAX_CHARS = 700;

    /**
     * @param base       the user's prompt (vanilla vocabulary)
     * @param vanilla    display names from the minecraft namespace
     * @param modded     display names from every other namespace
     */
    public static String build(String base, Iterable<String> vanilla, Iterable<String> modded) {
        Set<String> known = new HashSet<String>(BlockMatcher.GENERIC_WORDS);
        for (String n : vanilla) known.addAll(words(n));
        known.addAll(words(base));

        Map<String, Integer> counts = new HashMap<String, Integer>();
        Map<String, String> spelling = new HashMap<String, String>();
        for (String name : modded) {
            for (String raw : name.split("[^\\p{L}]+")) {
                String w = raw.toLowerCase(Locale.ROOT);
                if (w.length() < 4 || known.contains(w)) continue;
                Integer c = counts.get(w);
                counts.put(w, c == null ? 1 : c + 1);
                if (!spelling.containsKey(w)) spelling.put(w, raw);
            }
        }
        List<String> ranked = new ArrayList<String>(counts.keySet());
        ranked.sort((a, b) -> {
            int d = counts.get(b) - counts.get(a);
            return d != 0 ? d : a.compareTo(b);
        });

        StringBuilder sb = new StringBuilder(base.trim());
        if (ranked.isEmpty()) return sb.toString();
        sb.append(" Also:");
        int added = 0;
        for (String w : ranked) {
            String piece = (added == 0 ? " " : ", ") + spelling.get(w);
            if (sb.length() + piece.length() + 1 > MAX_CHARS) break;
            sb.append(piece);
            added++;
        }
        return added == 0 ? base.trim() : sb.append('.').toString();
    }

    private static List<String> words(String s) {
        List<String> out = new ArrayList<String>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }
}
