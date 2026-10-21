#!/usr/bin/env python3
"""Babble builder: pick a Forge version, get a mod jar.

    ./build.py                       interactive TUI
    ./build.py 1.20.1                newest recommended Forge for 1.20.1, then options screen
    ./build.py 1.20.1-47.4.10 -y     no questions asked
    ./build.py 1.21.1 -y --model base.en-q5_1 --cpu-only
    ./build.py --mic-test            talk into your mic and see what would be destroyed (no Minecraft needed)

Everything it downloads (whisper.cpp, models, Forge MDKs, Gradle, JDKs that Gradle provisions,
Minecraft itself) stays in ./.cache, and finished jars go to ./dist.
"""

import argparse
import hashlib
import json
import os
import platform as platform_mod
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

try:
    import curses  # not available on Windows' Python; only the interactive TUI needs it
except ImportError:
    curses = None
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / ".cache"
DIST = ROOT / "dist"
LOGS = CACHE / "logs"

WHISPER_TAG = "v1.9.5"
WHISPER_REPO = "https://github.com/ggml-org/whisper.cpp"
FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge"
FORGE_PROMOS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-{}.bin"
MIN_MC = (1, 16, 5)

MODELS = [
    ("tiny.en-q8_0", "tiny.en, 8-bit · 44 MB · fastest (default)"),
    ("tiny.en", "tiny.en, full precision · 75 MB"),
    ("base.en-q5_1", "base.en, 5-bit · 57 MB · more accurate, ~2.5x slower on CPU"),
    ("base.en", "base.en, full precision · 142 MB"),
    ("small.en-q5_1", "small.en, 5-bit · 181 MB · most accurate, GPU recommended"),
]
PLATFORM_MODES = [("host", "this computer only (lean jar)"),
                  ("all", "Linux + Windows + macOS (universal jar)"),
                  ("all-cuda", "universal + CUDA engines (-cuda jar: bigger, for NVIDIA + CUDA runtime)")]
GPU_MODES = [("auto", "every GPU engine this computer can build"), ("cpu", "CPU only")]
CUDA_ARCHS = [("native", "this machine's GPU only (fast build)"), ("portable", "all NVIDIA GPUs since RTX 20xx (slower build)")]

MOD_VERSION = "1.1.0"

GITHUB_REPO = "MosheKash/Babble"
# Universal jars use natives built by .github/workflows/natives.yml and published as release natives-<key>.
NATIVES_URL = "https://github.com/" + GITHUB_REPO + "/releases/download/natives-{key}/babble-natives-{key}.zip"
# Runtime preference: the first library that loads wins (each GPU build also contains the CPU backend).
BACKEND_ORDER = ["cuda", "vulkan", "metal", "cpu"]
VULKAN_HEADERS_TAG = "v1.4.363"
SPIRV_HEADERS_TAG = "vulkan-sdk-1.4.363.0"

# Class names that differ before Minecraft 1.17 (Forge kept MCP class names with official mappings).
RENAMES_PRE_1_17 = {
    "CommandSourceStack": "CommandSource",
    "ServerLevel": "ServerWorld",
    "ServerPlayer": "ServerPlayerEntity",
    "LocalPlayer": "ClientPlayerEntity",
    "Component": "ITextComponent",
    "PoseStack": "MatrixStack",
    "AbstractSliderButton": "AbstractSlider",
    "EditBox": "TextFieldWidget",
}

C = {"b": "\033[1m", "d": "\033[2m", "r": "\033[31m", "g": "\033[32m", "y": "\033[33m", "c": "\033[36m", "0": "\033[0m"}
if not sys.stdout.isatty():
    C = {k: "" for k in C}


# ---------------------------------------------------------------- versions

def mc_tuple(mc):
    parts = [int(p) for p in re.findall(r"\d+", mc)][:3]
    return tuple(parts + [0] * (3 - len(parts)))


def mc_code(mc):
    a, b, c = mc_tuple(mc)
    return a * 10000 + b * 100 + c


def fetch(url, dest, max_age=None):
    """Download url to dest (cached). With max_age, refresh when older, but keep the stale copy offline."""
    dest = Path(dest)
    if dest.exists() and (max_age is None or time.time() - dest.stat().st_mtime < max_age):
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "babble-builder"})
        with urllib.request.urlopen(req, timeout=60) as r, open(tmp, "wb") as f:
            total = int(r.headers.get("Content-Length") or 0)
            done = 0
            while chunk := r.read(1 << 20):
                f.write(chunk)
                done += len(chunk)
                if total > 5 << 20 and sys.stdout.isatty():
                    print(f"\r    {dest.name}: {done >> 20}/{total >> 20} MB", end="", flush=True)
            if total > 5 << 20 and sys.stdout.isatty():
                print("\r\033[K", end="")
        tmp.replace(dest)
    except Exception:
        tmp.unlink(missing_ok=True)
        if dest.exists():
            return dest
        raise
    return dest


class Entry:
    def __init__(self, full, promos):
        self.full = full
        self.mc, self.forge = full.split("-", 1)
        self.tags = [t for t in ("recommended", "latest") if promos.get(f"{self.mc}-{t}") == self.forge]
        self.forge_key = tuple(int(x) for x in re.findall(r"\d+", self.forge))

    def label(self):
        tag = "★ recommended" if "recommended" in self.tags else ("latest" if self.tags else "")
        return f"{self.mc:<10} {self.forge:<12} {tag}"


def load_versions():
    meta = fetch(f"{FORGE_MAVEN}/maven-metadata.xml", CACHE / "forge" / "maven-metadata.xml", max_age=86400)
    promos = json.loads(fetch(FORGE_PROMOS, CACHE / "forge" / "promotions_slim.json", max_age=86400).read_text())["promos"]
    entries = []
    for v in ET.parse(meta).getroot().iter("version"):
        full = v.text.strip()
        if full.count("-") != 1:
            continue  # pre-1.10 naming like 1.7.10-10.13.4.1614-1.7.10
        mc = full.split("-")[0]
        if not re.fullmatch(r"\d+(\.\d+){1,2}", mc) or mc_tuple(mc) < MIN_MC:
            continue
        entries.append(Entry(full, promos))
    entries.sort(key=lambda e: (mc_tuple(e.mc), "recommended" in e.tags, "latest" in e.tags, e.forge_key), reverse=True)
    return entries


def filter_versions(entries, query):
    q = query.strip().lower()
    if not q:
        return entries
    exact = [e for e in entries if e.mc == q or e.full == q or e.forge == q]
    prefix = [e for e in entries if e not in exact and (e.full.startswith(q) or e.forge.startswith(q))]
    sub = [e for e in entries if e not in exact and e not in prefix and q in e.full]
    return exact + prefix + sub


def resolve(entries, query):
    matches = filter_versions(entries, query)
    if matches:
        return matches[0]
    if re.fullmatch(r"\d+(\.\d+)+", query.split("-")[0]) and mc_tuple(query.split("-")[0]) < MIN_MC:
        sys.exit(f"Minecraft {query} is older than {'.'.join(map(str, MIN_MC))}, which this mod does not support.")
    sys.exit(f"No Forge release matches '{query}'.")


# ---------------------------------------------------------------- TUI

def tui(entries, initial_query, opts, choose_version):
    def run(scr):
        curses.curs_set(1)
        curses.use_default_colors()
        for i, c in enumerate([curses.COLOR_CYAN, curses.COLOR_GREEN, curses.COLOR_YELLOW, curses.COLOR_RED], 1):
            curses.init_pair(i, c, -1)
        entry = None
        if choose_version:
            entry = version_screen(scr, entries, initial_query)
            if entry is None:
                return None
        else:
            entry = resolve(entries, initial_query)
        return entry if options_screen(scr, entry, opts) else None

    return curses.wrapper(run)


def read_key(scr):
    """get_wch, but also decode raw ESC [ A / ESC O A arrow sequences that some terminals send."""
    k = scr.get_wch()
    if k != "\x1b":
        return k
    scr.nodelay(True)
    try:
        seq = ""
        while len(seq) < 2:
            try:
                seq += scr.get_wch()
            except curses.error:
                break
    finally:
        scr.nodelay(False)
    arrows = {"A": curses.KEY_UP, "B": curses.KEY_DOWN, "C": curses.KEY_RIGHT, "D": curses.KEY_LEFT}
    if len(seq) == 2 and seq[0] in "[O" and seq[1] in arrows:
        return arrows[seq[1]]
    if seq in ("[5", "[6"):
        try:
            scr.get_wch()  # trailing "~"
        except curses.error:
            pass
        return curses.KEY_PPAGE if seq == "[5" else curses.KEY_NPAGE
    return "\x1b"


def header(scr, subtitle):
    scr.erase()
    scr.addstr(0, 2, "Babble builder", curses.A_BOLD | curses.color_pair(1))
    scr.addstr(0, 22, f"— {subtitle}", curses.A_DIM)


def version_screen(scr, entries, query):
    sel, top = 0, 0
    while True:
        h, w = scr.getmaxyx()
        shown = filter_versions(entries, query)
        sel = max(0, min(sel, len(shown) - 1))
        rows = max(1, h - 8)
        top = min(max(top, sel - rows + 1), sel)
        header(scr, "pick a Forge version")
        scr.addstr(2, 2, "Minecraft or Forge version (e.g. 1.20.1, 47.4.10, 26.1):"[: w - 3])
        scr.addstr(3, 2, "> ", curses.color_pair(1) | curses.A_BOLD)
        scr.addstr(3, 4, query[: w - 6])
        scr.addstr(5, 4, f"{'Minecraft':<10} {'Forge':<12}"[: w - 5], curses.A_DIM | curses.A_UNDERLINE)
        if not shown:
            scr.addstr(6, 4, f"No match. Supported: Minecraft {'.'.join(map(str, MIN_MC))} and newer."[: w - 5], curses.color_pair(4))
        for i, e in enumerate(shown[top: top + rows]):
            idx = top + i
            attr = curses.A_REVERSE if idx == sel else 0
            if "recommended" in e.tags:
                attr |= curses.color_pair(2)
            scr.addstr(6 + i, 2, ("▸ " if idx == sel else "  ") + e.label()[: w - 5], attr)
        scr.addstr(h - 1, 2, "type to filter · ↑/↓ PgUp/PgDn select · Enter choose · Esc quit"[: w - 3], curses.A_DIM)
        scr.move(3, 4 + min(len(query), w - 6))
        k = read_key(scr)
        if k in ("\n", "\r", curses.KEY_ENTER) and shown:
            return shown[sel]
        if k == "\x1b":
            return None
        if k == curses.KEY_UP:
            sel -= 1
        elif k == curses.KEY_DOWN:
            sel += 1
        elif k == curses.KEY_PPAGE:
            sel -= rows
        elif k == curses.KEY_NPAGE:
            sel += rows
        elif k in (curses.KEY_BACKSPACE, "\x7f", "\b"):
            query, sel, top = query[:-1], 0, 0
        elif isinstance(k, str) and k.isprintable():
            query, sel, top = query + k, 0, 0


def disabled_field(key, opts):
    """Universal jars use the published natives, so local engine options don't apply."""
    if key in ("gpu", "arch") and opts["platforms"] != "host":
        return True
    return key == "arch" and opts["gpu"] == "cpu"


def options_screen(scr, entry, opts):
    fields = [("Whisper model", MODELS, "model"), ("Platforms", PLATFORM_MODES, "platforms"),
              ("Acceleration", GPU_MODES, "gpu"), ("CUDA targets", CUDA_ARCHS, "arch")]
    row = len(fields)  # start on the Build button
    while True:
        h, w = scr.getmaxyx()
        header(scr, f"Minecraft {entry.mc} · Forge {entry.forge}")
        scr.addstr(2, 2, "←/→ change · ↑/↓ move · Enter build · Esc back/quit"[: w - 3], curses.A_DIM)
        for i, (name, choices, key) in enumerate(fields):
            disabled = disabled_field(key, opts)
            idx = [c[0] for c in choices].index(opts[key])
            attr = curses.A_DIM if disabled else (curses.A_REVERSE if row == i else 0)
            scr.addstr(4 + i * 2, 2, f"{name:<15}", curses.A_BOLD)
            scr.addstr(4 + i * 2, 18, f"‹ {choices[idx][1]} ›"[: max(0, w - 20)], attr)
        attr = curses.A_REVERSE | curses.color_pair(2) | curses.A_BOLD if row == len(fields) else curses.color_pair(2)
        scr.addstr(5 + len(fields) * 2, 18, "[ Build ]", attr)
        scr.addstr(7 + len(fields) * 2, 2, f"Output: dist/babble-{entry.mc}-forge-{entry.forge}{jar_suffix(opts)}.jar"[: w - 3], curses.A_DIM)
        curses.curs_set(0)
        k = read_key(scr)
        if k in ("\n", "\r", curses.KEY_ENTER):
            return True
        if k == "\x1b":
            return False
        if k == curses.KEY_UP:
            row = (row - 1) % (len(fields) + 1)
        elif k == curses.KEY_DOWN:
            row = (row + 1) % (len(fields) + 1)
        elif k in (curses.KEY_LEFT, curses.KEY_RIGHT) and row < len(fields):
            _, choices, key = fields[row]
            if disabled_field(key, opts):
                continue
            ids = [c[0] for c in choices]
            opts[key] = ids[(ids.index(opts[key]) + (1 if k == curses.KEY_RIGHT else -1)) % len(ids)]


# ---------------------------------------------------------------- build steps

class Step:
    """Prints a step header, then a single updating status line while a subprocess runs."""

    def __init__(self, n, total, title):
        self.title = f"[{n}/{total}] {title}"
        print(f"{C['b']}{self.title}{C['0']}", flush=True)
        self.t0 = time.time()

    def run(self, cmd, log, cwd=None, env=None):
        LOGS.mkdir(parents=True, exist_ok=True)
        last = [""]
        with open(log, "a") as lf:
            lf.write(f"\n$ {' '.join(map(str, cmd))}\n")
            lf.flush()
            p = subprocess.Popen([str(c) for c in cmd], cwd=cwd, env=env, stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, text=True, errors="replace")
            stop = threading.Event()

            def spin():
                frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
                i = 0
                while not stop.wait(0.1):
                    if sys.stdout.isatty():
                        cols = shutil.get_terminal_size().columns
                        line = f"    {frames[i % 10]} {int(time.time() - self.t0)}s  {last[0]}"
                        print(f"\r\033[K{C['d']}{line[: cols - 1]}{C['0']}", end="", flush=True)
                    i += 1

            t = threading.Thread(target=spin, daemon=True)
            t.start()
            for line in p.stdout:
                lf.write(line)
                if line.strip():
                    last[0] = line.strip()
            p.wait()
            stop.set()
            t.join()
            if sys.stdout.isatty():
                print("\r\033[K", end="")
        if p.returncode != 0:
            tail = Path(log).read_text(errors="replace").splitlines()[-40:]
            print(f"{C['r']}    ✖ failed (exit {p.returncode}). Last lines of {log}:{C['0']}")
            for line in tail:
                print(f"      {line}")
            sys.exit(1)

    def done(self, note=""):
        print(f"    {C['g']}✔{C['0']} {note + ' ' if note else ''}{C['d']}({time.time() - self.t0:.0f}s){C['0']}", flush=True)


def find_jdks():
    """{major: path} for JDKs (with javac) found in the usual places."""
    roots = [Path("/usr/lib/jvm"), Path("/usr/java"), Path("/opt"), Path.home() / ".sdkman/candidates/java", Path.home() / ".jdks"]
    found = {}
    candidates = [Path(os.environ["JAVA_HOME"])] if os.environ.get("JAVA_HOME") else []
    for r in roots:
        if r.is_dir():
            candidates += [p for p in r.iterdir() if p.is_dir()]
    for home in candidates:
        has_javac = (home / "bin" / "javac").exists() or (home / "bin" / "javac.exe").exists()
        if not has_javac or not (home / "include" / "jni.h").exists():
            continue
        release = home / "release"
        m = re.search(r'JAVA_VERSION="(1\.)?(\d+)', release.read_text()) if release.exists() else None
        if m:
            found.setdefault(int(m.group(2)), home.resolve())
    return found


def host_platform():
    """Native folder name in the jar. macOS builds are universal (arm64 + x86_64) binaries."""
    if sys.platform == "darwin":
        return "macos-universal"
    os_name = "windows" if sys.platform.startswith("win") else "linux"
    machine = platform_mod.machine().lower()
    arch = "x86_64" if machine in ("x86_64", "amd64") else "aarch64" if machine in ("aarch64", "arm64") else machine
    return f"{os_name}-{arch}"


def lib_file(backend):
    """What System.mapLibraryName("babble_<backend>") gives on this OS."""
    if sys.platform.startswith("win"):
        return f"babble_{backend}.dll"
    return f"libbabble_{backend}.dylib" if sys.platform == "darwin" else f"libbabble_{backend}.so"


def natives_key():
    """Identifies a natives build: whisper.cpp version + our JNI sources. CI publishes natives-<key>."""
    h = hashlib.sha1(WHISPER_TAG.encode())
    for f in ("babble_jni.cpp", "CMakeLists.txt"):
        h.update((ROOT / "native" / f).read_bytes().replace(b"\r\n", b"\n"))
    return f"{WHISPER_TAG}-{h.hexdigest()[:10]}"


def find_nvcc():
    for p in [shutil.which("nvcc"), "/opt/cuda/bin/nvcc", "/usr/local/cuda/bin/nvcc"]:
        if p and Path(p).exists():
            return Path(p)
    return None


def vulkan_flags(step, log):
    """CMake flags for a Vulkan build, or (None, reason). Uses $VULKAN_SDK if set (LunarG SDK / CI);
    otherwise the system's glslc + Vulkan loader, fetching the headers into .cache if they're missing."""
    if os.environ.get("VULKAN_SDK"):
        return [f"-DCMAKE_PREFIX_PATH={os.environ['VULKAN_SDK']}"], None
    if not shutil.which("glslc"):
        return None, "glslc (shaderc) not found"
    loader = next((p for p in ["/usr/lib/libvulkan.so", "/usr/lib64/libvulkan.so", "/usr/lib/x86_64-linux-gnu/libvulkan.so",
                               "/usr/lib/libvulkan.so.1", "/usr/lib/x86_64-linux-gnu/libvulkan.so.1"] if Path(p).exists()), None)
    if loader is None:
        return None, "Vulkan loader (libvulkan) not found"
    prefix = CACHE / "vk-prefix"
    if not (prefix / "share" / "cmake" / "SPIRV-Headers").exists() or not (prefix / "include" / "vulkan" / "vulkan.hpp").exists():
        for repo, tag, name in [("KhronosGroup/Vulkan-Headers", VULKAN_HEADERS_TAG, "vulkan-headers"),
                                ("KhronosGroup/SPIRV-Headers", SPIRV_HEADERS_TAG, "spirv-headers")]:
            src = CACHE / f"{name}-{tag}"
            if not (src / "CMakeLists.txt").exists():
                shutil.rmtree(src, ignore_errors=True)
                step.run(["git", "-c", "advice.detachedHead=false", "clone", "-q", "--depth", "1", "--branch", tag,
                          f"https://github.com/{repo}", src], log)
            step.run(["cmake", "-S", src, "-B", src / "_build", f"-DCMAKE_INSTALL_PREFIX={prefix}"], log)
            step.run(["cmake", "--install", src / "_build"], log)
    return [f"-DCMAKE_PREFIX_PATH={prefix}", f"-DVulkan_INCLUDE_DIR={prefix / 'include'}", f"-DVulkan_LIBRARY={loader}"], None


def build_natives(step_no, total, opts, jdk):
    """Builds this computer's native libraries. Returns {platform: [(backend, path), ...]} best first."""
    plat = host_platform()
    step = Step(step_no, total, f"Native speech engine ({plat})")
    src = CACHE / f"whisper.cpp-{WHISPER_TAG}"
    log = LOGS / "natives.log"
    if not (src / "CMakeLists.txt").exists():
        shutil.rmtree(src, ignore_errors=True)
        step.run(["git", "clone", "-q", "--depth", "1", "--branch", WHISPER_TAG, WHISPER_REPO, src], log)

    key = natives_key()
    if sys.platform.startswith("win") and not (shutil.which("ninja") and shutil.which("cl")):
        generator = ["-A", "x64"]  # Visual Studio generator: finds MSVC without a developer prompt
    else:
        generator = ["-G", "Ninja"] if shutil.which("ninja") else []
    jobs = str(os.cpu_count() or 4)
    env = dict(os.environ, CCACHE_DIR=str(CACHE / "ccache"))  # ggml uses ccache when installed; keep it local

    variants = []
    if opts["gpu"] != "cpu":
        if sys.platform == "darwin":
            variants.append(("metal", "universal", ["-DGGML_METAL=ON", "-DCMAKE_OSX_ARCHITECTURES=arm64;x86_64",
                                                    "-DCMAKE_OSX_DEPLOYMENT_TARGET=11.0"]))
        else:
            nvcc = find_nvcc() if opts.get("cuda", True) else None
            if nvcc:
                ver = re.search(r"release (\d+)\.(\d+)", subprocess.run([nvcc, "--version"], capture_output=True, text=True).stdout)
                cuda_ver = (int(ver.group(1)), int(ver.group(2))) if ver else (0, 0)
                archs = "native" if opts["arch"] == "native" else \
                    "75-virtual;86-real;89-real" + (";120-real" if cuda_ver >= (12, 8) else "")
                variants.append(("cuda", opts["arch"], ["-DGGML_CUDA=ON", f"-DCMAKE_CUDA_ARCHITECTURES={archs}",
                                                        f"-DCMAKE_CUDA_COMPILER={nvcc}"]))
            elif opts.get("cuda", True):
                print(f"    {C['d']}· no CUDA toolkit (nvcc): skipping the CUDA engine{C['0']}")
            vk, why = (None, "not requested") if opts.get("only") and "vulkan" not in opts["only"] else vulkan_flags(step, log)
            if vk is not None:
                variants.append(("vulkan", "x86-64-v3", ["-DGGML_VULKAN=ON", *vk]))
            elif why != "not requested":
                print(f"    {C['y']}! skipping the Vulkan engine: {why}{C['0']}")
    if sys.platform != "darwin" or opts["gpu"] == "cpu":
        variants.append(("cpu", "x86-64-v3", []))
    if opts.get("only"):
        variants = [v for v in variants if v[0] in opts["only"]]
        if not variants:
            sys.exit(f"None of the requested engines ({','.join(opts['only'])}) can be built on this computer.")

    libs = []
    for backend, variant, flags in variants:
        out_dir = CACHE / "natives" / f"{plat}-{backend}-{variant}-{key}"
        lib = out_dir / lib_file(backend)
        if not lib.exists():
            # Short hashed build dir: Windows' MSBuild breaks once nested paths pass 260 characters.
            bdir = CACHE / "nb" / hashlib.sha1(f"{plat}-{backend}-{variant}-{key}".encode()).hexdigest()[:10]
            print(f"    building the {backend} engine (first time only, a few minutes)…", flush=True)
            step.run(["cmake", "-S", ROOT / "native", "-B", bdir, *generator, "-DCMAKE_BUILD_TYPE=Release",
                      f"-DWHISPER_DIR={src}", f"-DJAVA_HOME_DIR={jdk}", f"-DLIB_OUTPUT=babble_{backend}",
                      "-DGGML_NATIVE=OFF", *flags], log, env=env)
            step.run(["cmake", "--build", bdir, "--target", "babble", "--config", "Release", "-j", jobs], log, env=env)
            built = next(bdir.rglob(lib.name), None)  # multi-config generators put it in Release/
            if built is None:
                sys.exit(f"Built {backend} but can't find {lib.name} under {bdir}")
            out_dir.mkdir(parents=True, exist_ok=True)
            shutil.copy2(built, lib)
        libs.append((backend, lib))
    step.done(", ".join(f"{b} {l.stat().st_size >> 20} MB" for b, l in libs))
    return {plat: libs}


def universal_natives(step_no, total, cuda):
    """Linux + Windows + macOS natives, as published by the natives CI workflow for this source version.
    The published set includes CUDA engines; they go into the -cuda jar only."""
    key = natives_key()
    step = Step(step_no, total, f"Universal speech engines (natives-{key})")
    dest = CACHE / "natives-universal" / key
    if not (dest / "natives.properties").exists():
        try:
            archive = fetch(NATIVES_URL.format(key=key), CACHE / "natives-universal" / f"babble-natives-{key}.zip")
        except Exception as e:
            sys.exit(f"{C['r']}No published natives for this source version ({key}): {e}.{C['0']}\n"
                     f"Push native/ changes to GitHub so the 'natives' workflow builds them, or build a lean jar.")
        shutil.rmtree(dest, ignore_errors=True)
        with zipfile.ZipFile(archive) as z:
            z.extractall(dest)
    props = dict(l.split("=", 1) for l in (dest / "natives.properties").read_text().split() if "=" in l)
    natives = {}
    for plat, names in props.items():
        names = ",".join(n for n in names.split(",") if cuda or n != "cuda")
        files = {p.name: p for p in (dest / plat).iterdir()}
        natives[plat] = [(n, next(f for name, f in files.items() if re.fullmatch(rf"(lib)?babble_{n}\.(so|dll|dylib)", name)))
                         for n in names.split(",")]
    if cuda and not any(b == "cuda" for libs in natives.values() for b, _ in libs):
        sys.exit(f"{C['r']}natives-{key} has no CUDA engines, so a -cuda jar would be identical to the plain one.{C['0']}")
    step.done(", ".join(f"{p}: {'+'.join(b for b, _ in libs)}" for p, libs in natives.items()))
    return natives


def merge_natives(out, dirs):
    """--merge-natives (CI): combine per-job --natives-only outputs, engines ordered best first."""
    out = Path(out)
    merged = {}
    for d in map(Path, dirs):
        for line in (d / "natives.properties").read_text().split():
            plat, names = line.split("=", 1)
            shutil.copytree(d / plat, out / plat, dirs_exist_ok=True)
            merged.setdefault(plat, set()).update(names.split(","))
    lines = [f"{p}={','.join(sorted(n, key=BACKEND_ORDER.index))}" for p, n in sorted(merged.items())]
    (out / "natives.properties").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


def export_natives(natives, out):
    """--natives-only: lay natives out for the CI workflow to merge into babble-natives-<key>.zip."""
    out = Path(out)
    lines = []
    for plat, libs in natives.items():
        (out / plat).mkdir(parents=True, exist_ok=True)
        for _, lib in libs:
            shutil.copy2(lib, out / plat / lib.name)
        lines.append(f"{plat}={','.join(b for b, _ in libs)}")
    (out / "natives.properties").write_text("\n".join(lines) + "\n")
    print(f"natives for {', '.join(natives)} written to {out}")


def get_model(step_no, total, model):
    step = Step(step_no, total, f"Whisper model {model}")
    path = fetch(MODEL_URL.format(model), CACHE / "models" / f"ggml-{model}.bin")
    step.done(f"{path.stat().st_size >> 20} MB")
    return path


def preprocess(src, mc):
    """Resolve //#if MC >= N / //#elif / //#else / //#endif; '//$' marks code for non-default branches."""
    code = mc_code(mc)

    def cond(expr):
        if not re.fullmatch(r"[\sMC0-9<>=!&|()]+", expr):
            raise ValueError(f"bad condition: {expr}")
        return bool(eval(expr.replace("&&", " and ").replace("||", " or "), {"__builtins__": {}}, {"MC": code}))

    out, stack = [], []  # stack frames: [parent_active, branch_taken, active]
    for line in src.splitlines():
        s = line.strip()
        if s.startswith("//#if "):
            parent = all(f[2] for f in stack)
            c = cond(s[6:])
            stack.append([parent, c, parent and c])
        elif s.startswith("//#elif "):
            f = stack[-1]
            c = not f[1] and cond(s[8:])
            f[2], f[1] = f[0] and c, f[1] or c
        elif s == "//#else":
            f = stack[-1]
            f[2], f[1] = f[0] and not f[1], True
        elif s == "//#endif":
            stack.pop()
        elif all(f[2] for f in stack):
            out.append(line.replace("//$", "", 1) if s.startswith("//$") else line)
    if stack:
        raise ValueError("unterminated //#if")
    text = "\n".join(out) + "\n"
    if mc_tuple(mc) < (1, 17, 0):
        for new, old in RENAMES_PRE_1_17.items():
            text = re.sub(rf"\b{new}\b", old, text)
    return text


MODS_TOML = """modLoader="javafml"
loaderVersion="[{forge_major},)"
license="MIT"

[[mods]]
modId="babble"
version="{version}"
displayName="Babble"
authors="Moshe Kashlinsky <kashlinskymoshe@gmail.com>"
credits="Inspired by Do Not Say the Name of This Block: https://www.curseforge.com/minecraft/mc-mods/do-not-say-the-name-of-this-block"
description='''Say the name of a block and every block of that type around you is destroyed.
Speech recognition runs locally with whisper.cpp ({model}, {accel}).'''

[[dependencies.babble]]
    modId="forge"
    mandatory=true
    versionRange="[{forge_major},)"
    ordering="NONE"
    side="BOTH"

[[dependencies.babble]]
    modId="minecraft"
    mandatory=true
    versionRange="[{mc}]"
    ordering="NONE"
    side="BOTH"
"""


def prepare_project(step_no, total, entry, natives, model_path, opts):
    step = Step(step_no, total, f"Forge MDK {entry.full}")
    mdk_zip = fetch(f"{FORGE_MAVEN}/{entry.full}/forge-{entry.full}-mdk.zip", CACHE / "mdk" / f"forge-{entry.full}-mdk.zip")
    work = CACHE / "work" / entry.full
    if not (work / "build.gradle").exists():
        shutil.rmtree(work, ignore_errors=True)
        with zipfile.ZipFile(mdk_zip) as z:
            z.extractall(work)
        (work / "gradlew").chmod(0o755)

    # Our sources replace the example mod; the MDK's pack.mcmeta stays (its format matches the version).
    shutil.rmtree(work / "src" / "main" / "java", ignore_errors=True)
    # Clear every bundle folder from previous builds, keeping only the MDK's own pack.mcmeta and META-INF;
    # otherwise stale models/natives end up in the jar too.
    for old in (work / "src" / "main" / "resources").glob("*"):
        if old.is_dir() and old.name != "META-INF":
            shutil.rmtree(old)
    java = work / "src" / "main" / "java" / "dev" / "babble"
    shutil.copytree(ROOT / "core" / "src" / "dev" / "babble" / "core", java / "core")
    for f in (ROOT / "mod").glob("*.java"):
        (java / f.name).write_text(preprocess(f.read_text(), entry.mc))

    res = work / "src" / "main" / "resources"
    engines = sorted({b for libs in natives.values() for b, _ in libs}, key=BACKEND_ORDER.index)
    accel = " / ".join({"cuda": "CUDA", "vulkan": "Vulkan", "metal": "Metal", "cpu": "CPU"}[b] for b in engines)
    (res / "META-INF").mkdir(parents=True, exist_ok=True)
    (res / "META-INF" / "mods.toml").write_text(MODS_TOML.format(
        forge_major=entry.forge.split(".")[0], version=MOD_VERSION, mc=entry.mc, model=opts["model"], accel=accel))
    bundle = res / "babble"
    (bundle / "model").mkdir(parents=True)
    h = hashlib.sha1()
    lines = []
    for plat, libs in sorted(natives.items()):
        (bundle / "natives" / plat).mkdir(parents=True)
        for _, lib in libs:
            shutil.copy2(lib, bundle / "natives" / plat / lib.name)
            h.update(lib.read_bytes())
        lines.append(f"natives.{plat}={','.join(b for b, _ in libs)}")  # best first; see Babble.start
    shutil.copy2(model_path, bundle / "model" / model_path.name)
    h.update(model_path.name.encode())
    (bundle / "bundle.properties").write_text(
        f"model={model_path.name}\nstamp={h.hexdigest()[:12]}\nwhisper={WHISPER_TAG}\n" + "\n".join(lines) + "\n")

    # MDK template properties (1.16.5–1.21.x MDKs use these for the jar name and run configs).
    props = work / "gradle.properties"
    if props.exists():
        values = {"mod_id": "babble", "mod_name": "Babble", "mod_license": "MIT", "mod_version": MOD_VERSION,
                  "mod_group_id": "dev.babble", "mod_authors": "Moshe Kashlinsky", "mod_description": "Voice-controlled block destruction"}
        text = props.read_text()
        for k, v in values.items():
            text = re.sub(rf"(?m)^{k}=.*$", f"{k}={v}", text)
        props.write_text(text)
    step.done(str(work.relative_to(ROOT)))
    return work


def gradle_jdk(work, jdks):
    """A JDK able to *run* this MDK's Gradle (compilation uses the MDK's own toolchain setting)."""
    wrapper = (work / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text()
    m = re.search(r"gradle-(\d+)\.(\d+)", wrapper)
    gv = (int(m.group(1)), int(m.group(2))) if m else (8, 8)
    toolchain = re.search(r"JavaLanguageVersion\.of\((\d+)\)", (work / "build.gradle").read_text())
    want = int(toolchain.group(1)) if toolchain else 17
    # Highest Java each Gradle line can run on.
    max_java = 17 if gv < (8, 5) else 21 if gv < (8, 10) else 23 if gv < (9, 0) else 25
    usable = sorted(v for v in jdks if 17 <= v <= max_java)
    if not usable:
        sys.exit(f"Gradle {gv[0]}.{gv[1]} needs a JDK between 17 and {max_java}; install one (e.g. `sudo pacman -S jdk21-openjdk`).")
    pick = want if want in usable else usable[-1]
    return jdks[pick], want, gv


def run_gradle(step_no, total, entry, work, jdks, suffix=""):
    jdk, toolchain, gv = gradle_jdk(work, jdks)
    note = "" if toolchain in jdks else f" (Gradle will download JDK {toolchain} into .cache)"
    step = Step(step_no, total, f"Gradle build · Gradle {gv[0]}.{gv[1]} · Java {toolchain}{note}")
    if not (CACHE / "gradle").exists():
        print(f"    first build for a Minecraft version downloads and decompiles it: expect several minutes", flush=True)
    env = dict(os.environ, JAVA_HOME=str(jdk), GRADLE_USER_HOME=str(CACHE / "gradle"),
               PATH=f"{jdk / 'bin'}{os.pathsep}{os.environ.get('PATH', '')}")
    log = LOGS / f"gradle-{entry.full}.log"
    log.unlink(missing_ok=True)
    shutil.rmtree(work / "build" / "libs", ignore_errors=True)
    # Only offer Gradle the full JDKs we verified: auto-detection happily picks up JRE-only installs
    # (e.g. Arch's java-8-openjdk/jre), which then break ForgeGradle. Anything missing gets downloaded.
    toolchains = ",".join(str(p) for p in sorted(set(jdks.values())))
    step.run(["./gradlew", "build", "--console=plain", "--no-daemon", "-x", "test",
              "-Porg.gradle.java.installations.auto-detect=false",
              f"-Porg.gradle.java.installations.paths={toolchains}"], log, cwd=work, env=env)
    jars = [j for j in (work / "build" / "libs").glob("*.jar") if not re.search(r"-(sources|slim|dev|javadoc)\.jar$", j.name)]
    if not jars:
        sys.exit(f"Gradle finished but produced no jar; see {log}")
    jar = max(jars, key=lambda j: j.stat().st_size)
    DIST.mkdir(exist_ok=True)
    out = DIST / f"babble-{entry.mc}-forge-{entry.forge}{suffix}.jar"
    shutil.copy2(jar, out)
    step.done(f"{out.stat().st_size >> 20} MB")
    return out


def mic_test(opts):
    """Runs tools/VoiceTest.java against the real microphone with the same natives/model the mod bundles."""
    jdks = find_jdks()
    if not jdks:
        sys.exit("No JDK found (need one with javac).")
    jdk = jdks[max(jdks)]
    libs = next(iter(build_natives(1, 3, opts, jdk).values()))
    model = get_model(2, 3, opts["model"])
    step = Step(3, 3, "Compile test harness")
    jars = []
    for a in ("lwjgl", "lwjgl-openal"):
        for suffix in ("", "-natives-linux"):
            name = f"{a}-3.3.3{suffix}.jar"
            jars.append(fetch(f"https://repo1.maven.org/maven2/org/lwjgl/{a}/3.3.3/{name}", CACHE / "lwjgl" / name))
    classes = CACHE / "test-classes"
    cp = os.pathsep.join(map(str, jars))
    sources = list((ROOT / "core" / "src").rglob("*.java")) + [ROOT / "tools" / "VoiceTest.java"]
    step.run([jdk / "bin" / "javac", "--release", "8", "-Xlint:-options", "-nowarn", "-d", classes, "-cp", cp, *sources], LOGS / "mictest.log")
    step.done()
    print(f"\n{C['b']}Speak block names (\"stone\", \"oak log\", \"look at all this dirt\"). Ctrl+C to stop.{C['0']}\n")
    subprocess.run([jdk / "bin" / "java", "-cp", f"{classes}{os.pathsep}{cp}", "dev.babble.core.VoiceTest", "mic",
                    libs[0][1], model, str(opts["gpu"] != "cpu").lower()])


def jar_suffix(opts):
    """Universal: no suffix; universal with CUDA: -cuda; lean: -<platform>."""
    return {"all": "", "all-cuda": "-cuda"}.get(opts["platforms"], "-" + host_platform())


def jar_kind(opts):
    return {"all": "universal (Linux + Windows + macOS)",
            "all-cuda": "universal + CUDA (Linux + Windows + macOS)"}.get(opts["platforms"], f"lean ({host_platform()})")


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description="Build the Babble mod for any Forge version (Minecraft 1.16.5+).")
    ap.add_argument("version", nargs="?", default="", help="Minecraft version (1.20.1), Forge version (47.4.10) or both (1.20.1-47.4.10)")
    ap.add_argument("-y", "--yes", action="store_true", help="skip the options screen and use defaults / flags")
    ap.add_argument("--model", choices=[m[0] for m in MODELS], default=MODELS[0][0])
    ap.add_argument("--universal", action="store_true", help="one jar for Linux + Windows + macOS (natives from the CI release)")
    ap.add_argument("--cuda", action="store_true", help="with --universal: also bundle the CUDA engines (the -cuda jar)")
    ap.add_argument("--cpu-only", action="store_true", help="lean jar with only the CPU engine")
    ap.add_argument("--no-cuda", action="store_true", help="don't build the CUDA engine even if the CUDA toolkit is installed")
    ap.add_argument("--cuda-arch", choices=[a[0] for a in CUDA_ARCHS], default="native")
    ap.add_argument("--install", metavar="MODS_DIR", help="also copy the jar into this mods folder")
    ap.add_argument("--mic-test", action="store_true", help="live microphone test in the terminal, no Minecraft needed")
    ap.add_argument("--natives-only", metavar="DIR", help="(CI) build this computer's natives into DIR, no jar")
    ap.add_argument("--natives-key", action="store_true", help="(CI) print the natives version key and exit")
    ap.add_argument("--only", metavar="ENGINES", help="(CI) with --natives-only: build only these engines, e.g. cuda")
    ap.add_argument("--merge-natives", nargs="+", metavar=("OUT", "DIR"), help="(CI) merge --natives-only outputs into OUT")
    args = ap.parse_args()

    opts = {"model": args.model, "gpu": "cpu" if args.cpu_only else "auto", "arch": args.cuda_arch,
            "cuda": not args.no_cuda, "only": args.only.split(",") if args.only else None,
            "platforms": ("all-cuda" if args.cuda else "all") if args.universal else "host"}
    if args.natives_key:
        return print(natives_key())
    if args.merge_natives:
        return merge_natives(args.merge_natives[0], args.merge_natives[1:])
    if args.natives_only:
        jdks = find_jdks()
        if not jdks:
            sys.exit("No JDK found (need one with javac and include/jni.h; set JAVA_HOME).")
        return export_natives(build_natives(1, 1, opts, jdks[max(jdks)]), args.natives_only)
    if args.mic_test:
        return mic_test(opts)
    print(f"{C['d']}Loading Forge version list…{C['0']}", end="\r", flush=True)
    entries = load_versions()

    if args.yes or not sys.stdin.isatty() or curses is None:
        if not args.version:
            sys.exit("Give a version when running non-interactively, e.g. ./build.py 1.20.1 -y")
        entry = resolve(entries, args.version)
    else:
        entry = tui(entries, args.version, opts, choose_version=not args.version)
        if entry is None:
            print("Cancelled.")
            return

    print(f"{C['c']}{C['b']}Babble{C['0']} for Minecraft {C['b']}{entry.mc}{C['0']} · Forge {C['b']}{entry.forge}{C['0']}"
          f" · model {opts['model']} · {jar_kind(opts)}\n")
    jdks = find_jdks()
    if not jdks:
        sys.exit("No JDK found (need one with javac, 17 or newer). Install e.g. `jdk21-openjdk`.")
    jni_jdk = jdks[max(jdks)]

    t0 = time.time()
    if opts["platforms"] == "host":
        natives = build_natives(1, 4, opts, jni_jdk)
    else:
        natives = universal_natives(1, 4, cuda=opts["platforms"] == "all-cuda")
    model = get_model(2, 4, opts["model"])
    work = prepare_project(3, 4, entry, natives, model, opts)
    jar = run_gradle(4, 4, entry, work, jdks, jar_suffix(opts))

    if args.install:
        dest = Path(args.install).expanduser()
        dest.mkdir(parents=True, exist_ok=True)
        for old in dest.glob("babble-*.jar"):
            old.unlink()
        shutil.copy2(jar, dest / jar.name)
        print(f"    installed into {dest}")
    print(f"\n{C['g']}{C['b']}Done in {time.time() - t0:.0f}s:{C['0']} {jar}")
    print(f"{C['d']}Drop it in your instance's mods/ folder. Config: config/babble.properties (created on first launch).{C['0']}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nInterrupted.")
        sys.exit(130)
