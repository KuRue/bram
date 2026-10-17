#!/usr/bin/env python3
"""Frames raw device screenshots as store-style cards in Bram's dark theme.

Palette and lattice follow docs/assets/bram-banner.svg (field gradient #07070A -> #1E1E25,
staggered white dot lattice fading out, copper #D9A47C accents, text #EDEAE5 / #B5AFA7).

    python3 tools/screenshots/compose.py RAW_DIR OUT_DIR --font-dir DIR

RAW_DIR holds the device captures named in SHOTS; OUT_DIR receives 1080x1920 PNGs, which go in
docs/assets/screenshots/. The headline font is Inter (SIL Open Font License,
https://github.com/rsms/inter); it is not committed, so download a release and point --font-dir
at its `extras/ttf` directory.

The current set was captured with `adb exec-out screencap -p` on a Galaxy S25 Ultra (1080x2340,
dark theme) running a release-signed build: a fresh install's setup guide, auto-configure and a
benchmark on LFM2.5-2.6B-Q4_0, and two chats. Crop boxes are in those device pixels and cut away
the status bar, so a recapture at another resolution needs new boxes. Keep captures real: no
edited model output or numbers.
"""
import argparse
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1080, 1920
COPPER = (217, 164, 124)
TEXT = (237, 234, 229)
MUTED = (181, 175, 167)
MARGIN = 90
SHOT_W = 900
RADIUS = 46

# name, raw file, crop box (x0, y0, x1, y1) in device pixels, overline, headline lines
SHOTS = [
    ("01-chat", "p07-reply-top.png", (0, 98, 1080, 2010), "CHAT",
     ["Local model", "conversation"]),
    ("02-setup", "p02-scan.png", (46, 150, 1034, 2246), "FIRST RUN",
     ["Device scan and", "model recommendation"]),
    ("03-tuning", "p04-top.png", (56, 114, 1024, 2226), "AUTO-CONFIGURE",
     ["Configuration", "measurements"]),
    ("04-approval", "p06-approval.png", (0, 98, 1080, 1430), "PERMISSIONS",
     ["Tool approval", "request"]),
    ("05-benchmark", "p09-bench.png", (0, 98, 1080, 1610), "BENCHMARKS",
     ["Prompt and decode", "throughput"]),
]


def field():
    img = Image.new("RGB", (W, H))
    top, mid, bot = (7, 7, 10), (20, 20, 25), (30, 30, 37)
    px = ImageDraw.Draw(img)
    for y in range(H):
        t = y / (H - 1)
        a, b, u = (top, mid, t * 2) if t < 0.5 else (mid, bot, (t - 0.5) * 2)
        px.line([(0, y), (W, y)], fill=tuple(round(a[i] + (b[i] - a[i]) * u) for i in range(3)))
    # Staggered lattice, fading from 20% to 3% like the banner's mask.
    dots = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(dots)
    sx, sy, r = 33, 66, 1.7
    for row, y in enumerate(range(0, H + sy, sy // 2)):
        alpha = round(255 * (0.20 - 0.17 * min(1.0, y / H)))
        offset = 0 if row % 2 == 0 else sx / 2
        x = offset
        while x <= W:
            d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 255, alpha))
            x += sx
    img.paste(dots, (0, 0), dots)
    return img


def rounded_mask(size, radius, bottom=True):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] - 1, size[1] + (0 if bottom else radius * 2)],
                                        radius=radius, fill=255)
    return m


def compose(raw, out_path, crop, overline, headline, fonts):
    img = field()
    draw = ImageDraw.Draw(img)

    y = 150
    tracking = 5
    x = MARGIN
    for ch in overline:
        draw.text((x, y), ch, font=fonts["over"], fill=COPPER)
        x += draw.textlength(ch, font=fonts["over"]) + tracking
    y += 70
    for line in headline:
        draw.text((MARGIN - 3, y), line, font=fonts["head"], fill=TEXT)
        y += 92
    y += 18
    draw.rounded_rectangle([MARGIN, y, MARGIN + 66, y + 5], radius=3, fill=COPPER)
    top = y + 70

    shot = raw.crop(crop)
    scale = SHOT_W / shot.width
    shot = shot.resize((SHOT_W, round(shot.height * scale)), Image.LANCZOS)
    room = H - top
    bleed = shot.height > room - 70
    if bleed:
        shot = shot.crop((0, 0, SHOT_W, room))
        pos = (MARGIN, top)
    else:
        pos = (MARGIN, top)
    mask = rounded_mask(shot.size, RADIUS, bottom=not bleed)

    # Soft copper glow and a dark drop shadow under the screen.
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gx0, gy0 = pos
    gd.rounded_rectangle([gx0 - 10, gy0 - 10, gx0 + SHOT_W + 10, gy0 + shot.height + 10],
                         radius=RADIUS + 10, fill=COPPER + (60,))
    glow = glow.filter(ImageFilter.GaussianBlur(42))
    img.paste(glow, (0, 0), glow)

    border = Image.new("RGBA", (shot.width + 4, shot.height + 4), (0, 0, 0, 0))
    ImageDraw.Draw(border).rounded_rectangle(
        [0, 0, border.width - 1, border.height - 1 + (0 if not bleed else RADIUS * 2)],
        radius=RADIUS + 2, fill=(74, 74, 82, 140))
    img.paste(border, (pos[0] - 2, pos[1] - 2), border)
    img.paste(shot, pos, mask)
    # 256 colours with dithering roughly halves the file with no visible banding on the field.
    img.quantize(colors=256, method=Image.Quantize.MEDIANCUT,
                 dither=Image.Dither.FLOYDSTEINBERG).save(out_path, optimize=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("raw_dir")
    ap.add_argument("out_dir")
    ap.add_argument("--font-dir", required=True, help="directory with Inter TTFs")
    args = ap.parse_args()
    fonts = {
        "over": ImageFont.truetype(os.path.join(args.font_dir, "Inter-SemiBold.ttf"), 30),
        "head": ImageFont.truetype(os.path.join(args.font_dir, "InterDisplay-SemiBold.ttf"), 80),
    }
    os.makedirs(args.out_dir, exist_ok=True)
    for name, raw_name, crop, overline, headline in SHOTS:
        raw = Image.open(os.path.join(args.raw_dir, raw_name)).convert("RGB")
        out = os.path.join(args.out_dir, f"{name}.png")
        compose(raw, out, crop, overline, headline, fonts)
        print(out, os.path.getsize(out) // 1024, "KB")


if __name__ == "__main__":
    main()
