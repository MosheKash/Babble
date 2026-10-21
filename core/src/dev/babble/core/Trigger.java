package dev.babble.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides which heard block names fire. The speech engine re-transcribes the audio every ~120 ms
 * while you talk, so the same mention shows up in many transcripts; each mention is identified by
 * where in the audio it was said and fires exactly once, as soon as it is trustworthy:
 *
 * - settled (followed by more words) -> fires immediately;
 * - the last word(s) of a transcript, where whisper may still be guessing (or "stone" that may yet
 *   become "stone bricks") -> fires when the next transcript, ~120 ms later, still has it;
 * - the final transcript after you stop talking -> fires.
 *
 * Saying the same block again later is a new mention and fires again. As a safety net, the same
 * block never fires twice within {@link #cooldownMs} of audio.
 *
 * With {@link #waitForLongerNames}, a name that could still grow ("stone" -> "stone bricks") waits
 * until the next word is heard (or you stop talking): no stray Stone when you meant Stone Bricks,
 * at the cost of a short delay for those names.
 */
public final class Trigger {
    /** Two sightings of a name this close together in the audio are the same mention. */
    private static final long SAME_MENTION_MS = 1000;
    private static final long FORGET_MS = 60000;

    private static final class Mention {
        final String key;
        long atMs;

        Mention(String key, long atMs) {
            this.key = key;
            this.atMs = atMs;
        }
    }

    private final BlockMatcher matcher;
    private final List<Mention> fired = new ArrayList<Mention>();
    private List<Mention> pending = new ArrayList<Mention>();
    public volatile boolean waitForLongerNames;
    public volatile long cooldownMs = 1000;
    private final Map<String, Long> lastFired = new java.util.HashMap<String, Long>();

    public Trigger(BlockMatcher matcher) {
        this.matcher = matcher;
    }

    public BlockMatcher matcher() {
        return matcher;
    }

    /**
     * @param startMs where the transcribed audio starts, on the engine's audio clock
     * @param lenMs   how much audio was transcribed
     */
    public List<BlockMatcher.Match> accept(String text, boolean isFinal, long startMs, long lenMs) {
        for (Iterator<Mention> it = fired.iterator(); it.hasNext(); ) {
            if (it.next().atMs < startMs - FORGET_MS) it.remove();
        }
        long now = startMs + lenMs;
        Map<String, BlockMatcher.Match> out = new LinkedHashMap<String, BlockMatcher.Match>();
        List<Mention> nextPending = new ArrayList<Mention>();
        List<Mention> seen = new ArrayList<Mention>();
        for (BlockMatcher.Match m : matcher.find(text)) {
            String key = m.name.toLowerCase(Locale.ROOT);
            // A rough position (words spread evenly over the audio). It drifts as more audio arrives
            // (a lone "Dirt." is placed at the end of an ever-longer clip), so every sighting of a
            // known mention moves it along: the same spoken word stays the same mention.
            long at = startMs + lenMs * m.endWord / Math.max(1, m.words);
            Mention known = claim(fired, seen, key, at);
            if (known != null) {
                known.atMs = at;
                continue;
            }
            Long last = lastFired.get(key);
            if (last != null && now - last < cooldownMs) continue;
            if (!isFinal && waitForLongerNames && m.canGrow && m.endWord == m.words) continue;
            if (!isFinal && !m.settled() && claim(pending, seen, key, at) == null) {
                nextPending.add(new Mention(key, at));
                continue;
            }
            Mention mention = new Mention(key, at);
            fired.add(mention);
            seen.add(mention);
            lastFired.put(key, now);
            out.put(key, m);
        }
        pending = nextPending;
        return new ArrayList<BlockMatcher.Match>(out.values());
    }

    /** The closest unclaimed mention of this name within reach, now claimed for this transcript. */
    private static Mention claim(List<Mention> list, List<Mention> seen, String key, long at) {
        Mention best = null;
        for (Mention m : list) {
            if (!m.key.equals(key) || seen.contains(m) || Math.abs(m.atMs - at) > SAME_MENTION_MS) continue;
            if (best == null || Math.abs(m.atMs - at) < Math.abs(best.atMs - at)) best = m;
        }
        if (best != null) seen.add(best);
        return best;
    }
}
