"""Текстура Бутерброда (client/SandwichModel) 256x256 и значок его яйца призыва 16x16.
Запуск: python3 tools/sandwich_texture.py src/main/resources/assets/gameplaymod/textures
Раскладка: верх булки (0,0)-(128,128) и низ (128,0)-(256,128) — вид сверху, к краю темнее; лист салата (0,128)-(64,192),
черешок слева; глаз (64,128)-(96,160); пятна 16x16: оливка (96,128), петрушка (112,128), сыр (96,144), стебель (112,144), щель рта (96,160), губы (112,160).
Ещё пишет значок морока mob_effect/sandwich_spell.png."""
import math, os, random, sys
import numpy as np
from PIL import Image

OUT = sys.argv[1]
rnd = random.Random(7)

def lerp(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))

def ramp(stops, t):
    t = min(1.0, max(0.0, t))
    for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
        if t <= t1: return lerp(c0, c1, (t - t0) / (t1 - t0))
    return stops[-1][1]

img = np.zeros((256, 256, 4), dtype=np.float64)
img[..., :3] = (176, 118, 58)
img[..., 3] = 255

def bun(x0, stops, grain):
    for y in range(128):
        for x in range(128):
            r = math.hypot(x + .5 - 64, y + .5 - 64) / 62
            n = (rnd.random() - .5) * grain
            # блик сверху слева, как на картинке
            glow = max(0.0, 1 - math.hypot(x - 46, y - 78) / 34) * 14
            img[y, x0 + x, :3] = [v + n + glow for v in ramp(stops, r)]

bun(0, [(0, (206, 150, 78)), (0.55, (186, 124, 60)), (0.85, (160, 98, 46)), (1, (134, 80, 38))], 8)
bun(128, [(0, (172, 100, 52)), (0.6, (152, 84, 42)), (1, (112, 58, 32))], 6)
# кунжут: зёрнышки на верхней половинке
for _ in range(26):
    a, r = rnd.random() * math.tau, 8 + rnd.random() * 44
    cx, cy, rot = 64 + r * math.cos(a), 64 + r * math.sin(a), rnd.random() * math.pi
    for y in range(int(cy) - 4, int(cy) + 5):
        for x in range(int(cx) - 4, int(cx) + 5):
            dx, dy = x + .5 - cx, y + .5 - cy
            u, v = dx * math.cos(rot) + dy * math.sin(rot), -dx * math.sin(rot) + dy * math.cos(rot)
            d = (u / 3.2) ** 2 + (v / 1.7) ** 2
            if d <= 1 and 0 <= x < 128 and 0 <= y < 128:
                img[y, x, :3] = lerp((244, 226, 178), (214, 186, 128), d)

# лист салата: средняя жилка, боковые жилки, к краю темнее
for y in range(64):
    for x in range(64):
        t, s = x / 63, (y - 31.5) / 31.5
        c = lerp((132, 196, 70), (84, 150, 44), abs(s) ** 1.5)
        vein = max(0.0, 1 - abs(s) * 14)
        side = max(0.0, 1 - abs(((t * 5 - abs(s) * 1.6) % 1) - .5) * 9) * (1 - vein) * 0.6
        c = lerp(c, (196, 228, 130), max(vein, side))
        img[128 + y, x, :3] = [v + (rnd.random() - .5) * 6 for v in c]

# глаз-оливка: свет сверху слева, белок, зрачок, блик
for y in range(32):
    for x in range(32):
        nx, ny = (x + .5 - 16) / 16, (y + .5 - 16) / 16
        shade = -nx * 0.35 - ny * 0.6
        c = lerp((104, 112, 40), (150, 158, 66) if shade > 0 else (66, 74, 26), min(1, abs(shade) * 1.3))
        d = math.hypot(x + .5 - 11, y + .5 - 19)
        if d <= 6: c = (240, 240, 226)
        if math.hypot(x + .5 - 10.5, y + .5 - 20) <= 4.2: c = (34, 30, 22)
        if math.hypot(x + .5 - 9, y + .5 - 18.2) <= 1.4: c = (255, 255, 255)
        img[128 + y, 64 + x, :3] = c

for (x0, y0, c) in ((96, 128, (104, 112, 40)), (112, 128, (76, 160, 56)), (96, 144, (228, 198, 96)), (112, 144, (92, 156, 52)), (96, 160, (120, 82, 34)), (112, 160, (244, 218, 124))):
    img[y0:y0 + 16, x0:x0 + 16, :3] = c

os.makedirs(os.path.join(OUT, 'entity'), exist_ok=True)
Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), 'RGBA').save(os.path.join(OUT, 'entity', 'sandwich.png'))

# значок яйца: портрет, как у яиц смешариков; обводка — тёмный тон по контуру
PAL = {'G': (96, 170, 56), 'g': (58, 124, 44), 'E': (120, 128, 46), 'e': (84, 92, 32), 'W': (244, 244, 230), 'K': (34, 30, 22),
       'T': (190, 128, 62), 't': (214, 160, 88), 'd': (154, 94, 46), 's': (244, 226, 178), 'C': (232, 202, 100), 'c': (200, 164, 70),
       'B': (150, 84, 42), 'b': (112, 58, 32)}
ROWS = [
    ".GG........GG.",
    "GGg.g....g.gGG",
    ".G.gEEEEEEg.G.",
    "....EWKKWE....",
    "..TTeEeeEeTT..",
    ".TtttTTsTTTTT.",
    "TTtTsTTTTTsTTT",
    "TTTTTTTsTTTTdd",
    "CTTTsTTTTTTddC",
    "GCCCCCCCCCCCCG",
    "GGcCCCCCCCCcGG",
    "Gg.BBBBBBBB.gG",
    "...bBBBBBBb...",
]
icon = [[None] * 16 for _ in range(16)]
for j, row in enumerate(ROWS):
    for i, ch in enumerate(row):
        if ch in PAL: icon[j + 1][i + 1] = PAL[ch]
out = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
for y in range(16):
    for x in range(16):
        if icon[y][x] is not None:
            out.putpixel((x, y), icon[y][x] + (255,))
            continue
        near = [icon[b][a] for a, b in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)) if 0 <= a < 16 and 0 <= b < 16 and icon[b][a] is not None]
        if near: out.putpixel((x, y), tuple(int(sum(c[i] for c in near) / len(near) * 0.38) for i in range(3)) + (255,))
os.makedirs(os.path.join(OUT, 'item'), exist_ok=True)
out.save(os.path.join(OUT, 'item', 'sandwich_spawn_egg.png'))
# значок морока в списке эффектов 18x18: тот же портрет
effect = Image.new('RGBA', (18, 18), (0, 0, 0, 0))
effect.paste(out, (1, 1))
os.makedirs(os.path.join(OUT, 'mob_effect'), exist_ok=True)
effect.save(os.path.join(OUT, 'mob_effect', 'sandwich_spell.png'))
