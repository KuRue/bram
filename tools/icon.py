"""Generate Bram's adaptive launcher icon vectors.

Run from anywhere with: python tools/icon.py

The selected mark is a five-by-four tactile dot matrix that resolves into a lowercase b. Keeping
the coordinates here makes the colour and themed variants mechanically identical and prevents
small hand-edits from changing the silhouette between Android launcher modes.
"""

from pathlib import Path


WIDTH = 108
HEIGHT = 108
X_POSITIONS = (38, 49, 60, 71)
Y_POSITIONS = (32, 43, 54, 65, 76)
COLOR_RADIUS = 4.25
MONO_RADIUS = 4.65

# Row-major cells in the four-column matrix. The active cells draw the b; the remaining cells are
# quiet registration points in the colour icon and disappear from Android's themed silhouette.
ACTIVE_CELLS = (
    (0, 0),
    (1, 0),
    (2, 0), (2, 1), (2, 2), (2, 3),
    (3, 0), (3, 3),
    (4, 0), (4, 1), (4, 2), (4, 3),
)
INACTIVE_CELLS = tuple(
    (row, column)
    for row in range(len(Y_POSITIONS))
    for column in range(len(X_POSITIONS))
    if (row, column) not in ACTIVE_CELLS
)


def circle_path(x: float, y: float, radius: float) -> str:
    diameter = radius * 2
    return (
        f"M{x - radius:.2f},{y:.2f} "
        f"a{radius:.2f},{radius:.2f} 0 1,0 {diameter:.2f},0 "
        f"a{radius:.2f},{radius:.2f} 0 1,0 -{diameter:.2f},0 Z"
    )


def cells_path(cells: tuple[tuple[int, int], ...], radius: float) -> str:
    return " ".join(
        circle_path(X_POSITIONS[column], Y_POSITIONS[row], radius)
        for row, column in cells
    )


def build_background() -> str:
    return '''<?xml version="1.0" encoding="utf-8"?>
<!-- Bram's graphite field. The dot matrix itself carries the texture, so the background stays
     quiet enough for the mark to survive small launcher sizes and Android's adaptive masks. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="18"
                android:startY="8"
                android:endX="92"
                android:endY="103">
                <item android:offset="0" android:color="#FF0E0E11" />
                <item android:offset="0.56" android:color="#FF19191F" />
                <item android:offset="1" android:color="#FF24242B" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="52"
                android:centerY="54"
                android:gradientRadius="47">
                <item android:offset="0" android:color="#12D9A47C" />
                <item android:offset="0.62" android:color="#06D9A47C" />
                <item android:offset="1" android:color="#00D9A47C" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''


def build_foreground() -> str:
    active = cells_path(ACTIVE_CELLS, COLOR_RADIUS)
    inactive = cells_path(INACTIVE_CELLS, COLOR_RADIUS * 0.82)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- A tactile dot matrix resolving into a lowercase b. Active copper cells form the mark; the
     smaller graphite cells preserve the full matrix without competing at launcher size. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="{inactive}"
        android:fillColor="#FF4A4A52"
        android:fillAlpha="0.52" />
    <path android:pathData="{active}">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="38"
                android:startY="32"
                android:endX="71"
                android:endY="76">
                <item android:offset="0" android:color="#FFF7D3B2" />
                <item android:offset="0.5" android:color="#FFD9A47C" />
                <item android:offset="1" android:color="#FFBB7B4E" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''


def build_monochrome() -> str:
    active = cells_path(ACTIVE_CELLS, MONO_RADIUS)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Android recolours this layer from the user's wallpaper palette. Only the active b silhouette
     remains; inactive registration points would become visual noise in a one-colour icon. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="{active}"
        android:fillColor="#FFFFFFFF" />
</vector>
'''


def main() -> None:
    drawable = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res" / "drawable"
    outputs = {
        "ic_launcher_background.xml": build_background(),
        "ic_launcher_foreground.xml": build_foreground(),
        "ic_launcher_monochrome.xml": build_monochrome(),
    }
    for name, content in outputs.items():
        (drawable / name).write_text(content, encoding="utf-8", newline="\n")
    print("wrote 3 launcher icon vectors")


if __name__ == "__main__":
    main()
