package dev.babble.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds block names in a transcript. Names and transcript go through the same normalisation
 * (lowercase, punctuation stripped, crude plural stemming), then the longest name wins at each
 * position, so "stone bricks" matches Stone Bricks rather than Stone.
 *
 * If nothing matches exactly at a position, spaces are ignored ("deep slate") and long names allow
 * a small edit distance ("stone breaks", "jacko lanther"). Short names must match exactly so that
 * everyday words ("store", "class", "later") never destroy anything.
 *
 * Modpacks often have no block named after a material itself ("Cinnabar"), only "Cinnabar Ore",
 * "Cinnabar Block"... A word that names no block, starts modded block names and appears in no vanilla
 * block name is treated as a material and matches all of them. Vanilla and generic words ("oak",
 * "red", "advanced") never are, so everyday speech stays harmless.
 *
 * Built once (add... then {@link #finish()}) on the client thread, then only read from the speech
 * thread, so it needs no locking.
 */
public final class BlockMatcher {
    public static final class Match {
        public final String name;
        public final Set<String> ids;
        /** Word index just past the name, and the transcript's word count: where in the audio it was said. */
        public final int endWord, words;
        /** The name is the start of a longer one ("stone" -> "stone bricks"). */
        public final boolean canGrow;

        Match(String name, Set<String> ids, int endWord, int words, boolean canGrow) {
            this.name = name;
            this.ids = ids;
            this.endWord = endWord;
            this.words = words;
            this.canGrow = canGrow;
        }

        /** Whisper may still be guessing at the trailing words of audio that is still coming in. */
        public boolean settled() {
            int after = words - endWord;
            return after >= 2 || after == 1 && !canGrow;
        }

        @Override
        public String toString() {
            return name + " " + ids;
        }
    }

    /** Modifier words common in block names; never treated as materials and not worth prompting. */
    static final Set<String> GENERIC_WORDS = new HashSet<String>(java.util.Arrays.asList(
            "block", "blocks", "slab", "stairs", "stair", "wall", "fence", "gate", "door", "trapdoor", "button",
            "pressure", "plate", "pane", "glass", "ore", "deepslate", "stone", "brick", "bricks", "planks",
            "log", "wood", "leaves", "sapling", "white", "black", "red", "green", "blue", "yellow", "orange",
            "purple", "pink", "gray", "grey", "light", "dark", "cyan", "lime", "magenta", "brown", "small",
            "large", "smooth", "polished", "chiseled", "cut", "cracked", "mossy", "raw", "of", "the", "and",
            "with", "frame", "casing", "machine", "basic", "advanced", "elite", "ultimate", "tier", "creative",
            "compressed", "reinforced", "industrial", "electric", "energy", "fluid", "item", "tiny", "big",
            "huge", "mini", "auto", "automatic", "dense", "double", "triple", "hardened", "dyed", "colored",
            "glowing", "storage", "crate", "tank", "pipe", "cable", "conduit", "wire", "generator", "potted",
            "infused", "enchanted", "ancient", "old", "new", "fake", "dummy", "invisible", "hidden", "lit"));

    private final Map<String, Set<String>> byName = new HashMap<String, Set<String>>();
    private final Set<String> vanillaWords = new HashSet<String>();
    private final Map<String, Set<String>> moddedFirstWords = new HashMap<String, Set<String>>();
    private final Map<String, String> moddedFirstSpelling = new HashMap<String, String>();
    private int materials;
    private final Map<String, String> displayNames = new HashMap<String, String>();
    /** Names with spaces removed, for the fuzzy pass, also bucketed by first letter (modpacks have 10k+ names). */
    private final Map<String, String> compact = new HashMap<String, String>();
    private final Map<Character, List<String>> compactByFirst = new HashMap<Character, List<String>>();
    /** Every strict token-prefix of a name ("stone" for "stone bricks"). */
    private final Set<String> prefixes = new HashSet<String>();
    private int maxTokens = 1;

    public void add(String name, String id) {
        String key = normalize(name);
        if (key.isEmpty()) return;
        Set<String> ids = byName.get(key);
        if (ids == null) {
            ids = new LinkedHashSet<String>();
            byName.put(key, ids);
            displayNames.put(key, name);
            String c = key.replace(" ", "");
            if (compact.put(c, key) == null) {
                List<String> bucket = compactByFirst.get(c.charAt(0));
                if (bucket == null) compactByFirst.put(c.charAt(0), bucket = new ArrayList<String>());
                bucket.add(c);
            }
        }
        ids.add(id);
        String[] tokens = key.split(" ");
        if (id.startsWith("minecraft:")) {
            vanillaWords.addAll(java.util.Arrays.asList(tokens));
        } else if (tokens.length > 1) {
            Set<String> family = moddedFirstWords.get(tokens[0]);
            if (family == null) {
                moddedFirstWords.put(tokens[0], family = new LinkedHashSet<String>());
                moddedFirstSpelling.put(tokens[0], name.trim().split("[^\\p{L}\\p{N}']+")[0]);
            }
            family.add(id);
        }
        maxTokens = Math.max(maxTokens, tokens.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.length - 1; i++) {
            if (i > 0) sb.append(' ');
            sb.append(tokens[i]);
            prefixes.add(sb.toString());
        }
    }

    /** Call after the last {@link #add}: registers material words. */
    public void finish() {
        for (Map.Entry<String, Set<String>> e : moddedFirstWords.entrySet()) {
            String word = e.getKey();
            if (word.length() < 4 || byName.containsKey(word) || vanillaWords.contains(word)
                    || GENERIC_WORDS.contains(word) || !word.matches("\\p{L}+")) continue;
            byName.put(word, e.getValue());
            displayNames.put(word, moddedFirstSpelling.get(word) + " (all)");
            compact.put(word, word);
            List<String> bucket = compactByFirst.get(word.charAt(0));
            if (bucket == null) compactByFirst.put(word.charAt(0), bucket = new ArrayList<String>());
            bucket.add(word);
            materials++;
        }
        moddedFirstWords.clear();
        moddedFirstSpelling.clear();
    }

    public int size() {
        return byName.size();
    }

    public int materials() {
        return materials;
    }

    /** Every block name in the transcript, longest name first at each position. */
    public List<Match> find(String text) {
        String norm = normalize(text);
        if (norm.isEmpty()) return Collections.emptyList();
        String[] t = norm.split(" ");
        List<Match> out = new ArrayList<Match>();
        int i = 0;
        while (i < t.length) {
            int matched = 0;
            for (int len = Math.min(maxTokens, t.length - i); len >= 1; len--) {
                String key = join(t, i, len);
                Set<String> ids = byName.get(key);
                if (ids == null) {
                    key = fuzzy(key.replace(" ", ""), len);
                    if (key == null) continue;
                    ids = byName.get(key);
                }
                out.add(new Match(displayNames.get(key), ids, i + len, t.length, prefixes.contains(key)));
                matched = len;
                break;
            }
            i += Math.max(matched, 1);
        }
        return out;
    }

    /**
     * Closest name to a space-less phrase within the allowed edit distance, or null. A phrase may
     * span at most one more word than the name ("deep slate"), so runs of small words ("and the
     * site") can't add up to a long name ("andesite").
     */
    private String fuzzy(String phrase, int words) {
        String exact = compact.get(phrase);
        if (exact != null && wordCount(exact) + 1 >= words) return exact;
        int allowed = allowedEdits(phrase.length());
        if (allowed == 0) return null;
        String best = null;
        int bestD = allowed + 1;
        List<String> bucket = compactByFirst.get(phrase.charAt(0));
        if (bucket == null) return null;
        for (String name : bucket) {
            if (Math.abs(name.length() - phrase.length()) > allowed) continue;
            String key = compact.get(name);
            if (wordCount(key) + 1 < words) continue;
            int max = Math.min(allowed, allowedEdits(name.length()));
            int d = levenshtein(phrase, name, max);
            if (d <= max && d < bestD) {
                bestD = d;
                best = key;
            }
        }
        return best;
    }

    private static int wordCount(String key) {
        int n = 1;
        for (int i = 0; i < key.length(); i++) if (key.charAt(i) == ' ') n++;
        return n;
    }

    private static int allowedEdits(int len) {
        return len >= 10 ? 2 : len >= 7 ? 1 : 0;
    }

    /** Edit distance, or max+1 as soon as it is known to exceed max. */
    private static int levenshtein(String a, String b, int max) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > max) return max + 1;
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    private static String join(String[] t, int from, int len) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < len; k++) {
            if (k > 0) sb.append(' ');
            sb.append(t[from + k]);
        }
        return sb.toString();
    }

    public static String normalize(String s) {
        String lower = s.toLowerCase(Locale.ROOT).replace("'", "");
        StringBuilder sb = new StringBuilder();
        for (String tok : lower.split("[^\\p{L}\\p{N}]+")) {
            if (tok.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(stem(tok));
        }
        return sb.toString();
    }

    private static String stem(String w) {
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }
}
