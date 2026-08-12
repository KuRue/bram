"""Generate Bram's adaptive launcher icon vectors.

Run from anywhere with: python tools/icon.py

The selected mark is a staggered tactile lattice that resolves into a lowercase b. Its alternating
half-step rows echo the field behind Bram's chat UI. Keeping the coordinates here makes the colour
and themed variants mechanically identical and prevents small hand-edits from changing the
silhouette between Android launcher modes.
"""

from pathlib import Path


WIDTH = 108
HEIGHT = 108
EVEN_X_POSITIONS = (41.0, 49.5, 58.0, 66.5)
ODD_X_POSITIONS = (36.75, 45.25, 53.75, 62.25, 70.75)
Y_POSITIONS = (31.0, 38.5, 46.0, 53.5, 61.0, 68.5, 76.0)
COLOR_RADIUS = 3.4
INACTIVE_RADIUS = 2.3
MONO_RADIUS = 3.65


def row_x_positions(row: int) -> tuple[float, ...]:
    return EVEN_X_POSITIONS if row % 2 == 0 else ODD_X_POSITIONS

# Row-major cells in alternating four- and five-column rows. Active cells draw the b; remaining
# cells are quiet registration points in the colour icon and disappear from the themed silhouette.
ACTIVE_CELLS = (
    (0, 0),
    (1, 1),
    (2, 0),
    (3, 1), (3, 2), (3, 3),
    (4, 0), (4, 3),
    (5, 1), (5, 3),
    (6, 0), (6, 1), (6, 2),
)
INACTIVE_CELLS = tuple(
    (row, column)
    for row in range(len(Y_POSITIONS))
    for column in range(len(row_x_positions(row)))
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
        circle_path(row_x_positions(row)[column], Y_POSITIONS[row], radius)
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
    inactive = cells_path(INACTIVE_CELLS, INACTIVE_RADIUS)
    # VectorDrawable has no blur primitive. Three translucent, round-capped bands create a quiet
    # feathered light field instead: a straight optical stem and a curve around the bowl.
    glow = "M43.12,31.00 L43.12,76.00 M43.12,53.50 C54.50,51.00 66.50,52.00 66.50,61.50 C66.50,71.00 56.50,75.00 43.12,75.00"
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- A staggered tactile lattice resolving into a lowercase b. A restrained light field optically
     straightens the stem and rounds the bowl without joining the individual copper cells. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="{glow}"
        android:fillColor="@android:color/transparent"
        android:strokeColor="#FFD9A47C"
        android:strokeAlpha="0.035"
        android:strokeWidth="14"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="{glow}"
        android:fillColor="@android:color/transparent"
        android:strokeColor="#FFE8AF80"
        android:strokeAlpha="0.055"
        android:strokeWidth="8"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="{glow}"
        android:fillColor="@android:color/transparent"
        android:strokeColor="#FFF0BD91"
        android:strokeAlpha="0.07"
        android:strokeWidth="4"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="{inactive}"
        android:fillColor="#FF4A4A52"
        android:fillAlpha="0.36" />
    <path android:pathData="{active}">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="41"
                android:startY="31"
                android:endX="66.5"
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
