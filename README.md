# Babble

**Say a block's name and it's gone.** Babble listens to your microphone while you play. Say a block's name,
even in passing ("ugh, there's so much *dirt* here"), and every block of that type around you breaks.

Speech recognition runs entirely on your machine with [whisper.cpp](https://github.com/ggml-org/whisper.cpp),
on your NVIDIA GPU (with automatic CPU fallback). Nothing is sent anywhere.

Babble is inspired by [Do Not Say the Name of This Block](https://www.curseforge.com/minecraft/mc-mods/do-not-say-the-name-of-this-block),
and builds on its idea with GPU-accelerated recognition, mid-sentence reactions, modpack support, an
in-game settings screen with a model picker, and builds for every Forge version from 1.16.5 to 26.x.

- **Instant.** Blocks break mid-sentence, about 120 ms after the word is heard, without waiting for you to
  finish talking.
- **Works with any modpack.** It reads the block list from the game, so every mod's blocks are included.
- **Drop-in.** A single jar with Forge as the only dependency. The speech model and native libraries are
  inside it.
- **In-game settings** with sliders, a model picker and live status. No config files to edit.
- **Every Forge version from Minecraft 1.16.5 to 26.x.** The build script makes a jar for whichever one
  you pick.

---

## Installing

1. Get the jar for your Minecraft/Forge version (`babble-<minecraft>-forge-<forge>.jar`, built as described
   [below](#building)).
2. Put it in your instance's `mods/` folder.
3. Launch the game and join a world. That's it.

On first launch Babble unpacks its speech model and native libraries into `<instance>/babble/`, and starts
listening once you're in a world. The microphone is closed whenever you're in a menu, not in a world, or have
voice turned off.

**Requirements:** Linux x86-64 and Forge. For GPU speed you need an NVIDIA GPU with the CUDA runtime
installed. Without it Babble runs on the CPU automatically: still usable, just a bit slower (about 0.2 s
per check instead of 0.02 s).

**Multiplayer:** the server needs Babble installed too, because the blocks are broken server-side. In
single-player nothing extra is needed. No operator permissions are required.

---

## Using it

Just talk. When Babble hears a block name it breaks every block of that type within the radius (6 blocks,
in a sphere, by default) and shows the name in the action bar.

- **Names are flexible.** It understands display names ("Grass Block") and ids ("grass block"), plurals
  ("logs"), spacing ("deep slate") and small mishearings of long names ("stone breaks" → Stone Bricks).
- **The longest name wins.** "Stone bricks" breaks Stone Bricks, not Stone.
- **Each time you say it counts.** "Dirt here… and more dirt over there" breaks dirt twice. Saying a word
  once always breaks it once, however long you keep talking afterwards.
- **Safe defaults.** Air is never touched, and you can exclude any other block (see
  [settings](#all-settings)).

### Settings screen

Open it with **Mods → Babble → Config** from the main menu, or **`/babble`** in game. Changes save and
apply immediately, with no restart, even for switching models or GPU/CPU.

**General page**

| Setting | |
|---|---|
| **Model** | Click to cycle through the speech models, then **Download** / **Use**. The bundled model works offline; the others download in-game with a progress indicator and swap in when ready. |
| **Voice** | Turn listening on or off. |
| **Mic** | Cycles through your capture devices (or the system default). |
| **Drop items** | Whether broken blocks drop as items. |
| **Shape** | Sphere or cube around you. |
| **Acceleration** | GPU or CPU. |
| **Show heard** | Action-bar message when a block is heard. |
| **Debug chat** | A chat line for every heard block (see [Troubleshooting](#troubleshooting)). |
| **Mod vocabulary** | Teach the speech model the unusual words in your mods' block names. |
| **Show transcripts** | Show everything Babble hears in the action bar, even without a block name. |
| **Longer names** | *Instant* (default) breaks "stone" the moment it's heard. *Wait* holds names like "stone" until the next word shows you didn't mean "stone bricks", about 0.3 s slower for those names. |

The bottom of the page shows what's active: microphone, model, GPU or CPU, and the last thing Babble heard.

**Tuning page:** each setting has a slider, plus a box where you can type an exact value.

| Setting | Range | Default | |
|---|---|---|---|
| Radius | 1–64 blocks | 6 | How far around you blocks break. |
| End of speech | 60–3000 ms | 220 | Silence that counts as "done talking". |
| Mid-speech checks every | off–2000 ms | 120 | How often speech is re-checked while you talk. Lower is faster. |
| Speech threshold | 1–40 dB | 12 | How much louder than the background counts as speech. Lower picks up quieter voices. |
| Minimum loudness | -90 to -10 dBFS | -50 | Absolute floor for speech. |
| Listening window | 1–30 s | 5 | How much recent audio is considered while you talk non-stop. |
| Repeat cooldown | 0–10000 ms | 1000 | Safety net: the same block never fires twice within this time. |
| Ignore sounds shorter than | 0–600 ms | 120 | Filters out clicks, pops and breaths. |
| CPU threads | 1–all cores | half your cores, max 8 | Used by the CPU fallback. |

### Commands

| Command | |
|---|---|
| `/babble` | Open the settings screen. |
| `/babble debug [on\|off]` | Toggle a chat line for every heard block. |

Both run on your side only, so they work on any server, even one without Babble. (The mod also registers
`/babble-break` on the server; that's what the client sends when it hears a block, and you never need to type it.)

### Speech models

| Model | Size | |
|---|---|---|
| `tiny.en-q8_0` | 42 MB | **Bundled.** Fastest; works offline. |
| `tiny.en` | 74 MB | Same model, full precision. |
| `base.en-q5_1` | 57 MB | Noticeably more accurate. **Recommended with a GPU**: only a few ms slower. |
| `base.en` | 141 MB | Full precision. |
| `small.en-q5_1` | 181 MB | Most accurate; GPU recommended. |

Downloaded models are stored in `<instance>/babble/models/` and survive updates.

---

## Modpacks

Babble isn't tied to vanilla. Whenever you join a world it re-reads the game's block list, so whatever mods
are installed are what it listens for.

- **Shared names break everything with that name.** If vanilla, Create and Mekanism all add Copper Ore,
  saying "copper ore" breaks all three.
- **Material words.** Many mods have no block called just "Cinnabar", only Cinnabar Ore, Cinnabar Block and
  so on. Saying the material breaks the whole family. To keep normal speech safe, this only applies to words
  that appear in no vanilla block name and aren't generic ("advanced", "red", "oak"...).
- **Modded vocabulary.** The speech model has never heard "Yellorite" or "Certus Quartz". Babble feeds it the
  most common distinctive words from your mods' block names, which roughly doubled correct recognition of
  modded names in testing.
- **No lag.** Matching a sentence against 30,000 blocks takes under a quarter of a millisecond, off the game
  thread.

---

## Troubleshooting

**It doesn't react to a word.** Turn on **Show transcripts** (or `/babble debug on`) to see what Babble
actually hears:
- *Nothing at all:* check **Mic** on the settings page (the status line shows which one is in use), or lower
  **Speech threshold**.
- *The wrong word* (e.g. "Bluestone" for glowstone): switch to the **base.en** model, which is much more
  accurate and nearly free on a GPU.

**It breaks a shorter name first** (stone, then stone bricks): set **Longer names** to *Wait*.

**It breaks something I didn't mean.** Add the block id to `excludedBlocks` in `config/babble.properties`.
Use `/babble debug on` to see exactly what was heard.

**"Unknown command" in multiplayer:** the server doesn't have Babble installed.

**Acceleration says "CPU (no CUDA)":** the CUDA runtime libraries weren't found. Install your distribution's
CUDA package, or keep using the CPU fallback.

**PolyMC: "Unable to detect the forge installer!"** That's PolyMC failing to download Forge's installer, not
Babble. Use [Prism Launcher](https://prismlauncher.org) (PolyMC's maintained successor), or put
`forge-<version>-installer.jar` from maven.minecraftforge.net into PolyMC's
`libraries/net/minecraftforge/forge/<version>/` folder.

The game log (`logs/latest.log`) has a `[Babble]` line for every important step: which library and model
loaded, on which GPU, which microphone, and how many block names it's listening for.

---

## All settings

Everything in the settings screen is stored in `config/babble.properties`, which is created on first launch
with every option documented. You never have to edit it, but you can:

| Key | Default | |
|---|---|---|
| `enabled` | `true` | Voice on/off. |
| `radius` | `6` | Blocks around you. |
| `maxRadius` | `16` | Server-side cap (raised automatically in single-player when you raise the radius). |
| `sphere` | `true` | `false` = cube. |
| `dropItems` | `true` | Drop broken blocks as items. |
| `excludedBlocks` | air variants | Comma-separated block ids that are never broken. |
| `waitForLongerNames` | `false` | See *Longer names* above. |
| `model` | *(bundled)* | Speech model id. |
| `useGpu` | `true` | GPU when available, else CPU. |
| `flashAttention` | `true` | Faster GPU attention. |
| `threads` | half your cores, max 8 | CPU threads. |
| `micBackend` / `micDevice` | `auto` / *(default)* | `openal` or `javasound`, and a capture device name. |
| `silenceMs` | `220` | End of speech. |
| `partialIntervalMs` | `120` | Mid-speech checks. |
| `minPartialMs` | `400` | Audio needed before the first mid-speech check. |
| `maxUtteranceMs` | `5000` | Listening window. |
| `repeatCooldownMs` | `1000` | Repeat cooldown. |
| `minVoicedMs` | `120` | Ignore sounds shorter than this. |
| `vadMarginDb` / `vadMinDb` | `12` / `-50` | Speech threshold / minimum loudness. |
| `prompt` | a list of common blocks | Words the speech model is primed with. Add the blocks you use most. |
| `modVocabulary` | `true` | Add your mods' distinctive words to the prompt. |
| `audioCtx` | `1500` | Whisper's encoder window (smaller is faster on CPU but less reliable). |
| `showHeard` / `showTranscripts` / `debugChat` | `true` / `false` / `false` | Feedback options. |
| `verbose` | `false` | Extra logging. |

---

## How it works

1. **Listen.** 16 kHz audio from your microphone (through Minecraft's own OpenAL), in 20 ms frames. A voice
   detector compares loudness against the background noise and ignores sounds too short to be words.
2. **Transcribe, repeatedly.** While you talk, everything said so far is transcribed every 120 ms by
   whisper.cpp. On an RTX-class GPU each pass takes 15–30 ms.
3. **Match.** The transcript is normalized and compared against every block name in the game: longest name
   first, with fuzzy matching for long names and space-insensitive matching.
4. **Decide.** A name followed by more words fires immediately. A name at the very end of what's been heard
   so far fires when the next pass, 120 ms later, confirms it. Each mention is tracked by when it was said,
   so it fires exactly once.
5. **Break.** The client sends `/babble-break <radius> <block ids>`, and the server breaks the blocks with
   normal drops and particles.

---

## Building

All jars are built by one script. Type a Minecraft or Forge version, pick options, and it does the rest:

```sh
./build.py                     # interactive: pick a version, then options, then Enter
./build.py 1.20.1 -y           # newest recommended Forge for 1.20.1, default options
./build.py 1.21.1-52.1.0 -y    # an exact Forge version
./build.py --mic-test          # test your microphone and recognition in the terminal, no Minecraft needed
```

| Option | |
|---|---|
| `--model <id>` | Which speech model to bundle (default `tiny.en-q8_0`). |
| `--cpu-only` | Skip the CUDA library. |
| `--cuda-arch native\|portable` | `native` (default) builds for this machine's GPU only; `portable` covers every NVIDIA GPU since the RTX 20 series, which is slower to build. |
| `--install <mods dir>` | Also copy the jar into a mods folder (replacing older Babble jars). |
| `-y` | Skip the options screen. |

Jars land in `dist/`. Each build downloads that version's official Forge MDK, so Gradle, ForgeGradle and
Java always match what Forge expects. Missing JDKs (Java 8 for 1.16.5, Java 25 for 26.x) are downloaded
automatically.

**Needs:** Python 3.8+, git, CMake, a C++ compiler, a JDK (17 or 21), and the CUDA toolkit for the GPU build
(without `nvcc` you get a CPU-only build). Everything the build downloads (whisper.cpp, models, Forge MDKs,
Gradle, Minecraft) is kept in `.cache/`; delete it to reclaim the space.

Tested on Minecraft 1.16.5, 1.18.2, 1.19.2, 1.20.1, 1.21.1, 1.21.11 and 26.3. Other versions from 1.16.5 up
build from the same source.

### Project layout

| Path | |
|---|---|
| `core/` | Version-independent Java 8: microphone, voice detection, whisper bindings, name matching, model downloads. |
| `mod/` | The Minecraft side: mod entry point, client, settings screen. One source for every version, with `//#if MC >= 11903`-style conditionals that `build.py` resolves. |
| `native/` | The JNI bridge. whisper.cpp and ggml are statically linked into one library per backend (CUDA, CPU). |
| `tools/VoiceTest.java` | Test harness: benchmarks, streaming recordings through the pipeline, and `--mic-test`. |
| `build.py` | The builder and its TUI. |

---

## Credits

- **Author:** Moshe Kashlinsky ([kashlinskymoshe@gmail.com](mailto:kashlinskymoshe@gmail.com))
- **Inspiration:** [Do Not Say the Name of This Block](https://www.curseforge.com/minecraft/mc-mods/do-not-say-the-name-of-this-block),
  the original "say a block, lose the block" mod. Babble takes its idea and adds GPU support, instant
  mid-sentence reactions, modpack awareness, in-game settings and many more Minecraft versions.
- Speech recognition: [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT) running OpenAI's
  [Whisper](https://github.com/openai/whisper) models (MIT).

## License

[MIT](LICENSE) © 2026 Moshe Kashlinsky
