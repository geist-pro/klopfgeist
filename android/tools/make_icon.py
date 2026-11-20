#!/usr/bin/env python3
"""Android launcher icon from the Apple icon layers (apple/App/AppIcon.icon/Assets).

    python3 android/tools/make_icon.py

Writes the adaptive icon's background, foreground and monochrome layer and the legacy bitmap
into app/src/main/res. The artwork is drawn at 90 % so the headphones stay inside the
adaptive icon's safe zone (the middle 66 of 108 dp). Needs Pillow and numpy.
"""
import pathlib
import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[2]
ART = ROOT / "apple/App/AppIcon.icon/Assets"
RES = ROOT / "android/app/src/main/res"
NAVY = (18, 27, 48, 255)
SCALE = 0.9
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def layer(name):
    art = Image.open(ART / name).convert("RGBA")
    size = round(1024 * SCALE)
    canvas = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    canvas.alpha_composite(art.resize((size, size), Image.LANCZOS), ((1024 - size) // 2,) * 2)
    return canvas


back, ghost, phones = layer("ghost-back.png"), layer("ghost-mid.png"), layer("ghost-front.png")
background = Image.new("RGBA", (1024, 1024), NAVY)
background.alpha_composite(back)
foreground = Image.alpha_composite(ghost, phones)

# Themed icon: the system tints the alpha channel. The eyes (the only coloured part of the ghost) are cut out.
g = np.asarray(ghost).astype(int)
body = (g[..., 3] > 0) & (g[..., 2] - g[..., 0] < 40)
alpha = np.where(body, g[..., 3], 0) | np.asarray(phones)[..., 3]
mono = np.zeros((1024, 1024, 4), np.uint8)
mono[..., :3], mono[..., 3] = 255, alpha
monochrome = Image.fromarray(mono, "RGBA")

# Legacy bitmap (no launcher on minSdk 33 uses it; kept so the manifest icon always resolves).
legacy = Image.alpha_composite(background, foreground).crop((170, 170, 854, 854))

for name, factor in DENSITIES.items():
    folder = RES / f"mipmap-{name}"
    folder.mkdir(exist_ok=True)
    side = round(108 * factor)
    background.resize((side, side), Image.LANCZOS).convert("RGB").save(folder / "ic_launcher_background.png")
    foreground.resize((side, side), Image.LANCZOS).save(folder / "ic_launcher_foreground.png")
    monochrome.resize((side, side), Image.LANCZOS).save(folder / "ic_launcher_monochrome.png")
    legacy.resize((round(48 * factor),) * 2, Image.LANCZOS).convert("RGB").save(folder / "ic_launcher.png")
print("icon layers written to", RES)
