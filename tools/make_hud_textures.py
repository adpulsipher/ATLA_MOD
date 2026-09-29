#!/usr/bin/env python3
"""Generates atla_core's HUD textures in the KyoshiCraft resource-pack style.

Colours for the slot frame are sampled from the pack's gui/widgets.png hotbar
(outline #15120e, slot fill #2c231a, inner shadow #2d2d2d, highlight #e9e9e9,
rivets #796b5f). Element icons are 16x16 with a dark hue-matched outline and
three-tone shading, like the pack's item textures.

Usage: python3 tools/make_hud_textures.py   (needs Pillow)
"""
import math
import os

from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), '..', 'atla-core', 'src', 'main', 'resources',
                   'assets', 'atla_core', 'textures', 'gui')


def hexc(s, a=255):
    s = s.lstrip('#')
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


OUTLINE = hexc('15120e')
FILL = hexc('2c231a')
SHADOW = hexc('2d2d2d')
HIGHLIGHT = hexc('e9e9e9')
JUNCTION = hexc('7c7c7c')
RIVET = hexc('796b5f')


def slot_frame(img, ox, oy):
    """22x22 slot drawn exactly like one cell of the pack's hotbar."""
    for y in range(22):
        for x in range(22):
            c = None
            if 1 <= x <= 20 and 1 <= y <= 20:
                if x in (1, 20) or y in (1, 20):
                    c = OUTLINE
                elif x == 19 and y == 2:
                    c = JUNCTION
                elif x == 19 or y == 19:
                    c = HIGHLIGHT
                elif x == 2 or y == 2:
                    c = SHADOW
                else:
                    c = FILL
            if c:
                img.putpixel((ox + x, oy + y), c)
    # riveted corners (4x4: dark ring, 2x2 light centre)
    for cx, cy in ((0, 0), (18, 0), (0, 18), (18, 18)):
        for y in range(4):
            for x in range(4):
                inner = 1 <= x <= 2 and 1 <= y <= 2
                img.putpixel((ox + cx + x, oy + cy + y), RIVET if inner else OUTLINE)


def icon(mask_fn, shade_fn, outline, extra=None):
    """Rasterise a 16x16 icon: mask -> shaded fill -> 1px outline around it."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    filled = [[False] * 16 for _ in range(16)]
    for y in range(16):
        for x in range(16):
            px, py = x + 0.5, y + 0.5
            if mask_fn(px, py):
                filled[y][x] = True
                img.putpixel((x, y), shade_fn(px, py))
    for y in range(16):
        for x in range(16):
            if filled[y][x]:
                continue
            if any(0 <= x + dx < 16 and 0 <= y + dy < 16 and filled[y + dy][x + dx]
                   for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                img.putpixel((x, y), outline)
    if extra:
        extra(img, filled)
    return img


def dist_to_polyline(px, py, pts):
    best = 1e9
    for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
        dx, dy = x2 - x1, y2 - y1
        t = max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy or 1)))
        best = min(best, math.hypot(px - (x1 + t * dx), py - (y1 + t * dy)))
    return best


# ---------------------------------------------------------------- air: Air Nomad swirl
AIR_L, AIR_M, AIR_D, AIR_O = hexc('fffbe8'), hexc('eadcb0'), hexc('bfa877'), hexc('3b3020')
spiral = []
for i in range(200):
    th = i / 199 * 2.6 * math.pi
    r = 6.0 - th * 0.62
    spiral.append((8 + r * math.cos(th - math.pi / 2), 8 + r * math.sin(th - math.pi / 2)))


def air_mask(px, py):
    return dist_to_polyline(px, py, spiral) < 1.05


def air_shade(px, py):
    v = (px + py) / 2
    return AIR_L if v < 6.5 else AIR_M if v < 10 else AIR_D


# ---------------------------------------------------------------- water: droplet
W_L, W_M, W_D, W_O, W_S = hexc('d6f0ff'), hexc('58aef0'), hexc('2c6ec4'), hexc('10224a'), hexc('ffffff')


def water_mask(px, py):
    cx, cy, r = 8.0, 10.0, 4.6
    if math.hypot(px - cx, py - cy) <= r:
        return True
    # pointed top: triangle from (8,1.2) down to the circle's tangents
    if 1.2 <= py <= cy:
        half = (py - 1.2) / (cy - 1.2) * r * 0.98
        return abs(px - cx) <= half
    return False


def water_shade(px, py):
    d = (px - 6.5) * 0.7 + (py - 8) * 0.7
    return W_L if d < -1.2 else W_M if d < 2.2 else W_D


def water_extra(img, filled):
    for x, y in ((6, 8), (6, 9), (7, 7)):
        img.putpixel((x, y), W_S)


# ---------------------------------------------------------------- earth: Earth Kingdom emblem (square in a ring)
E_L, E_M, E_D, E_O = hexc('a8d86a'), hexc('5e9c3a'), hexc('365f20'), hexc('14230b')


def earth_mask(px, py):
    d = math.hypot(px - 8, py - 8)
    ring = 4.6 <= d <= 6.9
    square = abs(px - 8) <= 2.1 and abs(py - 8) <= 2.1
    return ring or square


def earth_shade(px, py):
    v = (px - 8) * 0.6 + (py - 8) * 0.8
    return E_L if v < -2.5 else E_M if v < 2.5 else E_D


# ---------------------------------------------------------------- fire: flame with a hot core
F_Y, F_O, F_R, F_D, F_K = hexc('ffe27a'), hexc('ff9a2e'), hexc('e0482a'), hexc('8a1f12'), hexc('2a0c06')


def flame_width(py, top, base_y, max_w):
    if py < top or py > base_y + max_w * 0.9:
        return -1
    if py <= base_y:
        t = (py - top) / (base_y - top)
        return max_w * math.sin(t * math.pi / 2) ** 1.4
    return math.sqrt(max(0.0, (max_w * 0.9) ** 2 - (py - base_y) ** 2)) * max_w / (max_w * 0.9)


def fire_mask(px, py):
    sway = 0.9 * math.sin((py - 2) / 13 * math.pi) * (1 - py / 16)
    w = flame_width(py, 1.0, 10.0, 5.6)
    main = w >= 0 and abs(px - 8 - sway) <= w
    tongue = flame_width(py, 4.0, 10.0, 2.2)
    side = tongue >= 0 and abs(px - 3.6) <= tongue and py > 5.5
    tongue2 = flame_width(py, 5.5, 11.0, 1.8)
    side2 = tongue2 >= 0 and abs(px - 12.6) <= tongue2 and py > 6.5
    return main or side or side2


def fire_shade(px, py):
    core = flame_width(py, 6.0, 12.0, 2.6)
    if core >= 0 and abs(px - 8) <= core:
        return F_Y
    outer = flame_width(py, 3.5, 11.0, 4.2)
    if outer >= 0 and abs(px - 8) <= outer:
        return F_O
    return F_R if py < 12.5 else F_D


def main():
    os.makedirs(OUT, exist_ok=True)
    sheet = Image.new('RGBA', (128, 32), (0, 0, 0, 0))
    slot_frame(sheet, 0, 0)
    icons = [
        icon(air_mask, air_shade, AIR_O),
        icon(water_mask, water_shade, W_O, water_extra),
        icon(earth_mask, earth_shade, E_O),
        icon(fire_mask, fire_shade, F_K),
    ]
    for i, im in enumerate(icons):
        sheet.alpha_composite(im, (32 + 16 * i, 0))
    # level pips, styled like the hotbar's corner rivets so they read on dark and light backgrounds:
    # empty = rivet-coloured ring around a dark socket at (0,24); filled = solid white (tinted per element) at (4,24)
    for x in range(3):
        for y in range(3):
            centre = (x, y) == (1, 1)
            sheet.putpixel((x, 24 + y), FILL if centre else RIVET)
            sheet.putpixel((4 + x, 24 + y), hexc('ffffff'))
    sheet.save(os.path.join(OUT, 'element_hud.png'))

    # mod-list logo: the four icons on a slot background, 64x64
    logo = Image.new('RGBA', (22, 22), (0, 0, 0, 0))
    slot_frame(logo, 0, 0)
    logo = logo.resize((64, 64), Image.NEAREST)
    small = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    for i, im in enumerate(icons):
        small.alpha_composite(im, ((i % 2) * 16, (i // 2) * 16))
    logo.alpha_composite(small.resize((44, 44), Image.NEAREST), (10, 10))
    logo_dir = os.path.join(OUT, '..', '..', '..', '..')
    logo.save(os.path.join(logo_dir, 'atla_core_logo.png'))
    print('wrote', os.path.abspath(os.path.join(OUT, 'element_hud.png')))


if __name__ == '__main__':
    main()
