"""Babble logo: 32x32 pixel art (speech bubble with a shattering grass block).

Regenerate: python3 tools/logo.py /tmp/logo.pam && magick /tmp/logo.pam -filter point -resize 512x512 assets/logo.png
"""
import random
import sys

N = 32
random.seed(4)


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


img = [[(0, 0, 0, 0)] * N for _ in range(N)]


def px(x, y, c):
    if 0 <= x < N and 0 <= y < N:
        img[y][x] = hexc(c) if isinstance(c, str) else c


def inside(poly, x, y):
    """Point-in-polygon for the pixel centre."""
    x, y = x + 0.5, y + 0.5
    hit = False
    for i in range(len(poly)):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % len(poly)]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            hit = not hit
    return hit


# ---- background: vertical gradient, rounded corners
top, bot = hexc("#3b2d63"), hexc("#16122a")
for y in range(N):
    t = y / (N - 1)
    c = tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3)) + (255,)
    for x in range(N):
        px(x, y, c)
for (x, y) in [(0, 0), (1, 0), (0, 1), (31, 0), (30, 0), (31, 1), (0, 31), (1, 31), (0, 30), (31, 31), (30, 31), (31, 30)]:
    img[y][x] = (0, 0, 0, 0)

# ---- speech bubble
OUT, FILL = "#120e1f", "#f4f1e8"
bx0, by0, bx1, by1 = 3, 3, 28, 22


def in_bubble(x, y):
    if not (bx0 <= x <= bx1 and by0 <= y <= by1):
        return False
    for cx, cy in [(bx0, by0), (bx1, by0), (bx0, by1), (bx1, by1)]:
        if abs(x - cx) + abs(y - cy) < 2:  # rounded corners
            return False
    return True


tail = [(x, 23) for x in range(6, 10)] + [(x, 24) for x in range(5, 8)] + [(4, 25), (5, 25), (3, 26)]
shape = {(x, y) for x in range(N) for y in range(N) if in_bubble(x, y)} | set(tail)
for (x, y) in shape:
    px(x, y, FILL)
for (x, y) in shape:
    for dx, dy in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
        if (x + dx, y + dy) not in shape:
            px(x + dx, y + dy, OUT)

# ---- isometric grass block (classic Minecraft item-icon geometry)
GRASS = ["#86c93f", "#7abd38", "#97da52", "#82c43c"]
GRASS_L = ["#4f8f25", "#478522", "#57992a"]
GRASS_R = ["#3a6a1b", "#335f18", "#41741e"]
DIRT_L = ["#9a6538", "#8a582f", "#a87242", "#80512b"]
DIRT_R = ["#6a4122", "#5c381d", "#744826"]
MID, H = 9, 8  # MID 9 centres the block vertically in the bubble
block = {}
for x in range(8, 24):
    d = min(abs(x - 15), abs(x - 16))
    ht = 4 - d // 2
    for y in range(MID - ht, MID + ht):
        block[(x, y)] = random.choice(GRASS)
    fringe = 1 + (1 if random.random() < 0.5 else 0)
    for i, y in enumerate(range(MID + ht, MID + ht + H)):
        if x <= 15:
            block[(x, y)] = random.choice(GRASS_L) if i < fringe else random.choice(DIRT_L)
        else:
            block[(x, y)] = random.choice(GRASS_R) if i < fringe else random.choice(DIRT_R)
for (x, y), c in block.items():
    px(x, y, c)
for (x, y) in block:  # dark outline so it reads as a thumbnail
    for dx, dy in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
        if (x + dx, y + dy) not in block:
            px(x + dx, y + dy, "#24180c")

# ---- breaking cracks (like Minecraft's block-breaking overlay)
for (x, y) in [(10, 13), (11, 14), (11, 15), (12, 16), (13, 17), (12, 18), (9, 16),
               (20, 12), (19, 13), (20, 14), (21, 15), (20, 16), (22, 17)]:
    px(x, y + 1, "#1c1208")

# ---- chunks flying off (two escape the bubble)
def chunk(x, y, top, side):
    px(x, y, top); px(x + 1, y, top); px(x, y + 1, side); px(x + 1, y + 1, side)

chunk(23, 4, "#8fd34c", "#8a582f")
chunk(25, 7, "#7dbf3b", "#6a4122")
chunk(6, 10, "#9a6538", "#80512b")
px(30, 4, "#86c93f")
px(30, 7, "#9a6538")
px(27, 1, "#8fd34c")
px(5, 14, "#8a582f")

out = sys.argv[1]
with open(out, "wb") as f:
    f.write(f"P7\nWIDTH {N}\nHEIGHT {N}\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n".encode())
    f.write(bytes(v for row in img for p in row for v in p))
