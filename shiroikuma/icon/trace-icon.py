#!/usr/bin/env python3
"""Trace the 白い熊 読書 launcher icon from upstream's Librera PRO artwork.

Source: app/src/main/res/mipmap-xxxhdpi/adaptive_pdf_reader.png — the book of the Librera PRO
adaptive icon (192 px, the cleanest copy upstream ships). Nothing is drawn freehand: every shape
comes out of that raster, re-drawn in the house black-yellow line-art style:

  - the book's silhouette becomes a yellow outline,
  - the bookmark ribbon becomes a yellow outline (what lies under it is cleared),
  - the dark marks on the pages — the gutter, the text lines — stay as yellow shapes,
  - Librera's "L" swash on the left page is replaced by 読 (Noto Serif CJK JP Black).

The combined mask is vectorised with potrace and written to shiroikuma/icon/doksho-icon.svg
(108×108 adaptive-icon viewport, black ground). shiroikuma/icon/gen-icons.sh renders everything
else from that SVG.

Usage: python3 shiroikuma/icon/trace-icon.py [--debug DIR]
"""
import argparse
import os
import re
import subprocess
import tempfile

import numpy as np
from PIL import Image
from scipy import ndimage as ndi

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app/src/main/res/mipmap-xxxhdpi/adaptive_pdf_reader.png")
OUT = os.path.join(ROOT, "shiroikuma/icon/doksho-icon.svg")

SCALE = 8                 # 192 px → 1536 px before tracing
STROKE = 0.030            # outline width, fraction of the canvas
YELLOW, BLACK = "#FFFF00", "#000000"
MARK = 0.56               # the mark's larger side, fraction of the 108-unit viewport (safe zone)


def disk(r):
    r = max(1, int(round(r)))
    y, x = np.ogrid[-r:r + 1, -r:r + 1]
    return x * x + y * y <= r * r


def largest(mask):
    lab, n = ndi.label(mask)
    if n == 0:
        return mask
    sizes = ndi.sum(mask, lab, range(1, n + 1))
    return lab == (1 + int(np.argmax(sizes)))


def drop_small(mask, min_px):
    lab, n = ndi.label(mask)
    if n == 0:
        return mask
    sizes = ndi.sum(mask, lab, range(1, n + 1))
    keep = np.zeros(n + 1, bool)
    keep[1:] = sizes >= min_px
    return keep[lab]


GLYPH = "読"
GLYPH_FONT = ("/usr/share/fonts/opentype/noto/NotoSerifCJK-Black.ttc", 0)   # Noto Serif CJK JP Black


def glyph_mask(shape, char, cx, cy, width):
    """The character as a boolean mask of `shape`, `width` px wide, centred on (cx, cy)."""
    from PIL import ImageDraw, ImageFont
    font = ImageFont.truetype(GLYPH_FONT[0], 1200, index=GLYPH_FONT[1])
    canvas = Image.new("L", (1600, 1600), 0)
    ImageDraw.Draw(canvas).text((200, 100), char, font=font, fill=255)
    canvas = canvas.crop(canvas.getbbox())
    k = width / canvas.width
    canvas = canvas.resize((max(1, round(canvas.width * k)), max(1, round(canvas.height * k))), Image.LANCZOS)
    out = np.zeros(shape, bool)
    x0, y0 = int(round(cx - canvas.width / 2)), int(round(cy - canvas.height / 2))
    out[y0:y0 + canvas.height, x0:x0 + canvas.width] = np.asarray(canvas) > 127
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--debug", help="write the intermediate masks as PNGs into this directory")
    args = ap.parse_args()

    im = Image.open(SRC).convert("RGBA")
    w, h = im.size
    im = im.resize((w * SCALE, h * SCALE), Image.LANCZOS)
    px = np.asarray(im).astype(np.float32) / 255.0
    r, g, b, a = px[..., 0], px[..., 1], px[..., 2], px[..., 3]
    size = im.size[0]
    sw = STROKE * size

    lum = 0.299 * r + 0.587 * g + 0.114 * b
    # alpha > 0.85 leaves out the page stack's soft drop shadow; the blur-and-threshold then
    # rounds off the anti-aliasing so the outline runs clean.
    sil = ndi.binary_fill_holes(largest(a > 0.85))
    # The page stack's rounded corners leave notches along the right edge: close them over.
    pad = SCALE * 12
    sil = ndi.binary_closing(np.pad(sil, pad), disk(SCALE * 10))[pad:-pad, pad:-pad]
    sil = ndi.gaussian_filter(sil.astype(np.float32), SCALE * 2) > 0.5

    ribbon = (a > 0.5) & (r > 0.55) & (g < 0.55) & (b < 0.45) & (r - g > 0.2)
    ribbon = ndi.binary_fill_holes(largest(ndi.binary_opening(ribbon, disk(SCALE))))

    inner = ndi.binary_erosion(sil, disk(sw * 1.4))
    detail = (lum < 0.80) & inner & ~ndi.binary_dilation(ribbon, disk(sw * 1.2))
    detail = ndi.binary_closing(detail, disk(SCALE * 0.5))
    detail = drop_small(detail, (SCALE * 2.5) ** 2)
    # Thicken every mark by the same amount: the swash's thin tail survives small sizes, and the
    # text lines approach the outline's weight.
    detail = ndi.binary_dilation(detail, disk(SCALE * 0.9))

    # The left page carried Librera's "L" swash; 白い熊 replaced it with 読 (2026-09-25). The
    # gutter is the tall, narrow mark; every mark left of it goes, and the glyph takes its place.
    lab, n = ndi.label(detail)
    sil_ys, sil_xs = np.nonzero(sil)
    sil_h = sil_ys.max() - sil_ys.min()
    gutter_x = None
    for i, sl in enumerate(ndi.find_objects(lab), 1):
        if sl[0].stop - sl[0].start > 0.6 * sil_h and sl[1].stop - sl[1].start < 0.05 * size:
            gutter_x, gutter_sl = sl[1].start, sl
    if gutter_x is None:
        raise SystemExit("no gutter found in the source artwork")
    cols = np.arange(detail.shape[1])[None, :]
    detail &= ~(cols < gutter_x - SCALE)

    page_left = sil_xs.min() + sw
    page_w = gutter_x - page_left
    page_top, page_bottom = gutter_sl[0].start, gutter_sl[0].stop
    detail |= glyph_mask(detail.shape, GLYPH, page_left + page_w / 2, (page_top + page_bottom) / 2,
                         page_w * 0.80)

    outline = sil & ~ndi.binary_erosion(sil, disk(sw))
    ribbon_line = ribbon & ~ndi.binary_erosion(ribbon, disk(sw * 0.8))
    art = outline | ribbon_line | detail

    if args.debug:
        os.makedirs(args.debug, exist_ok=True)
        for name, m in dict(sil=sil, ribbon=ribbon, detail=detail, art=art).items():
            Image.fromarray((m * 255).astype(np.uint8)).save(os.path.join(args.debug, f"{name}.png"))

    # potrace: black = foreground in a PBM.
    ys, xs = np.nonzero(art)
    x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
    crop = art[y0:y1, x0:x1]
    with tempfile.TemporaryDirectory() as tmp:
        pbm = os.path.join(tmp, "art.pbm")
        Image.fromarray(((~crop) * 255).astype(np.uint8)).convert("1").save(pbm)
        svg = subprocess.run(["potrace", "-s", "--flat", "-a", "1.0", "-O", "0.4", "-t", "8", "-o", "-", pbm],
                             check=True, capture_output=True, text=True).stdout

    tr = re.search(r'<g transform="([^"]+)"', svg).group(1)
    paths = re.findall(r'<path d="([^"]+)"', svg, re.S)
    cw, ch = crop.shape[1], crop.shape[0]
    k = 108 * MARK / max(cw, ch)
    ox, oy = (108 - cw * k) / 2, (108 - ch * k) / 2
    body = "\n".join(f'    <path d="{" ".join(p.split())}"/>' for p in paths)
    open(OUT, "w").write(f'''<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">
  <!-- 白い熊 読書 launcher icon: Librera PRO's book (upstream mipmap-xxxhdpi/adaptive_pdf_reader.png)
       traced as yellow line-art on black, the house black-yellow style, with 読 on the left page. Generated by
       shiroikuma/icon/trace-icon.py — edit that, not this file. -->
  <rect width="108" height="108" fill="{BLACK}"/>
  <g transform="translate({ox:.3f},{oy:.3f}) scale({k:.6f})">
   <g transform="{tr}" fill="{YELLOW}" fill-rule="evenodd">
{body}
   </g>
  </g>
</svg>
''')
    print(f">>> {OUT} ({len(paths)} paths, mark {cw}x{ch} px)")


if __name__ == "__main__":
    main()
