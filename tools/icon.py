"""Generates the launcher icon vectors.

The wear is written by a script rather than by hand because it takes on the order of two hundred
marks before erosion reads as erosion instead of as three smudges, and nobody is placing those by
hand. The seed is fixed, so the output is a normal reviewable file that regenerates byte for byte.
"""
import random

# Faceted rather than round: every outer corner of both bowls is a straight chamfer, so the letter
# reads as cut from a slab. The previous semicircular bowls were the "goofy" part - a perfect
# half-circle bowl is a bubble-letter tell.
LETTER = ("M36,26 H63 L70,33 V47 L63,54 H65 L72,61 V75 L65,82 H36 Z "
          "M48,35 H57 L60,38 V42 L57,45 H48 Z "
          "M48,63 H59 L62,66 V70 L59,73 H48 Z")
SHADOW = "M38,28 H65 L72,35 V49 L65,56 H67 L74,63 V77 L67,84 H38 Z"

# The letter's bounding box, for scattering inside. The clip does the real work; this only avoids
# generating thousands of marks that land nowhere.
X0, Y0, X1, Y1 = 36, 26, 74, 84


def speck(rng, x, y, size):
    """An irregular four-sided fleck. Rectangles read as pixels; these read as pitting."""
    pts = []
    for dx, dy in ((0, 0), (1, 0), (1, 1), (0, 1)):
        pts.append((
            round(x + dx * size + rng.uniform(-0.35, 0.35), 2),
            round(y + dy * size + rng.uniform(-0.35, 0.35), 2),
        ))
    head = f"M{pts[0][0]},{pts[0][1]}"
    rest = "".join(f" L{p[0]},{p[1]}" for p in pts[1:])
    return head + rest + " Z"


def path(d, color, alpha):
    return (f'        <path android:pathData="{d}"\n'
            f'            android:fillColor="{color}" android:fillAlpha="{alpha:.2f}" />\n')


def wear(rng, count, colors, weight_corner, sizes=(0.9, 3.4)):
    """Scatters flecks, biased toward the lower-right corner where a real object wears first."""
    out = []
    placed = 0
    while placed < count:
        x = rng.uniform(X0, X1)
        y = rng.uniform(Y0, Y1)
        # Diagonal falloff: 0 at the top-left, 1 at the bottom-right.
        t = ((x - X0) / (X1 - X0) + (y - Y0) / (Y1 - Y0)) / 2
        if rng.random() > (1 - weight_corner) + weight_corner * t:
            continue
        size = rng.uniform(*sizes)
        color, lo, hi = colors[rng.randrange(len(colors))]
        out.append(path(speck(rng, x, y, size), color, rng.uniform(lo, hi)))
        placed += 1
    return out


def scratch(rng, x, y, length, thick, angle_bias):
    """A long thin gouge. Kept nearly horizontal so it crosses the stem and both bowls."""
    dx = length
    dy = length * angle_bias
    return (f"M{x:.1f},{y:.1f} L{x + dx:.1f},{y + dy:.1f} "
            f"L{x + dx:.1f},{y + dy + thick:.1f} L{x:.1f},{y + thick:.1f} Z")


def build_foreground():
    rng = random.Random(7)
    body = []

    # Dark pitting, in two tones so it does not read as one flat stipple.
    body += wear(rng, 150, [("#FF2A1608", 0.35, 0.75), ("#FF5A3418", 0.25, 0.55)], 0.75)
    # Bright abrasion, where the coating has been rubbed back to something lighter.
    body += wear(rng, 45, [("#FFFFE8D2", 0.20, 0.45)], 0.25, sizes=(0.7, 2.2))

    # Gouges. Long, deliberate, and at odds with the letter's own geometry.
    for x, y, length, thick, bias, alpha in (
        (30, 44, 48, 1.5, -0.10, 0.55),
        (30, 58, 48, 0.9, 0.06, 0.40),
        (30, 71, 48, 1.8, -0.05, 0.50),
        (30, 33, 48, 0.7, 0.04, 0.35),
        (30, 78, 48, 1.1, -0.08, 0.45),
    ):
        body.append(path(scratch(rng, x, y, length, thick, bias), "#FF1E1006", alpha))
    # One bright scratch catching the light, so the gouges are not all subtractive.
    body.append(path(scratch(rng, 30, 50, 48, 0.6, -0.09), "#FFFFEEDC", 0.30))

    # The cut edges of the letter, lit from the upper left like the slab behind it.
    body.append(path("M36,26 H63 L66,29 H36 Z", "#FFFFF3E8", 0.28))
    body.append(path("M36,26 H40 V82 H36 Z", "#FFFFF3E8", 0.22))
    body.append(path("M63,82 L65,79 H36 V82 Z", "#FF1E1006", 0.30))

    return f'''<?xml version="1.0" encoding="utf-8"?>
<!--
    The B, cut from the slab in the accent colour.

    Faceted rather than round: the bowls have straight chamfered corners instead of semicircular
    ones, which is the difference between a mark and a bubble letter. It also holds up at 48dp,
    where a curve that shallow would flatten out anyway.

    The wear is generated (see icon.py, seed 7) and clipped to the letter, so the pitting stops at
    its edge the way wear on a real object does rather than floating over the slab as a texture laid
    on top. It is deliberately heavy: an icon is looked at for a quarter of a second at a fifth of
    this size, and the first attempt at this was three translucent bands that vanished entirely.

    Everything sits inside the 66dp safe circle, so no launcher mask clips the letter.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <!-- Cast shadow, offset along the light in the background layer. -->
    <path
        android:pathData="{SHADOW}"
        android:fillColor="#FF000000"
        android:fillAlpha="0.35" />

    <!-- The letter. Lighter where the light falls, deepening to a burnt lower edge. -->
    <path
        android:pathData="{LETTER}"
        android:fillType="evenOdd">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="36"
                android:startY="24"
                android:endX="74"
                android:endY="84">
                <item android:offset="0" android:color="#FFF6CDA6" />
                <item android:offset="0.42" android:color="#FFD9A47C" />
                <item android:offset="1" android:color="#FF95552F" />
            </gradient>
        </aapt:attr>
    </path>

    <group>
        <clip-path android:pathData="{LETTER}" />
{''.join(body)}    </group>
</vector>
'''


def build_background():
    rng = random.Random(19)
    grain = []
    # Grain across the whole slab. Sparse and low-contrast: this is the surface the letter sits on,
    # not the subject, and a busy background is what makes an icon look cheap.
    for _ in range(210):
        x, y = rng.uniform(0, 108), rng.uniform(0, 108)
        size = rng.uniform(0.8, 2.6)
        if rng.random() < 0.45:
            grain.append(path(speck(rng, x, y, size), "#FFFFFFFF", rng.uniform(0.02, 0.07)))
        else:
            grain.append(path(speck(rng, x, y, size), "#FF000000", rng.uniform(0.06, 0.20)))

    return f'''<?xml version="1.0" encoding="utf-8"?>
<!--
    The slab the B is cut into: near-black, lit from the upper left, with the accent glowing up
    through it from behind the letter.

    The glow is the whole reason the background is not a flat fill. An adaptive icon's two layers
    are drawn with a parallax offset on some launchers, so light that belongs *behind* the letter
    has to live here rather than in the foreground - it drifts against the B the way a real light
    source would, instead of travelling with it.

    Grain generated by icon.py, seed 19.
-->
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
                android:startX="0"
                android:startY="0"
                android:endX="108"
                android:endY="108">
                <item android:offset="0" android:color="#FF2E2E38" />
                <item android:offset="0.55" android:color="#FF1A1A21" />
                <item android:offset="1" android:color="#FF0B0B0E" />
            </gradient>
        </aapt:attr>
    </path>

    <!-- The accent, bloomed. Fades out well before the mask edge, so every launcher shape -
         circle, squircle, teardrop - crops only unlit slab. -->
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="49"
                android:centerY="50"
                android:gradientRadius="42">
                <item android:offset="0" android:color="#66D9A47C" />
                <item android:offset="0.45" android:color="#2BC68A5E" />
                <item android:offset="1" android:color="#00C68A5E" />
            </gradient>
        </aapt:attr>
    </path>

{''.join(grain)}
    <!-- Handling wear: the lower right darkened, the upper left caught by the light. -->
    <path
        android:pathData="M108,30 L108,108 L20,108 Z"
        android:fillAlpha="0.22">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="26"
                android:startY="104"
                android:endX="104"
                android:endY="34">
                <item android:offset="0" android:color="#FF000000" />
                <item android:offset="1" android:color="#00000000" />
            </gradient>
        </aapt:attr>
    </path>
    <path
        android:pathData="M0,0 L64,0 L0,58 Z"
        android:fillAlpha="0.12">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0"
                android:startY="0"
                android:endX="56"
                android:endY="50">
                <item android:offset="0" android:color="#FFFFFFFF" />
                <item android:offset="1" android:color="#00FFFFFF" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''


def build_monochrome():
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!--
    The themed-icon layer (Android 13+). The system recolours this to the user's wallpaper palette
    and keeps only the alpha, so the gradients, the wear and the accent all mean nothing here - a
    flat silhouette is the honest version. Drawn a touch heavier than the colour letter, because a
    single-tone shape at 48dp reads thinner than a lit one.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="M35,25 H63 L71,33 V47 L64,54 H66 L73,61 V76 L65,83 H35 Z M48,35 H57 L60,38 V42 L57,45 H48 Z M48,63 H59 L62,66 V70 L59,73 H48 Z"
        android:fillType="evenOdd"
        android:fillColor="#FFFFFFFF" />
</vector>
'''


import os
os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      '..', 'app', 'src', 'main', 'res', 'drawable'))
open("ic_launcher_foreground.xml", "w", newline="\n").write(build_foreground())
open("ic_launcher_background.xml", "w", newline="\n").write(build_background())
open("ic_launcher_monochrome.xml", "w", newline="\n").write(build_monochrome())
print("wrote 3 vectors")
