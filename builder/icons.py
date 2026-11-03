"""Generates adaptive launcher icons (background + foreground layers) for every density."""

import colorsys
import hashlib
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps, ImageStat

# Adaptive icon layers are 108dp; launchers show roughly the middle 72dp.
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
LAYER_DP = 108
ART_DP = 72
MASTER = 432  # xxxhdpi layer size; smaller densities are downscaled from it

ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""

FONT_CANDIDATES = [
    "/System/Library/Fonts/Supplemental/Arial Rounded Bold.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/Library/Fonts/Arial Bold.ttf",
    "C:/Windows/Fonts/arialbd.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]


def _font(size: int) -> ImageFont.ImageFont:
    for path in FONT_CANDIDATES:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default(size=size)


def _layers_from_image(src: Path) -> tuple[Image.Image, Image.Image]:
    art = Image.open(src).convert("RGBA")
    # Background: the art itself, cover-cropped and heavily blurred, so the
    # launcher's mask never shows a hard edge whatever shape it uses.
    bg = ImageOps.fit(art.convert("RGB"), (MASTER, MASTER), Image.LANCZOS)
    bg = bg.filter(ImageFilter.GaussianBlur(MASTER // 12))
    # Foreground: the art fitted into the visible safe zone, aspect preserved.
    box = MASTER * ART_DP // LAYER_DP
    fitted = ImageOps.contain(art, (box, box), Image.LANCZOS)
    fg = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    mask = Image.new("L", fitted.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, *fitted.size), radius=MASTER // 24, fill=255)
    alpha = Image.composite(fitted.getchannel("A"), mask, mask)
    fitted.putalpha(alpha)
    fg.paste(fitted, ((MASTER - fitted.width) // 2, (MASTER - fitted.height) // 2), fitted)
    return bg, fg


def _wrap(text: str, font: ImageFont.ImageFont, width: int, draw: ImageDraw.ImageDraw) -> list[str]:
    lines, line = [], ""
    for word in text.split():
        trial = f"{line} {word}".strip()
        if not line or draw.textlength(trial, font=font) <= width:
            line = trial
        else:
            lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines


def _placeholder_layers(title: str) -> tuple[Image.Image, Image.Image]:
    # Stable colour per title so the same game always gets the same icon.
    hue = int(hashlib.sha1(title.encode()).hexdigest()[:4], 16) / 0xFFFF
    r, g, b = (int(c * 255) for c in colorsys.hsv_to_rgb(hue, 0.55, 0.55))
    bg = Image.new("RGB", (MASTER, MASTER), (r, g, b))

    fg = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    draw = ImageDraw.Draw(fg)
    safe = MASTER * 62 // LAYER_DP  # stay inside the 66dp circle-safe area
    for size in range(MASTER // 6, 12, -2):
        font = _font(size)
        lines = _wrap(title, font, safe, draw)
        line_h = size * 1.15
        widest = max(draw.textlength(l, font=font) for l in lines)
        if len(lines) <= 4 and widest <= safe and line_h * len(lines) <= safe:
            break
    y = (MASTER - line_h * len(lines)) / 2
    for line in lines:
        draw.text((MASTER / 2, y + line_h / 2), line, font=font, fill="white", anchor="mm")
        y += line_h
    return bg, fg


def write_icons(res_dir: Path, title: str, icon: Path | None) -> None:
    bg, fg = _layers_from_image(icon) if icon else _placeholder_layers(title)
    for density, scale in DENSITIES.items():
        size = round(LAYER_DP * scale)
        out = res_dir / f"mipmap-{density}"
        out.mkdir(parents=True, exist_ok=True)
        bg.resize((size, size), Image.LANCZOS).save(out / "ic_launcher_background.png", optimize=True)
        fg.resize((size, size), Image.LANCZOS).save(out / "ic_launcher_foreground.png", optimize=True)
    anydpi = res_dir / "mipmap-anydpi"
    anydpi.mkdir(parents=True, exist_ok=True)
    (anydpi / "ic_launcher.xml").write_text(ADAPTIVE_XML)
