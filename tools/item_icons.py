"""Иконки предметов GameplayMod 16x16: маркеры-жетоны, мотки верёвок, связка плота, горелка, мяч.
Общий стиль: свет сверху слева, тёмная обводка по внешнему контуру (тон соседнего пикселя), 4-5 тонов на материал."""
import colorsys, math, sys, os
from PIL import Image

OUT = sys.argv[1]
N = 16

def hexc(h):
    h = h.lstrip('#'); return tuple(int(h[i:i+2], 16) for i in (0, 2, 4)) + (255,)

def shade(c, k):
    """k>0 светлее, k<0 темнее (сдвиг светлоты в HLS, с лёгким сдвигом тона к тёплому на свету и к холодному в тени)."""
    r, g, b = (v / 255 for v in c[:3])
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    l = min(1, max(0, l + k))
    h = (h + (-0.015 if k > 0 else 0.02) * abs(k) * 2) % 1
    s = min(1, s * (1 + (0.1 if k < 0 else -0.15) * abs(k) * 3))
    r, g, b = colorsys.hls_to_rgb(h, l, s)
    return (round(r * 255), round(g * 255), round(b * 255), 255)

def ramp(base):
    return [shade(base, 0.22), shade(base, 0.1), base, shade(base, -0.12), shade(base, -0.24)]

L = (-0.55, -0.65, 0.52)
def light(nx, ny):
    nz = math.sqrt(max(0, 1 - nx * nx - ny * ny))
    return nx * L[0] + ny * L[1] + nz * L[2]

def tone(r, v):
    """Освещённость v (-1..1) → тон рампы."""
    idx = 4 - min(4, max(0, int((v + 0.15) / 0.24 + 0.5)))
    return r[idx]

class Icon:
    def __init__(self):
        self.p = [[None] * N for _ in range(N)]
    def set(self, x, y, c):
        if 0 <= x < N and 0 <= y < N and c is not None: self.p[y][x] = c
    def get(self, x, y):
        return self.p[y][x] if 0 <= x < N and 0 <= y < N else None
    def disc(self, cx, cy, rx, ry, base, flat=False):
        r = ramp(base)
        for y in range(N):
            for x in range(N):
                nx, ny = (x + .5 - cx) / rx, (y + .5 - cy) / ry
                if nx * nx + ny * ny <= 1:
                    self.set(x, y, base if flat else tone(r, light(nx, ny)))
    def sprite(self, x0, y0, rows, pal):
        for j, row in enumerate(rows):
            for i, ch in enumerate(row):
                if ch in pal: self.set(x0 + i, y0 + j, pal[ch])
    def line(self, pts, c):
        for (x, y) in pts: self.set(x, y, c)
    def outline(self, k=-0.42, inner=False):
        """Внешняя обводка: пустой пиксель рядом с непустым — тёмный тон соседа (как у ванильных предметов)."""
        add = {}
        for y in range(N):
            for x in range(N):
                if self.p[y][x] is not None: continue
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    c = self.get(x + dx, y + dy)
                    if c is not None and c[3] == 255:
                        add[(x, y)] = shade(c, k); break
        for (x, y), c in add.items(): self.set(x, y, c)
    def save(self, name):
        im = Image.new('RGBA', (N, N), (0, 0, 0, 0))
        for y in range(N):
            for x in range(N):
                if self.p[y][x] is not None: im.putpixel((x, y), self.p[y][x])
        im.save(os.path.join(OUT, name + '.png'))

WHITE = (250, 250, 245, 255); INK = (40, 30, 45, 255)

# ---------------------------------------------------------------- маркеры: жетон (ободок цвета маркера, светлое поле, белый значок с тенью)
def token(name, base, glyph, glyph_color=WHITE):
    ic = Icon()
    ic.disc(8, 8, 7, 7, base)                      # ободок
    inner = shade(base, -0.08)
    r = ramp(inner)
    for y in range(N):                              # поле: вогнутое — свет снизу справа
        for x in range(N):
            nx, ny = (x + .5 - 8) / 5.2, (y + .5 - 8) / 5.2
            if nx * nx + ny * ny <= 1: ic.set(x, y, tone(r, light(-nx, -ny) * 0.6))
    shadow = shade(base, -0.3)
    for j, row in enumerate(glyph):                 # тень значка на поле, потом значок
        for i, ch in enumerate(row):
            if ch != '.': ic.set(4 + i + 1, 4 + j + 1, shadow)
    for j, row in enumerate(glyph):
        for i, ch in enumerate(row):
            if ch == '#': ic.set(4 + i, 4 + j, glyph_color)
            elif ch == '+': ic.set(4 + i, 4 + j, shade(glyph_color, -0.18))
    ic.outline()
    ic.save(name)

token('current_marker', hexc('#2f86c8'), [
    "........",
    ".##..##.",
    "#..##..#",
    "........",
    ".##..##.",
    "#..##..#",
    "........",
    "........"])
token('wind_marker', hexc('#6aa9cc'), [
    ".....##.",
    "....#..#",
    ".......#",
    "#######.",
    "........",
    "#####...",
    ".....#..",
    "...##..."])
token('barrage_marker', hexc('#c8423a'), [
    "....###.",
    ".....##.",
    "....#+#.",
    "...#+...",
    "..#+....",
    ".#+.....",
    "#+#.....",
    ".#......"])
token('deadzone_marker', hexc('#4a2230'), [
    "..####..",
    ".######.",
    "#..##..#",
    "#..##..#",
    "########",
    ".###+##.",
    "..####..",
    "..#.#.#."], glyph_color=(236, 230, 220, 255))
token('launcher_marker', hexc('#e08a2a'), [
    "...##...",
    "..####..",
    ".######.",
    "...##...",
    "...##...",
    "........",
    "########",
    ".+....+."])
token('lava_wave_marker', hexc('#c9461c'), [
    "........",
    "...####.",
    "..#....#",
    ".#..##..",
    ".#.#....",
    "#..#....",
    "#...#...",
    "########"], glyph_color=(255, 214, 92, 255))
token('smoke_marker', hexc('#7d7f86'), [
    "....##..",
    "...#..#.",
    "..##..##",
    ".######.",
    "########",
    "#++++++#",
    ".######.",
    "........"])

# ---------------------------------------------------------------- верёвки: моток с узлом и свисающим концом
def coil(name, base, handle=None):
    ic = Icon()
    r = ramp(base)
    # кольцо мотка: эллипс-кольцо, витки — чередование светлых и тёмных пикселей по диагонали
    cx, cy, R1, R2 = 7.5, 7.0, 6.3, 3.4
    for y in range(N):
        for x in range(N):
            dx, dy = (x + .5 - cx), (y + .5 - cy) * 1.2
            d = math.hypot(dx, dy)
            if R2 <= d <= R1:
                t = math.atan2(dy, dx)
                # освещённость трубки кольца: середина ленты светлее, края темнее; плюс общий свет сверху слева
                across = (d - (R1 + R2) / 2) / ((R1 - R2) / 2)
                v = light(dx / R1 * 0.5, dy / R1 * 0.5) * 0.6 + (1 - across * across) * 0.35 - 0.1
                # витки верёвки: косые бороздки по углу
                if ((t * 7 / math.pi + across * 0.8) % 1) < 0.3: v -= 0.4
                ic.set(x, y, tone(r, v))
    # узел внизу справа и хвост
    # узел и свисающий конец с кисточкой
    ic.sprite(10, 9, ["##", "#+"], {'#': r[1], '+': r[3]})
    for i, (x, y) in enumerate([(11, 11), (11, 12), (12, 13)]):
        ic.set(x, y, r[1] if i % 2 == 0 else r[2])
    ic.sprite(11, 14, ["#+#"], {'#': r[3], '+': r[2]})
    if handle:
        h = ramp(handle)
        ic.sprite(10, 10, [
            "...#..",
            "..#+#.",
            ".#...#",
            "######"][0:0] or [
            "..#..",
            ".#+#.",
            "#+.+#",
            "#####"], {'#': h[1], '+': h[3]})
    ic.outline()
    ic.save(name)

coil('tarzan_rope', hexc('#4f9a3c'))
coil('calm_rope', hexc('#a3243b'))
coil('pull_rope', hexc('#b08a58'))
coil('balloon_lift_rope', hexc('#b08a58'), handle=hexc('#3b78d8'))
coil('balloon_helm_rope', hexc('#b08a58'), handle=hexc('#d23c32'))

# ---------------------------------------------------------------- связка плота: два бревна, перехваченные верёвкой
ic = Icon()
for y0 in (4, 9):
    log = ramp(hexc('#8a5a32'))
    for y in range(y0, y0 + 4):
        for x in range(2, 14):
            ny = (y + .5 - (y0 + 2)) / 2
            ic.set(x, y, tone(log, light(0, ny)))
    end = ramp(hexc('#c79a5e'))
    ic.sprite(13, y0, ["#", "+", "+", "#"], {'#': end[3], '+': end[1]})
    ic.sprite(13, y0 + 1, [".", "o"][0:0], {})
    ic.set(13, y0 + 1, end[0]); ic.set(13, y0 + 2, end[2])
rope = ramp(hexc('#d8c08a'))
for x in (5, 10):
    for y in range(3, 14):
        ic.set(x, y, rope[1] if y % 2 else rope[3])
ic.outline(); ic.save('raft_binder')

# ---------------------------------------------------------------- горелка шара: латунный корпус, сопло, пламя
ic = Icon()
brass = ramp(hexc('#c39a3a'))
for y in range(9, 15):
    for x in range(4, 12):
        ic.set(x, y, tone(brass, light((x + .5 - 8) / 4.5, 0.1)))
for x in range(5, 11): ic.set(x, 9, brass[0])
steel = ramp(hexc('#8b9098'))
for y in range(7, 9):
    for x in range(6, 10): ic.set(x, y, tone(steel, light((x + .5 - 8) / 2.5, 0)))
ic.sprite(4, 0, [
    "...##...",
    "..#oo#..",
    "..#oy#..",
    ".#oyyo#.",
    ".#oywyo#"[0:8],
    "..#yy#..",
    "...bb..."], {'#': (214, 58, 24, 255), 'o': (246, 128, 32, 255), 'y': (255, 214, 72, 255), 'w': (255, 250, 220, 255), 'b': (90, 150, 240, 255)})
ic.outline(); ic.save('balloon_burner')

# ---------------------------------------------------------------- мяч: белый шар с чёрными пятиугольниками
ic = Icon()
ic.disc(8, 8, 6.5, 6.5, (238, 238, 234, 255))
dark = ramp((44, 44, 52, 255))
# пятиугольник в середине (чуть к свету) и края соседних у кромки
center = [".##.", "####", ".##."]
ic.sprite(6, 6, center, {'#': dark[2]})
ic.set(7, 6, dark[0])
# пять соседних пятен по кругу внутри шара (не на кромке, иначе шар похож на шестерню)
for k in range(5):
    a = math.radians(-90 + 72 * k)
    px, py = 8 + 4.6 * math.cos(a), 8 + 4.6 * math.sin(a)
    for dx, dy in ((0, 0), (-1, 0), (0, -1), (-1, -1)):
        x, y = int(px + dx), int(py + dy)
        if (x + .5 - 8) ** 2 + (y + .5 - 8) ** 2 <= 5.6 ** 2: ic.set(x, y, dark[3] if y > 8 else dark[2])
ic.set(5, 4, (255, 255, 255, 255)); ic.set(4, 5, (255, 255, 255, 255))
ic.outline(-0.5); ic.save('ball')
print('ok')
