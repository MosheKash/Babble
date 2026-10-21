package dev.babble;

// Client-only half: microphone + whisper, turning heard block names into /babble commands.
// Only referenced behind FMLEnvironment.dist == CLIENT, so dedicated servers never load it.

import dev.babble.core.BlockMatcher;
import dev.babble.core.Log;
import dev.babble.core.PromptBuilder;
import dev.babble.core.SpeechEngine;
import dev.babble.core.Trigger;
import dev.babble.core.Babble;
import dev.babble.core.VoiceConfig;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
//#if MC >= 11900
import net.minecraft.network.chat.Component;
//#elif MC >= 11700
//$import net.minecraft.network.chat.TextComponent;
//#else
//$import net.minecraft.util.text.StringTextComponent;
//#endif
//#if MC >= 11700
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.Block;
//#else
//$import net.minecraft.client.entity.player.ClientPlayerEntity;
//$import net.minecraft.block.Block;
//#endif
//#if MC >= 11800
import net.minecraft.commands.Commands;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
//#else
//$import net.minecraftforge.client.event.ClientChatEvent;
//#endif
//#if MC >= 11900
import net.minecraftforge.client.ConfigScreenHandler;
//#elif MC >= 11700
//$import net.minecraftforge.client.ConfigGuiHandler;
//#else
//$import net.minecraftforge.fml.ExtensionPoint;
//#endif
import dev.babble.core.Models;
import net.minecraftforge.fml.ModLoadingContext;
//#if MC < 12106
import net.minecraftforge.common.MinecraftForge;
//#endif
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

final class VoiceClient {
    private VoiceClient() {}

    /** Chat/command packets are capped at 256 characters; a name shared by many mods can need several commands. */
    private static final int MAX_COMMAND = 250;

    private static VoiceConfig cfg;
    private static volatile SpeechEngine engine;
    private static volatile BlockMatcher matcher;
    private static volatile String modPrompt;
    private static volatile Object seenPlayer;
    private static volatile String lastHeard;
    private static boolean started;
    private static File workDir;
    // Speech thread only.
    private static Trigger trigger;

    static void start(VoiceConfig config) {
        cfg = config;
        workDir = new File(FMLPaths.GAMEDIR.get().toFile(), "babble");
        Models.setWorkDir(workDir);
        // Mods list -> Babble -> Config
//#if MC >= 11900
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new VoiceSettingsScreen(parent)));
//#elif MC >= 11700
//$     ModLoadingContext.get().registerExtensionPoint(ConfigGuiHandler.ConfigGuiFactory.class,
//$             () -> new ConfigGuiHandler.ConfigGuiFactory((mc, parent) -> new VoiceSettingsScreen(parent)));
//#else
//$     ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.CONFIGGUIFACTORY,
//$             () -> (mc, parent) -> new VoiceSettingsScreen(parent));
//#endif
//#if MC >= 12106
        RegisterClientCommandsEvent.BUS.addListener(VoiceClient::registerClientCommands);
//#elif MC >= 11800
//$     MinecraftForge.EVENT_BUS.addListener(VoiceClient::registerClientCommands);
//#else
//$     MinecraftForge.EVENT_BUS.addListener(VoiceClient::onChat);
//#endif
        if (cfg.enabled) {
            ensureStarted();
        } else {
            Log.info("Voice is off (turn it on in Mods -> Babble -> Config or /babble)");
        }
    }


    /** Loads whisper and starts listening, once; also called when voice is switched on in the settings screen. */
    static synchronized void ensureStarted() {
        if (started) return;
        started = true;
        Thread init = new Thread(() -> {
            try {
                SpeechEngine e = Babble.start(cfg, workDir, VoiceClient::inWorld, VoiceClient::onTranscript);
                engine = e;
                if (modPrompt != null) e.setPrompt(modPrompt);
            } catch (Throwable t) {
                Log.error("Voice recognition failed to start", t);
            }
        }, "Babble-init");
        init.setDaemon(true);
        init.start();
    }

    /**
     * Speech thread: only listen while in a world. Each time a new player entity shows up (joining a
     * world or server, respawning, changing dimension) the block names are re-read on the client
     * thread, so whatever mods, language and resource packs are active right now are what we match.
     */
    private static boolean inWorld() {
        Minecraft mc = Minecraft.getInstance();
        Object player = mc == null ? null : mc.player;
        if (player != null && player != seenPlayer) {
            seenPlayer = player;
            mc.execute(VoiceClient::rebuildNames);
        }
        return player != null && cfg.enabled;
    }

    /** Client thread: e.g. after the mod-vocabulary setting changed. */
    static void refreshNames() {
        if (Minecraft.getInstance().player != null) rebuildNames();
    }

    static String lastHeard() {
        return lastHeard;
    }

    /** Client thread: registries and translations are only safe to read here. */
    private static void rebuildNames() {
        long t0 = System.nanoTime();
        Set<String> excluded = new HashSet<String>(Arrays.asList(cfg.excludedBlocks.split("\\s*,\\s*")));
        BlockMatcher m = new BlockMatcher();
        List<String> vanilla = new ArrayList<String>();
        List<String> modded = new ArrayList<String>();
        Set<String> namespaces = new HashSet<String>();
        for (Block block : ForgeRegistries.BLOCKS.getValues()) {
            String id = String.valueOf(ForgeRegistries.BLOCKS.getKey(block));
            if (excluded.contains(id)) continue;
            String namespace = id.substring(0, id.indexOf(':'));
            namespaces.add(namespace);
            String display = block.getName().getString();
            // Untranslated names come back as the raw key ("block.mod.thing"); the id alias covers those.
            if (!display.startsWith("block.")) {
                m.add(display, id);
                (namespace.equals("minecraft") ? vanilla : modded).add(display);
            }
            m.add(id.substring(id.indexOf(':') + 1).replace('_', ' '), id);
        }
        m.finish();
        matcher = m;
        String prompt = cfg.modVocabulary && !modded.isEmpty() ? PromptBuilder.build(cfg.prompt, vanilla, modded) : cfg.prompt;
        modPrompt = prompt;
        SpeechEngine e = engine;
        if (e != null) e.setPrompt(prompt);
        Log.info(String.format("Listening for %d block names (%d materials) from %d mods (%d ms)", m.size(), m.materials(), namespaces.size(),
                (System.nanoTime() - t0) / 1_000_000));
        Log.debug("Prompt: " + prompt);
    }

    /** Speech thread: matching runs here so even huge modpacks never stall a frame. */
    private static void onTranscript(String text, boolean isFinal, long latencyMs, long startMs, long lenMs) {
        Minecraft mc = Minecraft.getInstance();
        BlockMatcher m = matcher;
        if (mc == null || m == null) return;
        if (trigger == null || trigger.matcher() != m) trigger = new Trigger(m);
        trigger.waitForLongerNames = cfg.waitForLongerNames;
        trigger.cooldownMs = cfg.repeatCooldownMs;

        Set<String> ids = new LinkedHashSet<String>();
        List<String> names = new ArrayList<String>();
        List<BlockMatcher.Match> matches = trigger.accept(text, isFinal, startMs, lenMs);
        for (BlockMatcher.Match match : matches) {
            ids.addAll(match.ids);
            names.add(match.name);
        }

        if (!ids.isEmpty()) {
            List<String> commands = new ArrayList<String>();
            String prefix = "babble-break " + cfg.radius;
            StringBuilder cmd = new StringBuilder(prefix);
            for (String id : ids) {
                if (cmd.length() + 1 + id.length() > MAX_COMMAND && cmd.length() > prefix.length()) {
                    commands.add(cmd.toString());
                    cmd = new StringBuilder(prefix);
                }
                cmd.append(' ').append(id);
            }
            commands.add(cmd.toString());
            Log.debug("heard " + names + " in \"" + text + "\" (" + latencyMs + " ms) -> " + ids.size() + " block types");
            lastHeard = String.join(", ", names) + " \u00a77in \"" + text + "\" (" + latencyMs + " ms)";
            mc.execute(() -> {
                LocalPlayer player = mc.player;
                if (player == null) return;
                for (String c : commands) sendCommand(mc, player, c);
                if (cfg.showHeard) message(player, "\u00a7c\u2716 " + String.join(", ", names), true);
                if (cfg.debugChat) {
                    for (BlockMatcher.Match match : matches) message(player, debugLine(match, text, isFinal, latencyMs), false);
                }
            });
        } else if (cfg.showTranscripts && isFinal) {
            mc.execute(() -> {
                if (mc.player != null) message(mc.player, "\u00a77\"" + text + "\" (" + latencyMs + " ms)", true);
            });
        }
    }

    /** "[Babble] Heard Copper Ore -> minecraft:copper_ore, create:... +2 more  "copper ore" (21 ms, early)" */
    private static String debugLine(BlockMatcher.Match match, String text, boolean isFinal, long latencyMs) {
        StringBuilder ids = new StringBuilder();
        int shown = 0;
        for (String id : match.ids) {
            if (shown == 3) {
                ids.append(" +").append(match.ids.size() - 3).append(" more");
                break;
            }
            ids.append(shown++ > 0 ? ", " : "").append(id);
        }
        return "\u00a78[Babble] \u00a7fHeard \u00a7e" + match.name + " \u00a77\u2192 " + ids
                + " \u00a78\"" + text + "\" (" + latencyMs + " ms, " + (isFinal ? "after speech" : "mid-speech") + ")";
    }

    private static int openSettings() {
        VoiceSettingsScreen.openLater();
        return 1;
    }

    private static int setDebug(boolean on) {
        cfg.debugChat = on;
        cfg.save();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            message(player, "\u00a78[Babble] \u00a7fHeard-block messages " + (on ? "\u00a7aon" : "\u00a7coff")
                    + (on && !cfg.enabled ? " \u00a77(voice is disabled in config)" : ""), false);
        }
        return 1;
    }

//#if MC >= 11800
    /** /babble (settings screen), /babble debug [on|off]: client-side only, so they work on any server. */
    private static void registerClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("babble")
                .executes(ctx -> openSettings())
                .then(Commands.literal("settings").executes(ctx -> openSettings()))
                .then(Commands.literal("debug")
                        .executes(ctx -> setDebug(!cfg.debugChat))
                        .then(Commands.literal("on").executes(ctx -> setDebug(true)))
                        .then(Commands.literal("off").executes(ctx -> setDebug(false)))));
    }
//#else
//$ /** 1.16/1.17 have no client commands, so catch "/babble ..." before it's sent to the server. */
//$ private static void onChat(ClientChatEvent event) {
//$     String[] parts = event.getMessage().trim().split("\\s+");
//$     if (!parts[0].equalsIgnoreCase("/babble")) return;
//$     event.setCanceled(true);
//$     Minecraft.getInstance().gui.getChat().addRecentChat(event.getMessage());
//$     if (parts.length > 1 && parts[1].equalsIgnoreCase("debug")) {
//$         setDebug(parts.length > 2 ? parts[2].equalsIgnoreCase("on") : !cfg.debugChat);
//$     } else {
//$         openSettings();
//$     }
//$ }
//#endif

    private static void sendCommand(Minecraft mc, LocalPlayer player, String command) {
//#if MC >= 11903
        mc.getConnection().sendCommand(command);
//#elif MC >= 11901
//$     player.commandUnsigned(command);
//#elif MC >= 11900
//$     player.command(command);
//#else
//$     player.chat("/" + command);
//#endif
    }

    /** Action bar (overlay) or chat line, local to this client. */
    private static void message(LocalPlayer player, String text, boolean overlay) {
//#if MC >= 260100
        if (overlay) player.sendOverlayMessage(Component.literal(text));
        else player.sendSystemMessage(Component.literal(text));
//#elif MC >= 11900
//$     player.displayClientMessage(Component.literal(text), overlay);
//#elif MC >= 11700
//$     player.displayClientMessage(new TextComponent(text), overlay);
//#else
//$     player.displayClientMessage(new StringTextComponent(text), overlay);
//#endif
    }
}
