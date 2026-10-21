"""Babble banner for the CurseForge/Modrinth description: the logo (2x) next to "BABBLE" and the tagline in a
pixel font, on the logo's background. Drawn on a 200x72 grid and scaled up 5x with no smoothing.

Regenerate:
  python3 tools/logo.py /tmp/logo.pam && python3 tools/banner.py /tmp/logo.pam /tmp/banner.pam \\
    && magick /tmp/banner.pam -filter point -resize 1000x360 assets/banner.png
"""
import random
import sys

W, H = 200, 72
random.seed(11)

FONT = {  # 5x7 pixel glyphs ('#' = on); punctuation is narrower
    "A": [".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    "B": ["####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."],
    "C": [".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."],
    "D": ["####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."],
    "E": ["#####", "#....", "#....", "####.", "#....", "#....", "#####"],
    "G": [".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".###."],
    "I": ["###", ".#.", ".#.", ".#.", ".#.", ".#.", "###"],
    "K": ["#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"],
    "L": ["#....", "#....", "#....", "#....", "#....", "#....", "#####"],
    "M": ["#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"],
    "N": ["#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"],
    "O": [".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
    "S": [".####", "#....", "#....", ".###.", "....#", "....#", "####."],
    "T": ["#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."],
    "Y": ["#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."],
    "'": ["#", "#", ".", ".", ".", ".", "."],
    ",": ["..", "..", "..", "..", "..", ".#", "#."],
    ".": [".", ".", ".", ".", ".", ".", "#"],
    " ": ["..", "..", "..", "..", "..", "..", ".."],
}


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


img = [[(0, 0, 0, 0)] * W for _ in range(H)]


def px(x, y, c):
    if 0 <= x < W and 0 <= y < H:
        img[y][x] = hexc(c) if isinstance(c, str) else c


def text_width(s, scale):
    return sum((len(FONT[ch][0]) + 1) * scale for ch in s) - scale


def text(s, x, y, scale, color, shadow):
    for ch in s:
        g = FONT[ch]
        for gy, row in enumerate(g):
            for gx, v in enumerate(row):
                if v != "#":
                    continue
                for sy in range(scale):
                    for sx in range(scale):
                        px(x + gx * scale + sx + 1, y + gy * scale + sy + 1, shadow)
        for gy, row in enumerate(g):
            for gx, v in enumerate(row):
                if v != "#":
                    continue
                for sy in range(scale):
                    for sx in range(scale):
                        px(x + gx * scale + sx, y + gy * scale + sy, color)
        x += (len(g[0]) + 1) * scale


# background: the logo's purple gradient, with cut corners
top, bot = hexc("#3b2d63"), hexc("#16122a")
for y in range(H):
    t = y / (H - 1)
    c = tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3)) + (255,)
    for x in range(W):
        px(x, y, c)
for (x, y) in [(0, 0), (1, 0), (0, 1), (W - 1, 0), (W - 2, 0), (W - 1, 1),
               (0, H - 1), (1, H - 1), (0, H - 2), (W - 1, H - 1), (W - 2, H - 1), (W - 1, H - 2)]:
    img[y][x] = (0, 0, 0, 0)

# logo (32x32 RGBA PAM from tools/logo.py), drawn at 2x without its own background
raw = open(sys.argv[1], "rb").read()
logo = raw[raw.index(b"ENDHDR\n") + 7:]

title, lines = "BABBLE", ["SAY A BLOCK'S NAME", "AND IT'S GONE."]
block_w = 64 + 12 + max(text_width(title, 3), *(text_width(l, 1) for l in lines))
x0 = (W - block_w) // 2
for y in range(32):
    # the logo's own background is exactly this gradient colour on row y (see tools/logo.py); skip it
    t = y / 31
    bg = tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3))
    for x in range(32):
        r, g, b, a = logo[(y * 32 + x) * 4:(y * 32 + x) * 4 + 4]
        if a == 0 or (r, g, b) == bg:
            continue
        for sy in range(2):
            for sx in range(2):
                px(x0 + x * 2 + sx, 4 + y * 2 + sy, (r, g, b, 255))

# title and tagline, vertically centred as a block
tx = x0 + 64 + 12
ty = (H - (21 + 7 + 7 + 3 + 7)) // 2
text(title, tx, ty, 3, "#f4f1e8", "#120e1f")
text(lines[0], tx, ty + 28, 1, "#b9b0d6", "#120e1f")
text(lines[1], tx, ty + 38, 1, "#b9b0d6", "#120e1f")

# a few block chunks drifting off to the sides
for (x, y, a, b) in [(W - 16, 10, "#86c93f", "#8a582f"), (W - 24, 54, "#9a6538", "#6a4122"),
                     (12, 58, "#86c93f", "#9a6538"), (W - 10, 34, "#97da52", "#80512b")]:
    px(x, y, a); px(x + 1, y, a); px(x, y + 1, b); px(x + 1, y + 1, b)
for (x, y, c) in [(W - 30, 18, "#86c93f"), (8, 14, "#9a6538"), (20, 40, "#86c93f"), (W - 6, 62, "#9a6538")]:
    px(x, y, c)

with open(sys.argv[2], "wb") as f:
    f.write(f"P7\nWIDTH {W}\nHEIGHT {H}\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n".encode())
    f.write(bytes(v for row in img for p in row for v in p))
