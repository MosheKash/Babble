package dev.babble.core;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * Out-of-game harness for the speech pipeline.
 *
 *   bench  <lib> <model> <gpu> <wav...>   time whisper on whole clips (16 kHz mono 16-bit wav)
 *   stream <lib> <model> <gpu> <wav...>   push clips through the VAD/partial pipeline, as in game
 *   mic    <lib> <model> <gpu>            live microphone, prints what would be destroyed
 *   modpack <lib> <model> <gpu> <wav...>  ~30k synthetic modded blocks: matcher speed, prompt, recognition
 */
public final class VoiceTest {
    static final String[] BLOCKS = {
            "Stone", "minecraft:stone", "Stone Bricks", "minecraft:stone_bricks", "Dirt", "minecraft:dirt",
            "Grass Block", "minecraft:grass_block", "Short Grass", "minecraft:short_grass", "Sand", "minecraft:sand",
            "Gravel", "minecraft:gravel", "Cobblestone", "minecraft:cobblestone", "Oak Log", "minecraft:oak_log",
            "Oak Leaves", "minecraft:oak_leaves", "Oak Planks", "minecraft:oak_planks", "Glass", "minecraft:glass",
            "Diamond Ore", "minecraft:diamond_ore", "Iron Ore", "minecraft:iron_ore", "Coal Ore", "minecraft:coal_ore",
            "Deepslate", "minecraft:deepslate", "Netherrack", "minecraft:netherrack", "Obsidian", "minecraft:obsidian",
            "Water", "minecraft:water", "Lava", "minecraft:lava", "TNT", "minecraft:tnt", "Bedrock", "minecraft:bedrock",
            "Crafting Table", "minecraft:crafting_table", "Furnace", "minecraft:furnace", "Chest", "minecraft:chest",
            "Torch", "minecraft:torch", "Ice", "minecraft:ice", "Snow", "minecraft:snow", "Clay", "minecraft:clay",
            "Granite", "minecraft:granite", "Diorite", "minecraft:diorite", "Andesite", "minecraft:andesite",
            "Glowstone", "minecraft:glowstone", "Sandstone", "minecraft:sandstone", "Terracotta", "minecraft:terracotta",
            "Cactus", "minecraft:cactus", "Pumpkin", "minecraft:pumpkin", "Melon", "minecraft:melon",
            "Jack o'Lantern", "minecraft:jack_o_lantern", "Bookshelf", "minecraft:bookshelf", "Spruce Log", "minecraft:spruce_log",
            "Birch Log", "minecraft:birch_log", "Gold Ore", "minecraft:gold_ore", "Redstone Ore", "minecraft:redstone_ore",
            "Lapis Lazuli Ore", "minecraft:lapis_ore", "Emerald Ore", "minecraft:emerald_ore", "Copper Ore", "minecraft:copper_ore",
            "Basalt", "minecraft:basalt", "Soul Sand", "minecraft:soul_sand", "End Stone", "minecraft:end_stone",
            "Wool", "minecraft:white_wool", "Mossy Cobblestone", "minecraft:mossy_cobblestone", "Bricks", "minecraft:bricks",
    };

    public static void main(String[] a) throws Exception {
        String mode = a[0];
        WhisperNative.load(new File(a[1]));
        WhisperNative.setVerbose(false);
        boolean gpu = Boolean.parseBoolean(a[3]);
        long t0 = System.nanoTime();
        WhisperNative.Model model = new WhisperNative.Model(a[2], new File(a[2]).getName(), gpu, gpu);
        System.out.printf("model %s on %s, load %d ms%n", new File(a[2]).getName(), model.devices, (System.nanoTime() - t0) / 1_000_000);
        VoiceConfig cfg = new VoiceConfig();
        cfg.verbose = Log.verbose = Boolean.getBoolean("verbose");
        cfg.prompt = System.getProperty("prompt", cfg.prompt);
        boolean waitLonger = Boolean.getBoolean("waitForLongerNames");
        BlockMatcher matcher = new BlockMatcher();
        for (int i = 0; i < BLOCKS.length; i += 2) {
            matcher.add(BLOCKS[i], BLOCKS[i + 1]);
            matcher.add(BLOCKS[i + 1].substring(BLOCKS[i + 1].indexOf(':') + 1).replace('_', ' '), BLOCKS[i + 1]);
        }
        matcher.finish();
        model.transcribe(new float[16000], 16000, cfg.prompt, cfg.threads, 128); // warm-up

        List<String> files = Arrays.asList(a).subList(4, a.length);
        if (mode.equals("modpack")) {
            String[] materials = {"Yellorite", "Certus Quartz", "Osmium", "Tin", "Lead", "Uranium", "Fluix", "Cinnabar",
                    "Allthemodium", "Vibranium", "Unobtainium", "Zinc", "Brass", "Bauxite", "Uraninite", "Aluminum",
                    "Silver", "Nickel", "Platinum", "Iridium", "Sulfur", "Apatite", "Niter", "Ruby", "Sapphire", "Peridot",
                    "Cobalt", "Ardite", "Draconium", "Inferium", "Prosperity", "Soulium", "Galena", "Iesnium", "Azure Silver",
                    "Crimson Iron", "Fluorite", "Desh", "Ostrum", "Calorite", "Tungsten", "Titanium", "Chromium", "Steel"};
            String[] shapes = {"Ore", "Deepslate Ore", "Block", "Raw Block", "Bricks", "Brick Slab", "Brick Stairs",
                    "Brick Wall", "Tiles", "Pillar", "Lamp", "Glass", "Frame", "Casing", "Plating", "Grate", "Panel",
                    "Chiseled Block", "Smooth Block", "Cluster", "Bud", "Pressure Plate", "Button", "Door", "Trapdoor"};
            String[] mods = {"mekanism", "thermal", "create", "ae2", "allthemodium", "bigreactors", "immersiveengineering",
                    "silentgems", "mysticalagriculture", "ad_astra", "draconicevolution", "chisel", "tconstruct", "ic2",
                    "techreborn", "occultism", "alltheores", "projectred", "rftoolsbase", "bloodmagic", "botania",
                    "forbidden_arcanus", "chemlib", "gtceu", "industrialforegoing", "powah", "ars_nouveau"};
            BlockMatcher big = new BlockMatcher();
            List<String> vanilla = new java.util.ArrayList<String>(), modded = new java.util.ArrayList<String>();
            for (int i = 0; i < BLOCKS.length; i += 2) {
                big.add(BLOCKS[i], BLOCKS[i + 1]);
                vanilla.add(BLOCKS[i]);
            }
            int n = 0;
            for (String mod : mods)
                for (String mat : materials)
                    for (String shape : shapes) {
                        String name = mat + " " + shape;
                        String id = mod + ":" + (mat + "_" + shape).toLowerCase().replace(' ', '_');
                        big.add(name, id);
                        big.add(id.substring(id.indexOf(':') + 1).replace('_', ' '), id);
                        modded.add(name);
                        n++;
                    }
            big.finish();
            System.out.printf("%d modded blocks, %d distinct names, %d materials%n", n, big.size(), big.materials());
            String[] texts = {"Hey look at all this dirt over here", "I really hate yellorite ore", "certis quartz ore",
                    "osmium or", "blah blah the quick brown fox jumps over the lazy dog again and again",
                    "get rid of all this cinnabar", "aluminium ore"};
            for (String t : texts) {
                long s = System.nanoTime();
                List<BlockMatcher.Match> r = null;
                for (int i = 0; i < 100; i++) r = big.find(t);
                long us = (System.nanoTime() - s) / 100_000;
                StringBuilder found = new StringBuilder();
                for (BlockMatcher.Match m : r) found.append(m.name).append(" x").append(m.ids.size()).append("  ");
                System.out.printf("  %5d us  %-70s -> %s%n", us, t, found);
            }
            String modPrompt = PromptBuilder.build(cfg.prompt, vanilla, modded);
            System.out.println("prompt (" + modPrompt.length() + " chars): " + modPrompt);
            for (String f : files) {
                float[] pcm = readWav(new File(f));
                String plain = model.transcribe(pcm, pcm.length, cfg.prompt, cfg.threads, 1500);
                long s = System.nanoTime();
                String biased = model.transcribe(pcm, pcm.length, modPrompt, cfg.threads, 1500);
                long ms = (System.nanoTime() - s) / 1_000_000;
                System.out.printf("%-40s vanilla prompt: %-28s -> %-22s | mod prompt (%d ms): %-28s -> %s%n", new File(f).getName(),
                        '"' + plain + '"', names(big.find(plain)), ms, '"' + biased + '"', names(big.find(biased)));
            }
            return;
        }
        if (mode.equals("bench")) {
            for (String f : files) {
                float[] pcm = readWav(new File(f));
                for (int ctx : new int[] {0, 1500}) {
                    cfg.audioCtx = ctx;
                    long best = Long.MAX_VALUE;
                    String text = "";
                    for (int r = 0; r < 5; r++) {
                        long s = System.nanoTime();
                        text = model.transcribe(pcm, pcm.length, cfg.prompt, cfg.threads, ctx == 0 ? autoCtx(pcm.length) : ctx);
                        best = Math.min(best, System.nanoTime() - s);
                    }
                    System.out.printf("%-28s ctx=%-5s %4d ms  %-40s -> %s%n", new File(f).getName(), ctx == 0 ? "auto" : ctx,
                            best / 1_000_000, '"' + text + '"', matcher.find(text));
                }
            }
        } else if (mode.equals("stream")) {
            for (String f : files) {
                float[] pcm = readWav(new File(f));
                // half a second of quiet room noise before and after, like a real mic
                float[] padded = new float[pcm.length + 16000];
                java.util.Random rnd = new java.util.Random(1);
                for (int i = 0; i < padded.length; i++) padded[i] = (float) (rnd.nextGaussian() * 0.001);
                for (int i = 0; i < pcm.length; i++) padded[i + 8000] += pcm[i];
                final int[] fed = {0};
                final String name = new File(f).getName();
                final Trigger trigger = new Trigger(matcher);
                trigger.waitForLongerNames = waitLonger;
                trigger.cooldownMs = Long.getLong("cooldownMs", 2000L);
                SpeechEngine engine = new SpeechEngine(cfg, model, () -> true, (text, fin, ms, start, len) ->
                        System.out.printf("  %-28s @%5d ms %-7s %3d ms  %-40s -> %s%n", name, fed[0] / 16 - 500,
                                fin ? "final" : "partial", ms, '"' + text + '"', trigger.accept(text, fin, start, len)));
                float[] frame = new float[Microphone.FRAME];
                for (int off = 0; off + frame.length <= padded.length; off += frame.length) {
                    System.arraycopy(padded, off, frame, 0, frame.length);
                    fed[0] = off + frame.length;
                    engine.process(frame);
                }
                System.out.printf("  %-28s speech ends @%d ms%n", name, pcm.length / 16);
            }
        } else if (mode.equals("mic")) {
            cfg.verbose = Log.verbose = true;
            final Trigger trigger = new Trigger(matcher);
            SpeechEngine engine = new SpeechEngine(cfg, model, () -> true, (text, fin, ms, start, len) ->
                    System.out.printf("%s %3d ms  %-40s -> %s%n", fin ? "FINAL  " : "partial", ms, '"' + text + '"', trigger.accept(text, fin, start, len)));
            engine.start();
            System.out.println("Talk! Ctrl+C to quit.");
            Thread.currentThread().join();
        }
        model.close();
    }

    static String names(List<BlockMatcher.Match> ms) {
        StringBuilder sb = new StringBuilder();
        for (BlockMatcher.Match m : ms) sb.append(sb.length() > 0 ? ", " : "").append(m.name);
        return "[" + sb + "]";
    }

    static int autoCtx(int n) {
        int padded = Math.max(n, 17600);
        int positions = (padded + 319) / 320 + 64;
        return Math.min(1500, (positions + 63) / 64 * 64);
    }

    static float[] readWav(File f) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(f)) {
            if (in.getFormat().getSampleRate() != 16000 || in.getFormat().getChannels() != 1)
                throw new IllegalArgumentException(f + " must be 16 kHz mono");
            byte[] b = new byte[(int) in.getFrameLength() * 2];
            int off = 0, r;
            while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            float[] out = new float[b.length / 2];
            for (int i = 0; i < out.length; i++) out[i] = bb.getShort() / 32768f;
            return out;
        }
    }
}
