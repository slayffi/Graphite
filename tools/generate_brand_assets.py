#!/usr/bin/env python3
"""Generates the Graphite crystal icons from one geometry (kept in sync with CrystalGeometry.kt).

Outputs:
  platform/android/src/main/res/...           adaptive launcher icon (+ monochrome), notification icon
  platform/desktop/icons/graphite.ico         Windows icon (16..256 px)
  platform/desktop/icons/graphite.png         512 px PNG (Linux / README)
  ui/designsystem/src/commonMain/composeResources/drawable/app_icon.xml   window / tray icon

Requires Pillow. Run from the repository root: python3 tools/generate_brand_assets.py
"""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent

P = {
    "top": (50, 6), "ul": (24, 30), "um": (50, 40), "ur": (76, 30),
    "ll": (24, 70), "lm": (50, 80), "lr": (76, 70), "bottom": (50, 96),
}
FACETS = [
    (["top", "ul", "um"], "#5C5C61"),
    (["top", "um", "ur"], "#3A3A3F"),
    (["ul", "ll", "lm", "um"], "#34343A"),
    (["um", "lm", "lr", "ur"], "#1E1E22"),
    (["ll", "bottom", "lm"], "#26262A"),
    (["lm", "bottom", "lr"], "#141416"),
]
EDGES = [
    ("top", "ul"), ("top", "ur"), ("top", "um"), ("ul", "um"), ("um", "ur"),
    ("ul", "ll"), ("um", "lm"), ("ur", "lr"), ("ll", "lm"), ("lm", "lr"),
    ("ll", "bottom"), ("lm", "bottom"), ("lr", "bottom"),
]
EDGE_COLOR = "#C8C8CC"
BG = "#000000"


def fmt(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def vector_paths(scale, dx, dy, edge_width, mono=False):
    def pt(name):
        x, y = P[name]
        return dx + x * scale, dy + y * scale

    out = []
    for names, color in FACETS:
        d = "M" + " L".join(f"{fmt(pt(n)[0])},{fmt(pt(n)[1])}" for n in names) + " Z"
        out.append(f'    <path android:fillColor="{"#FFFFFFFF" if mono else color}" android:pathData="{d}" />')
    if not mono:
        d = " ".join(f"M{fmt(pt(a)[0])},{fmt(pt(a)[1])} L{fmt(pt(b)[0])},{fmt(pt(b)[1])}" for a, b in EDGES)
        out.append(
            f'    <path android:strokeColor="{EDGE_COLOR}" android:strokeAlpha="0.8" android:strokeWidth="{fmt(edge_width)}" '
            f'android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{d}" />'
        )
    return "\n".join(out)


def write(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def android():
    res = ROOT / "platform/android/src/main/res"
    # Adaptive icon: 108dp canvas, keep the crystal inside the 66dp safe zone.
    scale, dx, dy = 0.62, 54 - 50 * 0.62, 54 - 51 * 0.62
    write(res / "drawable/ic_launcher_foreground.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
{vector_paths(scale, dx, dy, 0.9)}
</vector>
""")
    write(res / "drawable/ic_launcher_monochrome.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
{vector_paths(scale, dx, dy, 0.9, mono=True)}
</vector>
""")
    write(res / "drawable/ic_launcher_background.xml", """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#FF000000" />
</shape>
""")
    adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""
    write(res / "mipmap-anydpi-v26/ic_launcher.xml", adaptive)
    write(res / "mipmap-anydpi-v26/ic_launcher_round.xml", adaptive)
    # Status bar icon: white silhouette, 24dp.
    s, ox, oy = 0.22, 12 - 50 * 0.22, 12 - 51 * 0.22
    write(res / "drawable/ic_stat_graphite.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
{vector_paths(s, ox, oy, 0.3, mono=True)}
</vector>
""")


def compose_resource():
    # 100x100 viewport with a black rounded square behind the crystal (window and tray icon).
    scale, dx, dy = 0.78, 50 - 50 * 0.78, 50 - 51 * 0.78
    write(ROOT / "ui/designsystem/src/commonMain/composeResources/drawable/app_icon.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="64dp" android:height="64dp" android:viewportWidth="100" android:viewportHeight="100">
    <path android:fillColor="#FF000000" android:pathData="M18,0 H82 A18,18 0 0 1 100,18 V82 A18,18 0 0 1 82,100 H18 A18,18 0 0 1 0,82 V18 A18,18 0 0 1 18,0 Z" />
{vector_paths(scale, dx, dy, 1.4)}
</vector>
""")


def raster(size: int) -> Image.Image:
    ss = 4
    big = size * ss
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    r = int(big * 0.18)
    d.rounded_rectangle([0, 0, big - 1, big - 1], radius=r, fill=BG)
    scale = big * 0.78 / 100
    off_x = big / 2 - 50 * scale
    off_y = big / 2 - 51 * scale

    def pt(n):
        x, y = P[n]
        return off_x + x * scale, off_y + y * scale

    for names, color in FACETS:
        d.polygon([pt(n) for n in names], fill=color)
    width = max(1, int(big * (0.007 if size >= 64 else 0.012 if size >= 32 else 0.02)))
    for a, b in EDGES:
        d.line([pt(a), pt(b)], fill=(200, 200, 204, 210), width=width)
    return img.resize((size, size), Image.LANCZOS)


def desktop():
    icons = ROOT / "platform/desktop/icons"
    icons.mkdir(parents=True, exist_ok=True)
    raster(512).save(icons / "graphite.png")
    sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
    base = raster(256)
    base.save(icons / "graphite.ico", sizes=[(s, s) for s in sizes], append_images=[raster(s) for s in sizes if s != 256])


if __name__ == "__main__":
    android()
    compose_resource()
    desktop()
    print("brand assets generated")
