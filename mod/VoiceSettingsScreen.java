package dev.babble;

// In-game settings: Mods -> Babble -> Config, or /babble. Every change is saved and
// applied immediately (model and GPU switches hot-swap in the background, no restart).
// Two pages: General (model + toggles) and Tuning (numbers: slider plus a box to type the value).
// Built from Button / AbstractSliderButton / EditBox, the parts of Minecraft's GUI API that change least.

import dev.babble.core.Microphone;
import dev.babble.core.Models;
import dev.babble.core.SpeechEngine;
import dev.babble.core.Babble;
import dev.babble.core.VoiceConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
//#if MC >= 11700
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
//#else
//$import net.minecraft.client.gui.widget.AbstractSlider;
//$import net.minecraft.client.gui.widget.button.Button;
//$import net.minecraft.client.gui.widget.TextFieldWidget;
//$import net.minecraft.client.gui.screen.Screen;
//$import net.minecraft.util.text.ITextComponent;
//$import net.minecraft.util.text.StringTextComponent;
//$import com.mojang.blaze3d.matrix.MatrixStack;
//#endif
//#if MC >= 11700 && MC < 11900
//$import net.minecraft.network.chat.TextComponent;
//#endif
//#if MC >= 260100
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#elif MC >= 12000
//$import net.minecraft.client.gui.GuiGraphics;
//#elif MC >= 11700
//$import com.mojang.blaze3d.vertex.PoseStack;
//#endif

final class VoiceSettingsScreen extends Screen {
    private static final int GENERAL = 0, TUNING = 1;

    private final Screen parent;
    private final int page;
    private final VoiceConfig cfg = BabbleMod.config();
    private final List<Runnable> labels = new ArrayList<Runnable>();
    private final List<String> mics = new ArrayList<String>();
    private int modelIndex;
    private int rowStep = 22;

    VoiceSettingsScreen(Screen parent) {
        this(parent, GENERAL);
    }

    private VoiceSettingsScreen(Screen parent, int page) {
        super(text(page == GENERAL ? "Babble" : "Babble §7— Tuning"));
        this.parent = parent;
        this.page = page;
        String current = cfg.model.isEmpty() ? Models.bundledId() : cfg.model;
        for (int i = 0; i < Models.ALL.size(); i++) if (Models.ALL.get(i).id.equals(current)) modelIndex = i;
    }

    /** Opens the screen on the next tick (after the chat screen that ran /babble has closed). */
    static void openLater() {
        Minecraft mc = Minecraft.getInstance();
        // execute() from another thread always queues; from the render thread it may run immediately.
        java.util.concurrent.CompletableFuture.runAsync(() -> mc.execute(() -> show(mc, new VoiceSettingsScreen(null))));
    }

    private static void show(Minecraft mc, Screen screen) {
//#if MC >= 260100
        mc.gui.setScreen(screen);
//#else
//$     mc.setScreen(screen);
//#endif
    }

    @Override
    protected void init() {
        labels.clear();
        if (page == GENERAL) initGeneral();
        else initTuning();
        button(width / 2 - 155, height - 26, 150, () -> page == GENERAL ? "Tuning ▸" : "◂ General",
                () -> show(minecraft, new VoiceSettingsScreen(parent, page == GENERAL ? TUNING : GENERAL)));
        button(width / 2 + 5, height - 26, 150, () -> "Done", this::onClose);
        refreshLabels();
    }

    // ------------------------------------------------------------------ General page

    private void initGeneral() {
        mics.clear();
        mics.add("");
        mics.addAll(Microphone.listDevices());
        if (!cfg.micDevice.isEmpty() && !mics.contains(cfg.micDevice)) mics.add(cfg.micDevice);

        int left = width / 2 - 155, right = width / 2 + 5, y = 30;
        button(left, y, 235, this::modelLabel, () -> modelIndex = (modelIndex + 1) % Models.ALL.size());
        button(left + 240, y, 70, this::modelActionLabel, this::modelAction);
        y += 28;

        button(left, y, 150, () -> "Voice: " + onOff(cfg.enabled), () -> {
            cfg.enabled = !cfg.enabled;
            if (cfg.enabled) VoiceClient.ensureStarted();
        });
        button(right, y, 150, () -> "Mic: " + (cfg.micDevice.isEmpty() ? "System default" : shorten(cfg.micDevice, 18)), () -> {
            cfg.micDevice = mics.get((mics.indexOf(cfg.micDevice) + 1) % mics.size());
            SpeechEngine e = Babble.engine();
            if (e != null) e.reopenMic();
        });
        y += 22;
        button(left, y, 150, () -> "Drop items: " + onOff(cfg.dropItems), () -> cfg.dropItems = !cfg.dropItems);
        button(right, y, 150, () -> "Shape: " + (cfg.sphere ? "Sphere" : "Cube"), () -> cfg.sphere = !cfg.sphere);
        y += 22;
        button(left, y, 150, this::gpuLabel, () -> {
            if (!Babble.gpuAvailable()) return;
            cfg.useGpu = !cfg.useGpu;
            Babble.reload();
        });
        button(right, y, 150, () -> "Show heard: " + onOff(cfg.showHeard), () -> cfg.showHeard = !cfg.showHeard);
        y += 22;
        button(left, y, 150, () -> "Debug chat: " + onOff(cfg.debugChat), () -> cfg.debugChat = !cfg.debugChat);
        button(right, y, 150, () -> "Mod vocabulary: " + onOff(cfg.modVocabulary), () -> {
            cfg.modVocabulary = !cfg.modVocabulary;
            VoiceClient.refreshNames();
        });
        y += 22;
        button(left, y, 150, () -> "Show transcripts: " + onOff(cfg.showTranscripts), () -> cfg.showTranscripts = !cfg.showTranscripts);
        button(right, y, 150, () -> "Longer names: " + (cfg.waitForLongerNames ? "\u00a7eWait" : "\u00a7aInstant"),
                () -> cfg.waitForLongerNames = !cfg.waitForLongerNames);
    }

    private Models.Info selected() {
        return Models.ALL.get(modelIndex);
    }

    private String inUse() {
        SpeechEngine e = Babble.engine();
        if (e != null) return e.model().name;
        return cfg.model.isEmpty() ? Models.bundledId() : cfg.model;
    }

    private String modelLabel() {
        Models.Info m = selected();
        String state = m.id.equals(inUse()) ? "§a✔" : Models.available(m.id) ? "§7installed" : "§7" + m.sizeMb + " MB";
        return "Model: " + m.id + " §7(" + m.blurb + ") " + state;
    }

    private String modelActionLabel() {
        Models.Info m = selected();
        Integer p = Models.progress(m.id);
        if (m.id.equals(Babble.loading())) return "Loading…";
        if (p != null && p >= 0) return p + "%";
        if (m.id.equals(inUse())) return "In use";
        if (Models.available(m.id)) return "Use";
        return p != null ? "§cRetry" : "Download";
    }

    private void modelAction() {
        final Models.Info m = selected();
        if (m.id.equals(inUse()) || Babble.loading() != null) return;
        if (Models.available(m.id)) {
            use(m.id);
        } else {
            // Switch to it as soon as it's downloaded; the player asked for this model.
            Models.download(m.id, () -> Minecraft.getInstance().execute(() -> use(m.id)));
        }
    }

    private void use(String id) {
        cfg.model = id.equals(Models.bundledId()) ? "" : id;
        cfg.save();
        if (Babble.engine() != null) Babble.reload();
        else if (cfg.enabled) VoiceClient.ensureStarted();
    }

    private String gpuLabel() {
        if (!Babble.gpuAvailable()) return "Acceleration: CPU §7(no CUDA)";
        SpeechEngine e = Babble.engine();
        boolean onGpu = e != null && Babble.loading() == null ? e.model().gpu : cfg.useGpu;
        return "Acceleration: " + (onGpu ? "GPU" : "CPU");
    }

    // ------------------------------------------------------------------ Tuning page

    private void initTuning() {
        // Nine rows: space them to fit small windows (854x480 at GUI scale 2 is only 240 px tall).
        rowStep = Math.max(19, Math.min(22, (height - 62) / 9));
        int y = 28;
        number(y, "Radius", " blocks", 1, 64, () -> cfg.radius, v -> {
            cfg.radius = v;
            // Single-player shares this config with the built-in server, whose cap would clamp it.
            if (cfg.maxRadius < v) cfg.maxRadius = v;
        });
        number(y += rowStep, "End of speech", " ms", 60, 3000, () -> cfg.silenceMs, v -> cfg.silenceMs = v);
        number(y += rowStep, "Mid-speech checks every", " ms", 0, 2000, () -> cfg.partialIntervalMs, v -> cfg.partialIntervalMs = v);
        number(y += rowStep, "Speech threshold", " dB over noise", 1, 40, () -> (int) Math.round(cfg.vadMarginDb), v -> cfg.vadMarginDb = v);
        number(y += rowStep, "Minimum loudness", " dBFS", -90, -10, () -> (int) Math.round(cfg.vadMinDb), v -> cfg.vadMinDb = v);
        number(y += rowStep, "Listening window", " ms", 1000, SpeechEngine.MAX_UTTERANCE_S * 1000, () -> cfg.maxUtteranceMs, v -> cfg.maxUtteranceMs = v);
        number(y += rowStep, "Repeat cooldown", " ms", 0, 10000, () -> cfg.repeatCooldownMs, v -> cfg.repeatCooldownMs = v);
        number(y += rowStep, "Ignore sounds shorter than", " ms", 0, 600, () -> cfg.minVoicedMs, v -> cfg.minVoicedMs = v);
        number(y += rowStep, "CPU threads", "", 1, Math.max(2, Runtime.getRuntime().availableProcessors()), () -> cfg.threads, v -> cfg.threads = v);
    }

    /** A slider for dragging plus a box for typing an exact value; each keeps the other in sync. */
    private void number(int y, String name, String unit, int min, int max, IntSupplier get, IntConsumer set) {
        int left = width / 2 - 155;
        final NumberSlider slider = new NumberSlider(left, y, 250, rowStep - 1, name, unit, min, max, get, set);
        final EditBox box = new EditBox(font, left + 255, y, 55, rowStep - 1, text(name));
        box.setMaxLength(6);
        box.setValue(String.valueOf(get.getAsInt()));
        box.setResponder(s -> {
            try {
                int v = Integer.parseInt(s.trim());
                if (v < min || v > max) return; // half-typed or out of range: keep the last good value
                set.accept(v);
                cfg.save();
                slider.show(v);
            } catch (NumberFormatException ignored) {
            }
        });
        slider.box = box;
        add(slider);
        add(box);
    }

    private final class NumberSlider extends AbstractSliderButton {
        private final String name, unit;
        private final int min, max;
        private final IntConsumer set;
        EditBox box;

        NumberSlider(int x, int y, int w, int h, String name, String unit, int min, int max, IntSupplier get, IntConsumer set) {
            super(x, y, w, h, text(""), (Math.max(min, Math.min(max, get.getAsInt())) - min) / (double) (max - min));
            this.name = name;
            this.unit = unit;
            this.min = min;
            this.max = max;
            this.set = set;
            updateMessage();
        }

        private int current() {
            return min + (int) Math.round(value * (max - min));
        }

        /** Move the knob to a typed value without feeding it back into the box. */
        void show(int v) {
            value = (v - min) / (double) (max - min);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int v = current();
            String shown = v == 0 && unit.equals(" ms") && name.startsWith("Mid-speech") ? "off" : v + unit;
            setMessage(text(name + ": " + shown));
        }

        @Override
        protected void applyValue() {
            set.accept(current());
            cfg.save();
            if (box != null) box.setValue(String.valueOf(current()));
        }
    }

    // ------------------------------------------------------------------ widgets and drawing

    private static String onOff(boolean b) {
        return b ? "§aON" : "§cOFF";
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private void button(int x, int y, int w, Supplier<String> label, Runnable action) {
//#if MC >= 11903
        Button b = Button.builder(text(""), btn -> clicked(action)).bounds(x, y, w, 20).build();
//#else
//$     Button b = new Button(x, y, w, 20, text(""), btn -> clicked(action));
//#endif
        labels.add(() -> b.setMessage(text(label.get())));
        add(b);
    }

//#if MC >= 11700
    private void add(net.minecraft.client.gui.components.AbstractWidget widget) {
        addRenderableWidget(widget);
    }
//#else
//$ private void add(net.minecraft.client.gui.widget.Widget widget) {
//$     addButton(widget);
//$ }
//#endif

    private void clicked(Runnable action) {
        action.run();
        cfg.save();
        refreshLabels();
    }

    private void refreshLabels() {
        for (Runnable r : labels) r.run();
    }

    private String[] statusLines() {
        if (page != GENERAL) return new String[0]; // the rows use the full height
        String heard = VoiceClient.lastHeard();
        return new String[] {Babble.status(), heard == null ? "§7Say a block name to try it" : "§7Last heard: §f" + heard};
    }

    // Download progress and status change while the screen is open, so labels refresh every frame.
//#if MC >= 260100
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        refreshLabels();
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        String[] lines = statusLines();
        for (int i = 0; i < lines.length; i++) g.centeredText(font, text(lines[i]), width / 2, height - 54 + i * 12, 0xFFFFFFFF);
    }
//#elif MC >= 12000
//$ @Override
//$ public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
//$     refreshLabels();
//#if MC < 12002
//$     renderBackground(g);
//#endif
//$     super.render(g, mouseX, mouseY, partialTick);
//$     g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
//$     String[] lines = statusLines();
//$     for (int i = 0; i < lines.length; i++) g.drawCenteredString(font, text(lines[i]), width / 2, height - 54 + i * 12, 0xFFFFFFFF);
//$ }
//#else
//$ @Override
//$ public void render(PoseStack g, int mouseX, int mouseY, float partialTick) {
//$     refreshLabels();
//$     renderBackground(g);
//$     super.render(g, mouseX, mouseY, partialTick);
//$     drawCenteredString(g, font, title, width / 2, 12, 0xFFFFFFFF);
//$     String[] lines = statusLines();
//$     for (int i = 0; i < lines.length; i++) drawCenteredString(g, font, text(lines[i]), width / 2, height - 54 + i * 12, 0xFFFFFFFF);
//$ }
//#endif

    @Override
    public void onClose() {
        cfg.save();
//#if MC >= 260100
        if (parent != null) minecraft.gui.setScreen(parent);
        else super.onClose();
//#else
//$     minecraft.setScreen(parent);
//#endif
    }

    private static Component text(String s) {
//#if MC >= 11900
        return Component.literal(s);
//#elif MC >= 11700
//$     return new TextComponent(s);
//#else
//$     return new StringTextComponent(s);
//#endif
    }
}
