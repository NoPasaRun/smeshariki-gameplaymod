"""Иконки блоков: скаты (угол, материал, направление), колья, колонны, тёмная завеса; значок краски и вода/воздух для BuildMod.
Пользуется инструментами item_icons.py (тот же стиль)."""
import sys, os, math, random
sys.argv = [sys.argv[0], sys.argv[1]]
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'item_icons.py')).read().split("# ---------------------------------------------------------------- маркеры")[0])

MAT = {
    '': ('ice', hexc('#7fb3f0')),
    '_stone': ('stone', hexc('#8f8f8f')),
    '_dirt': ('dirt', hexc('#8a5c3a')),
}

def texel(kind, base, x, y, v):
    """Тон материала с узором: лёд — светлые косые прожилки, камень — крапинки, земля — комочки."""
    r = ramp(base)
    rnd = random.Random(x * 31 + y * 17 + hash(kind) % 97)
    if kind == 'ice':
        if (x - y) % 5 == 0: v += 0.35
    elif kind == 'stone':
        if rnd.random() < 0.25: v -= 0.25
        elif rnd.random() < 0.15: v += 0.2
    else:
        if rnd.random() < 0.3: v -= 0.3
        elif rnd.random() < 0.12: v += 0.25
    return tone(r, v)

def slide(name, angle, kind, base, field=False, west=False):
    ic = Icon()
    # клин в квадрате 14x13 (x 1..14, низ на y 14): поднимается влево, спускается вправо (по направлению спуска)
    x0, x1, yb = 1, 14, 14
    w = x1 - x0 + 1
    t = math.tan(math.radians(angle))
    hmax = 13
    # клин целиком в поле 14x13: у крутого узкое основание, у пологого — во всю ширину
    span = w if field else min(w, hmax / t)
    for x in range(x0, x1 + 1):
        d = x1 + 1 - x - 0.5
        if field:
            h = 2.5 + d * 0.55 + 1.6 * math.sin((x - x0) / w * math.pi * 1.6)
        else:
            if d > span: continue
            h = d * hmax / span if span < w else d * t
        h = min(hmax, h)
        if h < 0.6: continue
        top = yb - h
        for y in range(int(math.ceil(top - 0.001)), yb + 1):
            depth = y - top
            v = 0.75 if depth < 1.2 else (0.32 - 0.015 * depth)   # гладкая поверхность ската светлее боковины
            ic.set(x, y, texel(kind, base, x, y, v))
    if west:
        ic.p = [row[::-1] for row in ic.p]
    ic.outline()
    ic.save(name)

ANG = {'slide': 45, 'slide_gentle': 22.5, 'slide_steep': 68.2, 'slide_shallow': 11.25}
for suf, (kind, base) in MAT.items():
    for n, a in ANG.items():
        slide(n + suf, a, kind, base)
    slide('slide_field' + suf, 0, kind, base, field=True)
    slide('slide_field_west' + suf, 0, kind, base, field=True, west=True)

def spike(name, kind, h, w):
    ic = Icon()
    base = hexc('#9fd0f5') if kind == 'ice' else hexc('#8a8a8e')
    r = ramp(base)
    yb = 15
    for y in range(yb - h + 1, yb + 1):
        half = (y - (yb - h + 1) + 1) / h * w / 2
        for x in range(N):
            dx = x + .5 - 8
            if abs(dx) <= half + 0.01:
                v = light(dx / max(half, 0.5) * 0.9, -0.2)
                if kind == 'ice' and (x + y) % 4 == 0: v += 0.3
                ic.set(x, y, tone(r, v))
    ic.outline()
    ic.save(name)

for kind in ('ice', 'stone'):
    spike('small_%s_spike' % kind, kind, 8, 5)
    spike('%s_spike' % kind, kind, 12, 7)
    spike('big_%s_spike' % kind, kind, 15, 11)

def column(name, kind):
    ic = Icon()
    base = hexc('#9fd0f5') if kind == 'ice' else hexc('#8a8a8e')
    r = ramp(base)
    for y in range(1, 16):
        d = abs(y + .5 - 8.5) / 7.5                 # 0 в середине, 1 у краёв
        half = 0.7 + d * d * 5.3
        for x in range(N):
            dx = x + .5 - 8
            if abs(dx) <= half:
                v = light(dx / half * 0.9, 0)
                if kind == 'ice' and (x - y) % 4 == 0: v += 0.3
                ic.set(x, y, tone(r, v))
    ic.outline()
    ic.save(name)

column('ice_column', 'ice'); column('stone_column', 'stone')

# тёмная завеса: полупрозрачная тёмная пелена, тает книзу, с бахромой
ic = Icon()
for y in range(1, 15):
    for x in range(2, 14):
        edge = 13 + (1 if (x % 3 == 0) else 0) - (1 if x % 4 == 1 else 0)
        if y > edge: continue
        a = int(235 - (y - 1) * 11)
        c = shade(hexc('#2a1f3d'), -0.02 * (y % 3) + (0.12 if (x + y) % 7 == 0 else 0))
        ic.set(x, y, (c[0], c[1], c[2], max(80, a)))
for x in range(2, 14): ic.set(x, 1, (90, 74, 120, 255))
for x in (2, 13):
    for y in range(1, 3): ic.set(x, y, (60, 48, 84, 255))
ic.save('dark_veil')
print('ok')

# значок краски (BuildMod): радужная капля в правом нижнем углу, с бликом
ic = Icon()
drop = [
    "...#...",
    "..###..",
    ".#####.",
    "#######",
    "#######",
    "#######",
    ".#####.",
]
# радуга сверху вниз: значок «цвет», а не огонёк
cols = [hexc('#e8443a'), hexc('#f08a2c'), hexc('#f5d33a'), hexc('#5cc04a'), hexc('#3a9be0'), hexc('#6a5ad8'), hexc('#a14fc8')]
for j, row in enumerate(drop):
    for i, ch in enumerate(row):
        if ch == '#':
            c = cols[j] if i < 5 else shade(cols[j], -0.12)
            ic.set(8 + i, 8 + j, c)
ic.set(10, 11, (255, 240, 230, 255)); ic.set(10, 12, (255, 240, 230, 255))
ic.outline(-0.5)
ic.save('paint_badge')
print('badge ok')
