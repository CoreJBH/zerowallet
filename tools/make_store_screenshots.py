#!/usr/bin/env python3
"""Rebuild the store screenshots from the last device capture, in USD only.

The capture is the wallet's home screen as the phone drew it. Two things in it
no longer match the app that ships 1.2.3:

* the fiat figures are in won (the wallet shows one fiat figure, in USD, since
  9a72e5b), and
* the on-chain row carried a round swap control that opened the channels sheet.
  That button was removed (`HomeScreen.kt`, BalanceRow's `trailing`): there is no
  swap in this wallet, and a control that looks like one teaches the wrong thing
  about both. The row's two figures therefore right-align to the card's content
  edge like the Lightning row above them.

So the script repaints the three fiat figures with the app's own typography
(Roboto at the sizes Compose uses: 30 sp bold for the total, 15 sp medium for the
rows, 1.875 px per sp) and erases the button. The satoshi figures, the payment
list and everything else are left as the phone drew them: the bitcoin mark
(U+20BF) is not in Roboto, so moving those pixels copies the glyph instead of
re-rendering it.

Every value painted here is an example. The capture was taken with demo amounts;
nothing in the output comes from a real wallet.

Usage:
    python3 tools/make_store_screenshots.py docs/preview.jpg \
        docs/preview.jpg fastlane/metadata/android/en-US/images/phoneScreenshots/1.png

The first argument is the capture to paint over, the second is the README
preview (838x1776), the third the store screenshot (the same image with the card's
own background colour padded 25 px on each side, which is what the store listing
has always used).
"""

import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONTS = "/workspace/work/zerowallet-preview/fonts"

# ui/theme/Theme.kt
BG = (7, 13, 37)             # Background
CARD = (10, 21, 53)          # Surface
PRIMARY = (241, 245, 255)    # TextPrimary

PX_PER_SP = 1.875            # measured from the capture
SS = 4                       # supersampling factor for text

# Example balances: 1,750,000 sat = 0.0175 BTC, priced at $91,200/BTC.
#   0.0175  * 91,200 = 1,596
#   0.005   * 91,200 =   456   (Lightning)
#   0.0125  * 91,200 = 1,140   (on-chain)
TOTAL_USD = "$1,596"
LIGHTNING_USD = "$456"
ONCHAIN_USD = "$1,140"

# Where the figures sit in the 838x1776 preview: (digit ink top, ink right edge,
# erase box, weight).
TOTAL = dict(text=TOTAL_USD, weight="Bold", px=30 * PX_PER_SP, top=176,
             right=754, clear=(452, 168, 762, 234))
LIGHTNING = dict(text=LIGHTNING_USD, weight="Medium", px=15 * PX_PER_SP, top=336,
                 right=755, clear=(628, 330, 762, 366))
ONCHAIN = dict(text=ONCHAIN_USD, weight="Medium", px=15 * PX_PER_SP, top=465,
               right=755, clear=(505, 458, 662, 496))

# The removed control: the elevated circle clipped at the card's right edge,
# x 683..794, y 462..537. The border line at x 795 and beyond is left alone.
SWAP_CLEAR = (679, 455, 794, 543)

# The on-chain satoshi figure moves right with the column, from ink right 651 to
# the content edge the Lightning row uses.
SATS_BOX = (538, 508, 654, 544)
SATS_SHIFT = LIGHTNING["right"] - 651


def render(text, weight, px, color):
    """Draw text with Roboto at the app's size, cropped to its ink box."""
    font = ImageFont.truetype(f"{FONTS}/Roboto-{weight}.ttf", int(round(px * SS)))
    canvas = Image.new("RGBA", (int(px * SS * len(text) * 1.2) + 40, int(px * SS * 3)),
                       (0, 0, 0, 0))
    ImageDraw.Draw(canvas).text((10, 10), text, font=font, fill=color + (255,))
    canvas = canvas.resize((max(1, round(canvas.width / SS)),
                            max(1, round(canvas.height / SS))), Image.LANCZOS)
    return canvas


def ink_box(img, threshold=10):
    a = np.asarray(img)
    ys, xs = np.nonzero(a[..., 3] > threshold)
    return int(ys.min()), int(ys.max()), int(xs.min()), int(xs.max())


def place(img, spec):
    """Paste a figure so its digits sit on the capture's own baseline."""
    whole = render(spec["text"], spec["weight"], spec["px"], PRIMARY)
    digits = render(spec["text"].lstrip("$"), spec["weight"], spec["px"], PRIMARY)
    wy0, _, wx0, wx1 = ink_box(whole)
    dy0, _, _, _ = ink_box(digits)
    # The currency sign rises above the digits and dips below the baseline; the
    # digits are what has to line up with the capture.
    y = spec["top"] - (dy0 - wy0)
    x = spec["right"] - (wx1 - wx0)
    img.alpha_composite(whole, (x, y))


def main(src_path, preview_path, store_path):
    src = Image.open(src_path).convert("RGBA")
    if src.size != (838, 1776):
        sys.exit(f"expected an 838x1776 capture, got {src.size[0]}x{src.size[1]}")

    out = src.copy()
    draw = ImageDraw.Draw(out)

    # 1. The removed control: card background, nothing else. The card's own
    #    border runs down x 792..794 there, so those columns take their colours
    #    from a row above the button instead of the fill.
    draw.rectangle([SWAP_CLEAR[0], SWAP_CLEAR[1], SWAP_CLEAR[2], SWAP_CLEAR[3]],
                   fill=CARD + (255,))
    border = np.asarray(src.crop((792, SWAP_CLEAR[1] - 8, 795, SWAP_CLEAR[1] - 7)))
    out_arr = np.asarray(out).copy()
    out_arr[SWAP_CLEAR[1]:SWAP_CLEAR[3] + 1, 792:795] = border[0]
    out = Image.fromarray(out_arr)
    draw = ImageDraw.Draw(out)

    # 2. The on-chain satoshi figure keeps its pixels, at the content edge.
    sats = src.crop(SATS_BOX)
    draw.rectangle([SATS_BOX[0] - 2, SATS_BOX[1] - 2,
                    SATS_BOX[2] + 2, SATS_BOX[3] + 2], fill=CARD + (255,))
    out.alpha_composite(sats, (SATS_BOX[0] + SATS_SHIFT, SATS_BOX[1]))

    # 3. The three fiat figures.
    for spec in (TOTAL, LIGHTNING, ONCHAIN):
        draw.rectangle([spec["clear"][0], spec["clear"][1],
                        spec["clear"][2], spec["clear"][3]], fill=CARD + (255,))
        place(out, spec)

    preview = out.convert("RGB")
    preview.save(preview_path, quality=92, subsampling=0)

    store = Image.new("RGB", (preview.width + 50, preview.height), BG)
    store.paste(preview, (25, 0))
    store.save(store_path)

    print(f"wrote {preview_path} {preview.size} and {store_path} {store.size}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
