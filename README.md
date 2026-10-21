<div align="center">

<img src="assets/logo.png" alt="Babble logo" width="180">

# Babble

### Say a block's name, and it's gone.

*Voice-controlled block breaking for Minecraft Forge, powered by on-device, GPU-accelerated speech recognition.*

[![Latest release](https://img.shields.io/github/v/release/MosheKash/Babble?label=release&color=7dbf3b)](https://github.com/MosheKash/Babble/releases/latest)
![Minecraft 1.16.5 – 26.x](https://img.shields.io/badge/Minecraft-1.16.5%20%E2%80%93%2026.x-8a582f)
![Forge](https://img.shields.io/badge/loader-Forge-1e2a3a)
![Platforms](https://img.shields.io/badge/platforms-Linux%20%7C%20Windows%20%7C%20macOS-4b6cb7)
![GPU](https://img.shields.io/badge/GPU-Vulkan%20%7C%20Metal%20%7C%20CUDA-76b900)
[![License: MIT](https://img.shields.io/badge/license-MIT-3b2d63)](LICENSE)

**[Download](https://github.com/MosheKash/Babble/releases/latest)** · [Features](#features) · [Install](#install) · [Settings](#settings) · [Modpacks](#modpacks) · [Troubleshooting](#troubleshooting) · [Building](#building)

</div>

---

> 🗣️ *"Ugh, there's so much **dirt** here and…"*
>
> 💥 **Dirt** breaks before you finish the sentence.

Babble listens to your microphone while you play. Mention a block, on purpose or in passing, and every block
of that type around you breaks. Speech recognition runs **entirely on your machine** with
[whisper.cpp](https://github.com/ggml-org/whisper.cpp), on your GPU (Vulkan, Metal or CUDA) with automatic CPU fallback.
Nothing is ever sent anywhere.

Inspired by [Do Not Say the Name of This Block](https://www.curseforge.com/minecraft/mc-mods/do-not-say-the-name-of-this-block),
rebuilt to be faster, smarter and run almost everywhere.

## Features

<table>
<tr>
<td width="50%" valign="top">

**⚡ Instant**<br>
Blocks break mid-sentence, about **120 ms** after the word is heard. No waiting for you to stop talking.

</td>
<td width="50%" valign="top">

**🎯 One word, one break**<br>
Every mention counts exactly once, however long you keep talking.

</td>
</tr>
<tr>
<td valign="top">

**🧩 Any modpack**<br>
Reads the game's block list, so every mod's blocks just work, including mod-specific words like "Yellorite".

</td>
<td valign="top">

**📦 Drop-in**<br>
One jar for Linux, Windows and macOS, with Forge the only dependency. The speech model and GPU engines are inside.

</td>
</tr>
<tr>
<td valign="top">

**🎛️ In-game settings**<br>
Sliders, a model picker with in-game downloads, mic selection and a GPU/CPU toggle. Every change applies instantly.

</td>
<td valign="top">

**🕰️ Every version**<br>
Minecraft **1.16.5 through 26.x**. One build script makes a jar for any Forge release.

</td>
</tr>
</table>

## Install

1. Download the jar for your Minecraft version from **[Releases](https://github.com/MosheKash/Babble/releases/latest)**.
   There are two kinds, and both run on Linux, Windows and macOS:
   - **`babble-<minecraft>-forge-<forge>.jar`**: the one most people want. It uses Vulkan, Metal on Macs,
     and falls back to the CPU.
   - **`babble-<minecraft>-forge-<forge>-cuda.jar`**: also tries **CUDA** first, for NVIDIA GPUs with the
     CUDA 12 runtime installed (it comes with the CUDA toolkit). It's slightly faster on NVIDIA and a bigger
     download. Without the CUDA runtime it behaves exactly like the regular jar.

   Or [build it yourself](#building) for any other version.
2. Drop it into your instance's `mods/` folder.
3. Launch, join a world, and start talking. That's it.

| | |
|---|---|
| **Platforms** | Linux and Windows (x86-64) and macOS (Apple Silicon and Intel), with Forge |
| **GPU** | Any GPU with a Vulkan driver on Linux and Windows (NVIDIA, AMD, Intel), and Metal on every Mac: about 0.02 s per check. Without a usable GPU, Babble runs on the CPU automatically (about 0.2 s; x86 CPUs need AVX2, i.e. 2013 or newer). |
| **macOS** | Your launcher needs microphone permission (System Settings → Privacy & Security → Microphone). Prism Launcher asks for it automatically. |
| **Multiplayer** | Install on the server too (blocks are broken server-side). No operator permissions needed. Single-player needs nothing extra. |
| **Privacy** | The microphone is open only while you're in a world with voice on, and audio never leaves your PC. |

On first launch Babble unpacks its model and libraries into `<instance>/babble/`.

## Using it

Just talk. When Babble hears a block name, every block of that type within the radius (6 blocks, in a
sphere, by default) breaks, and the name flashes in your action bar.

- **Names are forgiving:** display names ("Grass Block"), ids, plurals ("logs"), spacing ("deep slate") and
  small mishearings of long names ("stone breaks" → Stone Bricks).
- **The longest name wins:** "stone bricks" breaks Stone Bricks, not Stone.
- **Repeats count:** "dirt here… and more dirt over there" breaks dirt twice.
- **Safe by default:** air is never touched, and you can [exclude any block](#troubleshooting).

### Commands

| Command | |
|---|---|
| `/babble` | Open the settings screen. |
| `/babble debug [on\|off]` | Show a chat line for every heard block: what was heard, which blocks, how fast. |

Both run client-side only, so they work on any server.

## Settings

Open **Mods → Babble → Config** from the main menu, or type **`/babble`** in game. Every change saves and
applies instantly, even switching models or GPU/CPU.

**General:** speech **model** (download and switch in-game), **voice** on/off, **mic**, **drop items**,
sphere/cube **shape**, **GPU/CPU**, **show heard**, **debug chat**, **mod vocabulary**, **show transcripts**,
and **longer names** (*Instant*, or *Wait* to hold "stone" until it's clear you didn't mean "stone bricks").
A live status line shows the active mic, model, GPU, and the last thing it heard.

**Tuning:** sliders, each with a box to type an exact value.

<details>
<summary><b>Tuning reference</b></summary>

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

</details>

### Speech models

| Model | Size | |
|---|---|---|
| `tiny.en-q8_0` | 42 MB | **Bundled.** Fastest; works offline. |
| `tiny.en` | 74 MB | Same model, full precision. |
| `base.en-q5_1` | 57 MB | Noticeably more accurate. **Recommended with a GPU**: only a few ms slower. |
| `base.en` | 141 MB | Full precision. |
| `small.en-q5_1` | 181 MB | Most accurate; GPU recommended. |

Downloaded models live in `<instance>/babble/models/` and survive updates.

## Modpacks

Babble isn't tied to vanilla. Every time you join a world it re-reads the game's block list, so whatever is
installed is what it listens for.

- **Shared names break everything with that name.** If vanilla, Create and Mekanism all add Copper Ore,
  "copper ore" breaks all three.
- **Material words.** Many mods have no block called just "Cinnabar", only Cinnabar Ore, Cinnabar Block and
  so on. Saying the material breaks the whole family. To keep everyday speech safe, this only applies to
  words that appear in no vanilla block name and aren't generic ("advanced", "red", "oak").
- **Modded vocabulary.** The speech model is taught your mods' unusual words ("Yellorite", "Certus Quartz"),
  which roughly doubled correct recognition of modded names in testing.
- **No lag.** Matching a sentence against 30,000 blocks takes under a quarter of a millisecond, off the game
  thread.

## Troubleshooting

<details>
<summary><b>It doesn't react to a word</b></summary>

Turn on **Show transcripts** (or `/babble debug on`) to see what Babble actually hears.
- *Nothing at all:* check **Mic** on the settings page (the status line shows which one is in use), or lower
  **Speech threshold**.
- *The wrong word* (e.g. "Bluestone" for glowstone): switch to the **base.en** model, which is much more
  accurate and nearly free on a GPU.

</details>

<details>
<summary><b>It breaks a shorter name first</b> (stone, then stone bricks)</summary>

Set **Longer names** to *Wait*.

</details>

<details>
<summary><b>It breaks something I didn't mean</b></summary>

Add the block id to `excludedBlocks` in `config/babble.properties`, and use `/babble debug on` to see exactly
what was heard.

</details>

<details>
<summary><b>"Unknown command" in multiplayer</b></summary>

The server doesn't have Babble installed.

</details>

<details>
<summary><b>Acceleration says "CPU (no GPU engine)"</b></summary>

No GPU engine could start: on Linux and Windows that means no Vulkan driver was found. Update your graphics
driver (on Linux, install your distribution's Vulkan driver package, e.g. `mesa-vulkan-drivers` or the NVIDIA
driver), or keep using the CPU fallback. `logs/latest.log` lists each engine Babble tried.

</details>

<details>
<summary><b>PolyMC: "Unable to detect the forge installer!"</b></summary>

That's PolyMC failing to download Forge's installer, not Babble. Use [Prism Launcher](https://prismlauncher.org)
(PolyMC's maintained successor), or put `forge-<version>-installer.jar` from maven.minecraftforge.net into
PolyMC's `libraries/net/minecraftforge/forge/<version>/` folder.

</details>

The game log (`logs/latest.log`) has a `[Babble]` line for every important step: which library and model
loaded, on which GPU, which microphone, and how many block names it's listening for.

<details>
<summary><b>All config keys</b> (<code>config/babble.properties</code>)</summary>

Everything in the settings screen is stored here, created on first launch with every option documented. You
never have to edit it, but you can:

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

</details>

## How it works

<details>
<summary><b>From your voice to broken blocks, in five steps</b></summary>

1. **Listen.** 16 kHz audio from your microphone (through Minecraft's own OpenAL), in 20 ms frames. A voice
   detector compares loudness against the background noise and ignores sounds too short to be words.
2. **Transcribe, repeatedly.** While you talk, everything said so far is transcribed every 120 ms by
   whisper.cpp. On an RTX-class GPU each pass takes 15–30 ms, through CUDA or Vulkan alike.
3. **Match.** The transcript is normalized and compared against every block name in the game: longest name
   first, with fuzzy matching for long names and space-insensitive matching.
4. **Decide.** A name followed by more words fires immediately. A name at the very end of what's been heard
   so far fires when the next pass, 120 ms later, confirms it. Each mention is tracked by when it was said,
   so it fires exactly once.
5. **Break.** The client sends `/babble-break <radius> <block ids>`, and the server breaks the blocks with
   normal drops and particles.

</details>

## Building

One script builds a jar for any Forge release from Minecraft 1.16.5 to 26.x. Type a version, pick options,
press Enter:

```sh
./build.py                     # interactive: pick a version, then options
./build.py 1.20.1 -y           # lean jar for this computer, newest recommended Forge for 1.20.1
./build.py 1.20.1 -y --universal          # universal jar: Linux + Windows + macOS
./build.py 1.20.1 -y --universal --cuda   # universal jar that also tries CUDA first
./build.py 1.21.1-52.1.0 -y    # an exact Forge version
./build.py --mic-test          # test your mic and recognition in the terminal, no Minecraft needed
```

There are three kinds of jar. Each tries its engines in order and uses the first one that works:

| Jar | Engines, in order | |
|---|---|---|
| **Universal** `babble-<mc>-forge-<forge>.jar` | Linux, Windows: Vulkan → CPU · macOS: Metal | Runs everywhere, on any GPU. The main download. |
| **Universal + CUDA** `babble-<mc>-forge-<forge>-cuda.jar` | Linux, Windows: **CUDA** → Vulkan → CPU · macOS: Metal | Same, but tries CUDA 12 first on NVIDIA. Bigger. |
| **Lean** `babble-<mc>-forge-<forge>-<platform>.jar` | Whatever this computer can build, e.g. CUDA → Vulkan → CPU | Only this computer's OS, built locally. Smallest. |

The universal jars' engines are built by the [natives workflow](.github/workflows/natives.yml) on GitHub's Linux,
Windows and macOS machines, published as a `natives-*` pre-release, and downloaded by `build.py`.

Jars land in `dist/`. Each build uses that version's official Forge MDK, so Gradle, ForgeGradle and Java always
match what Forge expects, and missing JDKs (Java 8 for 1.16.5, Java 25 for 26.x) are downloaded automatically.

**Needs** (lean builds): Python 3.8+, git, CMake, a C++ compiler and a JDK (17 or 21). For the Vulkan engine
you also need `glslc` and the Vulkan loader, or the [Vulkan SDK](https://vulkan.lunarg.com/sdk/home) (missing
headers are fetched automatically). The CUDA toolkit adds the CUDA engine. Universal builds only need Python
and a JDK. Everything the build downloads (whisper.cpp, models, Forge MDKs,
Gradle, Minecraft) is kept in `.cache/`; delete it to reclaim the space.

Tested on Minecraft 1.16.5, 1.18.2, 1.19.2, 1.20.1, 1.21.1, 1.21.11 and 26.3.

<details>
<summary><b>Build options</b></summary>

| Option | |
|---|---|
| `--model <id>` | Which speech model to bundle (default `tiny.en-q8_0`). |
| `--universal` | Build the universal jar (Linux + Windows + macOS) from the published engines. |
| `--cuda` | With `--universal`: also bundle the CUDA engines (the `-cuda` jar). |
| `--cpu-only` | Lean jar with only the CPU engine. |
| `--no-cuda` | Don't build the CUDA engine even if the CUDA toolkit is installed. |
| `--cuda-arch native\|portable` | `native` (default) builds for this machine's GPU only; `portable` covers every NVIDIA GPU since the RTX 20 series, which is slower to build. |
| `--install <mods dir>` | Also copy the jar into a mods folder (replacing older Babble jars). |
| `-y` | Skip the options screen. |

</details>

<details>
<summary><b>Project layout</b></summary>

| Path | |
|---|---|
| `core/` | Version-independent Java 8: microphone, voice detection, whisper bindings, name matching, model downloads. |
| `mod/` | The Minecraft side: mod entry point, client, settings screen. One source for every version, with `//#if MC >= 11903`-style conditionals that `build.py` resolves. |
| `native/` | The JNI bridge. whisper.cpp and ggml are statically linked into one library per engine (CUDA, Vulkan, Metal, CPU). |
| `.github/workflows/natives.yml` | Builds the Linux, Windows and macOS engines for universal jars. |
| `tools/VoiceTest.java` | Test harness: benchmarks, streaming recordings through the pipeline, and `--mic-test`. |
| `tools/logo.py` | Draws the pixel-art logo (`assets/logo.png`). |
| `build.py` | The builder and its TUI. |

</details>

## Credits

- **Author:** Moshe Kashlinsky ([kashlinskymoshe@gmail.com](mailto:kashlinskymoshe@gmail.com))
- **Inspiration:** [Do Not Say the Name of This Block](https://www.curseforge.com/minecraft/mc-mods/do-not-say-the-name-of-this-block),
  the original "say a block, lose the block" mod. Babble takes its idea and adds GPU support, instant
  mid-sentence reactions, modpack awareness, in-game settings and many more Minecraft versions.
- **Speech recognition:** [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT) running OpenAI's
  [Whisper](https://github.com/openai/whisper) models (MIT).

<div align="center">

**[MIT](LICENSE)** © 2026 Moshe Kashlinsky

</div>
