"""Generates the pixel-art GUI textures for the Minelization UI kit.

Run from the repository root:  python tools/gen_gui_textures.py
Writes PNGs to src/client/resources/assets/grandstrategy/textures/gui/.
Pure standard library, so it runs anywhere without extra packages.
"""
import os
import random
import struct
import zlib

OUT = os.path.join("src", "client", "resources", "assets", "grandstrategy", "textures", "gui")


def png(path, w, h, pixels):
    raw = b"".join(b"\x00" + bytes(c for px in row for c in px) for row in pixels)

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    data += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(data)


def canvas(w, h, color=(0, 0, 0, 0)):
    return [[color for _ in range(w)] for _ in range(h)]


def shade(c, d):
    return (max(0, min(255, c[0] + d)), max(0, min(255, c[1] + d)), max(0, min(255, c[2] + d)), c[3])


def frame(img, w, h, inner, rnd, border=6):
    wood = [(58, 40, 25, 255), (74, 52, 32, 255), (90, 64, 39, 255), (66, 46, 28, 255)]
    for y in range(h):
        for x in range(w):
            edge = min(x, y, w - 1 - x, h - 1 - y)
            if edge == 0:
                img[y][x] = (20, 13, 8, 255)
            elif edge < border - 1:
                base = wood[(x * 3 + y * 7 + (x // 3)) % len(wood)]
                if x < border - 1 and y >= edge and x == edge:
                    base = shade(base, 18)
                if y == edge and x >= edge:
                    base = shade(base, 22)
                if x == w - 1 - edge or y == h - 1 - edge:
                    base = shade(base, -14)
                img[y][x] = shade(base, rnd.randint(-6, 6))
            elif edge == border - 1:
                img[y][x] = (28, 19, 11, 255)
            else:
                img[y][x] = inner(x, y)
    gold = (232, 194, 82, 255)
    dark_gold = (150, 112, 38, 255)
    for cx, cy in ((2, 2), (w - 4, 2), (2, h - 4), (w - 4, h - 4)):
        img[cy][cx] = gold
        img[cy][cx + 1] = dark_gold
        img[cy + 1][cx] = dark_gold
        img[cy + 1][cx + 1] = (110, 80, 26, 255)


def panel_dark():
    rnd = random.Random(1)
    w = h = 32
    img = canvas(w, h)

    def inner(x, y):
        v = 30 + rnd.randint(-3, 3)
        return (v - 4, v - 2, v + 4, 238)

    frame(img, w, h, inner, rnd)
    png(os.path.join(OUT, "panel_dark.png"), w, h, img)


def panel_parchment():
    rnd = random.Random(2)
    w = h = 32
    img = canvas(w, h)

    def inner(x, y):
        edge = min(x, y, w - 1 - x, h - 1 - y) - 6
        base = (233, 217, 176, 255)
        d = rnd.randint(-7, 5) - (10 if edge <= 1 else 0)
        return shade(base, d)

    frame(img, w, h, inner, rnd)
    png(os.path.join(OUT, "panel_parchment.png"), w, h, img)


def button():
    rnd = random.Random(3)
    w, h = 20, 20
    states = [
        ((112, 112, 112, 255), (165, 165, 165, 255), (60, 60, 60, 255)),
        ((150, 128, 70, 255), (236, 204, 110, 255), (90, 70, 30, 255)),
        ((70, 70, 70, 255), (100, 100, 100, 255), (40, 40, 40, 255)),
    ]
    img = canvas(w, h * 3)
    for s, (fill, light, dark) in enumerate(states):
        for y in range(h):
            for x in range(w):
                yy = y + s * h
                if x == 0 or y == 0 or x == w - 1 or y == h - 1:
                    img[yy][x] = (0, 0, 0, 255)
                elif x == 1 or y == 1:
                    img[yy][x] = light
                elif x == w - 2 or y == h - 2:
                    img[yy][x] = dark
                else:
                    c = shade(fill, rnd.randint(-5, 5))
                    if (x + y * 3) % 7 == 0:
                        c = shade(c, -8)
                    img[yy][x] = c
    png(os.path.join(OUT, "button.png"), w, h * 3, img)


def ribbon():
    w, h = 64, 16
    img = canvas(w, h)
    for y in range(h):
        for x in range(w):
            notch = abs(y - h // 2) // 2
            if x < 5 - notch or x > w - 6 + notch:
                continue
            if y == 0 or y == h - 1 or x == 5 - notch or x == w - 6 + notch:
                img[y][x] = (40, 40, 40, 255)
                continue
            v = 220 - (y * 6) + ((x * 5 + y) % 9) - 4
            if y == 1:
                v = 245
            if y == h - 2:
                v = 150
            img[y][x] = (v, v, v, 255)
    for x in range(8, w - 8, 4):
        img[3][x] = (255, 255, 255, 255)
    png(os.path.join(OUT, "ribbon.png"), w, h, img)


def bar():
    w, h = 64, 8
    img = canvas(w, h)
    for y in range(h):
        for x in range(w):
            if x == 0 or y == 0 or x == w - 1 or y == h - 1:
                img[y][x] = (20, 13, 8, 255)
            elif y == 1:
                img[y][x] = (40, 34, 30, 255)
            else:
                img[y][x] = (28, 24, 22, 255)
    png(os.path.join(OUT, "bar.png"), w, h, img)


def slot():
    w = h = 18
    img = canvas(w, h)
    for y in range(h):
        for x in range(w):
            if x == 0 or y == 0:
                img[y][x] = (55, 55, 55, 255)
            elif x == w - 1 or y == h - 1:
                img[y][x] = (255, 255, 255, 255)
            else:
                img[y][x] = (139, 139, 139, 255)
    png(os.path.join(OUT, "slot.png"), w, h, img)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    panel_dark()
    panel_parchment()
    button()
    ribbon()
    bar()
    slot()
    print("wrote", sorted(os.listdir(OUT)))
