#!/usr/bin/env python3
"""Recolor the ZeroWallet launcher artwork into the Deep Navy theme.

Source: docs/icon.png (1024x1024 white line-art wallet on black).
Output: adaptive foreground + legacy square/round launcher PNGs at every density.

Wallet outline -> #F1F5FF (ice white)
Lightning bolt -> #60A5FA (accent bright blue)
Background     -> #060D24 (deep navy); transparent on the adaptive foreground.
"""

import os
from collections import deque

import numpy as np
from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
MASTER = os.path.join(ROOT, "docs", "icon.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")

NAVY = (0x06, 0x0D, 0x24)
ICE = (0xF1, 0xF5, 0xFF)
BOLT = (0x60, 0xA5, 0xFA)

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

# Geometry measured from the previous artwork so the size and placement don't jump.
FG_ART_WIDTH = 152 / 432      # adaptive foreground: artwork width / canvas
FG_CENTER = (218 / 432, 209.5 / 432)
LEGACY_ART_WIDTH = 113 / 192  # legacy square icon
LEGACY_CENTER = (97.5 / 192, 91 / 192)
ROUND_ART_WIDTH = 0.53        # inset a little so the wallet sits inside the circle
ROUND_CENTER = (0.5, 0.5)


def components(mask):
    """8-connected components of a boolean mask, returned as list of index arrays."""
    h, w = mask.shape
    seen = np.zeros_like(mask, dtype=bool)
    out = []
    for y0, x0 in zip(*np.nonzero(mask)):
        if seen[y0, x0]:
            continue
        q = deque([(y0, x0)])
        seen[y0, x0] = True
        pix = []
        while q:
            y, x = q.popleft()
            pix.append((y, x))
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    ny, nx = y + dy, x + dx
                    if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        q.append((ny, nx))
        ys = np.array([p[0] for p in pix])
        xs = np.array([p[1] for p in pix])
        out.append((ys, xs))
    return out


def dilate(mask, rounds=2):
    m = mask.copy()
    for _ in range(rounds):
        p = np.pad(m, 1, mode="constant", constant_values=False)
        m = (
            p[1:-1, 1:-1] | p[:-2, 1:-1] | p[2:, 1:-1] | p[1:-1, :-2] | p[1:-1, 2:]
            | p[:-2, :-2] | p[:-2, 2:] | p[2:, :-2] | p[2:, 2:]
        )
    return m


def build_master():
    lum = np.asarray(Image.open(MASTER).convert("L")).astype(np.float32) / 255.0
    alpha = np.clip(lum, 0.0, 1.0)
    solid = alpha > 0.5
    comps = components(solid)
    comps.sort(key=lambda c: len(c[0]), reverse=True)

    # Largest bbox and area is the wallet outline; the next solid blob in the middle is the bolt.
    bolt_idx = None
    for i, (ys, xs) in enumerate(comps):
        cx = xs.mean() / alpha.shape[1]
        cy = ys.mean() / alpha.shape[0]
        area = len(ys)
        bbox_w = xs.max() - xs.min() + 1
        bbox_h = ys.max() - ys.min() + 1
        fill = area / float(bbox_w * bbox_h)
        if i > 0 and 0.30 < cx < 0.60 and 0.30 < cy < 0.65 and fill > 0.25 and area > 2000:
            bolt_idx = i
            break
    if bolt_idx is None:
        raise SystemExit("could not locate the lightning bolt component")

    bolt_mask = np.zeros(solid.shape, dtype=bool)
    ys, xs = comps[bolt_idx]
    bolt_mask[ys, xs] = True
    bolt_mask = dilate(bolt_mask, 2)

    print("components (area, bbox-fill, center):")
    for i, (ys, xs) in enumerate(comps):
        bbox = (xs.max() - xs.min() + 1) * (ys.max() - ys.min() + 1)
        print(
            "  %d area=%6d fill=%.2f center=(%.2f,%.2f)%s"
            % (i, len(ys), len(ys) / float(bbox), xs.mean() / 1024, ys.mean() / 1024,
               "  <- bolt" if i == bolt_idx else "")
        )

    rgb = np.empty((*solid.shape, 3), dtype=np.float32)
    rgb[...] = np.array(ICE, dtype=np.float32) / 255.0
    rgb[bolt_mask] = np.array(BOLT, dtype=np.float32) / 255.0

    rgba = np.dstack([rgb, alpha])
    art = Image.fromarray((rgba * 255.0 + 0.5).astype(np.uint8), "RGBA")

    solid_mask = alpha > 0.02
    ys, xs = np.nonzero(solid_mask)
    bbox = (xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)
    return art.crop(bbox), bolt_mask


def resize_rgba(art, width):
    ratio = width / art.width
    size = (max(1, int(round(art.width * ratio))), max(1, int(round(art.height * ratio))))
    a = np.asarray(art).astype(np.float32) / 255.0
    premul_arr = np.dstack([a[:, :, :3] * a[:, :, 3:4], a[:, :, 3:4]])
    p = Image.fromarray((premul_arr * 255.0 + 0.5).astype(np.uint8), "RGBA").resize(size, Image.LANCZOS)
    pa = np.asarray(p).astype(np.float32) / 255.0
    out_a = pa[:, :, 3:4]
    rgb = np.where(out_a > 1e-6, pa[:, :, :3] / np.maximum(out_a, 1e-6), 0.0)
    rgb = np.clip(rgb, 0.0, 1.0)
    out = np.dstack([rgb, out_a])
    return Image.fromarray((out * 255.0 + 0.5).astype(np.uint8), "RGBA")


def paste_centered(canvas, art, center_px, width_px):
    scaled = resize_rgba(art, int(round(width_px)))
    x = int(round(center_px[0] - scaled.width / 2.0))
    y = int(round(center_px[1] - scaled.height / 2.0))
    canvas.alpha_composite(scaled, (x, y))
    return canvas


def circle_mask(size):
    ss = 4
    big = Image.new("L", (size * ss, size * ss), 0)
    from PIL import ImageDraw

    d = ImageDraw.Draw(big)
    d.ellipse((0, 0, size * ss - 1, size * ss - 1), fill=255)
    return big.resize((size, size), Image.LANCZOS)


def main():
    art, _ = build_master()
    print("art bbox size:", art.size)

    for name, scale in DENSITIES.items():
        folder = os.path.join(RES, "mipmap-" + name)

        # Adaptive foreground: transparent background, art inside the 66dp safe zone.
        fg_size = int(round(108 * scale))
        fg = Image.new("RGBA", (fg_size, fg_size), (0, 0, 0, 0))
        paste_centered(
            fg, art,
            (FG_CENTER[0] * fg_size, FG_CENTER[1] * fg_size),
            FG_ART_WIDTH * fg_size,
        )
        fg.save(os.path.join(folder, "ic_launcher_foreground.png"), optimize=True)

        # Legacy square icon: navy background, rounded-square-free full bleed.
        sq = int(round(48 * scale))
        icon = Image.new("RGBA", (sq, sq), NAVY + (255,))
        paste_centered(
            icon, art,
            (LEGACY_CENTER[0] * sq, LEGACY_CENTER[1] * sq),
            LEGACY_ART_WIDTH * sq,
        )
        icon.convert("RGB").save(os.path.join(folder, "ic_launcher.png"), optimize=True)

        # Legacy round icon: round navy plate with transparent corners.
        rnd = Image.new("RGBA", (sq, sq), (0, 0, 0, 0))
        plate = Image.new("RGBA", (sq, sq), NAVY + (255,))
        rnd.paste(plate, (0, 0), circle_mask(sq))
        paste_centered(
            rnd, art,
            (ROUND_CENTER[0] * sq, ROUND_CENTER[1] * sq),
            ROUND_ART_WIDTH * sq,
        )
        rnd.save(os.path.join(folder, "ic_launcher_round.png"), optimize=True)
        print("wrote", name, "fg=%d sq=%d" % (fg_size, sq))

    # README artwork: same art on a navy square, 1024px.
    doc = Image.new("RGBA", (1024, 1024), NAVY + (255,))
    paste_centered(doc, art, (512, 512), 600)
    doc.convert("RGB").save(os.path.join(ROOT, "docs", "icon.png"), optimize=True)
    print("wrote docs/icon.png")


if __name__ == "__main__":
    main()
