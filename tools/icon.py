"""Generates the launcher icon vectors.

Run it from anywhere: python tools/icon.py

The dot lattice is written by a script rather than by hand because it is a hundred-odd points that
have to follow the same rule as `Modifier.bramField` in Background.kt (half-step row offsets, dots
carrying the inverse of the ramp), and keeping that in step by hand is how the icon and the app
would quietly drift apart. The output is a normal reviewable XML file, and it regenerates byte for
byte.

Everything is ASCII on purpose. Two lessons from the build: a double hyphen is illegal inside an XML
comment, and Python's open() defaults to the platform encoding, so a file written on Windows lands
as cp1252 while declaring UTF-8. Both fail resource compilation, neither obviously.
"""

# --- The letter ---------------------------------------------------------------------------------
#
# Bold, rounded, balanced. The bowls are cubics rather than semicircles: a true half-circle bowl is
# the bubble-letter tell, and a slightly flattened one reads as a typeface instead. The lower bowl
# is wider and taller than the upper one, which is what "balanced" actually means in a B. Two equal
# bowls look top-heavy, because the eye reads a letter's centre as slightly above its middle.
#
# Drawn at full size in the 108 grid and scaled about the centre by the group below, so the
# proportions stay legible in source while the whole letter clears the safe circle.
LETTER = (
    "M38,24 H58 "
    "C64.63,24 70,30.05 70,37.5 C70,44.95 64.63,51 58,51 "
    "C67.94,51 76,58.39 76,67.5 C76,76.61 67.94,84 58,84 "
    "H38 C35.79,84 34,82.21 34,80 V28 C34,25.79 35.79,24 38,24 Z "
    # Upper counter: small, so the strokes stay heavy.
    "M48,33 H55 C57.21,33 59,35.01 59,37.5 C59,39.99 57.21,42 55,42 H48 Z "
    # Lower counter: larger, following its bowl.
    "M48,60 H55 C59.97,60 64,63.36 64,67.5 C64,71.64 59.97,75 55,75 H48 Z"
)

# The letter's own box is 34..76 by 24..84, centred on 55,54. One unit left puts it on the icon's
# axis; 0.85 brings the far corner of the lower bowl inside the 66dp circle that every launcher
# mask is guaranteed to leave alone.
PIVOT_X, PIVOT_Y = 54.0, 54.0
LETTER_SCALE = 0.85
LETTER_NUDGE_X = -1.0

# --- The lattice --------------------------------------------------------------------------------
#
# Background.kt uses a 22dp step against a phone screen. Held to that here the icon would show about
# four dots, so the pitch is set by how many should be visible at icon size rather than by copying
# the number: nine across reads as a field, four reads as stray specks.
STEP = 12.0
DOT_RADIUS = 1.15
# The ramp and the dot alphas are lifted straight from bramField's dark theme, in the same order.
RAMP = ("#FF07070A", "#FF141419", "#FF1E1E25")
DOT_ALPHAS = (0.20, 0.09, 0.03)


def dot_alpha(y):
    """Interpolates bramField's three-stop alpha ramp. Brightest where the ground is darkest."""
    t = max(0.0, min(1.0, y / 108.0))
    if t < 0.5:
        a, b, local = DOT_ALPHAS[0], DOT_ALPHAS[1], t / 0.5
    else:
        a, b, local = DOT_ALPHAS[1], DOT_ALPHAS[2], (t - 0.5) / 0.5
    return a + (b - a) * local


def lattice():
    """The dot field, one path per dot, on the same rule as the chat background."""
    out = []
    row = 0
    y = 0.0
    while y <= 108.0 + STEP:
        # Alternate rows shift half a step, which reads as a diagonal weave rather than as graph
        # paper. Same trick, same reason, as the app.
        shift = 0.0 if row % 2 == 0 else STEP / 2.0
        x = shift - STEP
        while x <= 108.0 + STEP:
            alpha = dot_alpha(y)
            if -4 <= x <= 112 and -4 <= y <= 112 and alpha >= 0.02:
                out.append(
                    '    <path\n'
                    '        android:pathData="M{x:.2f},{y0:.2f} a{r},{r} 0 1,0 {d},0 '
                    'a{r},{r} 0 1,0 -{d},0 Z"\n'
                    '        android:fillColor="#FFFFFFFF" android:fillAlpha="{a:.3f}" />\n'
                    .format(x=x - DOT_RADIUS, y0=y, r=DOT_RADIUS, d=DOT_RADIUS * 2, a=alpha)
                )
            x += STEP
        y += STEP
        row += 1
    return out


def build_background():
    return '''<?xml version="1.0" encoding="utf-8"?>
<!--
    The same field the chat sits on: a dark vertical ramp under a fine dot lattice, with the accent
    glowing up through it from behind the letter.

    The ramp, the dot colour and the three-stop alpha come from Modifier.bramField in
    Background.kt, including the rule that makes it work: the dots carry the inverse of the ramp,
    brightest where the ground is darkest, so the texture stays visible top to bottom instead of
    dissolving into whichever end matches its own colour. Alternate rows shift half a step, which
    reads as a weave rather than as graph paper.

    The pitch is the one thing not copied. 22dp against a phone screen is a fine lattice; against a
    108dp icon it is four dots. It is set here by how many should be visible at icon size.

    The glow lives in this layer rather than in the foreground on purpose. Launchers draw an
    adaptive icon's two layers with a parallax offset, so light that belongs behind the letter has
    to sit behind it, or it slides around with the B instead of staying put.

    Lattice generated by tools/icon.py.
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
                android:startX="54"
                android:startY="0"
                android:endX="54"
                android:endY="108">
                <item android:offset="0" android:color="{r0}" />
                <item android:offset="0.5" android:color="{r1}" />
                <item android:offset="1" android:color="{r2}" />
            </gradient>
        </aapt:attr>
    </path>

{dots}
    <!-- The accent, bloomed. A vector has no blur filter, so the softness has to be a gradient.
         Fades out well before the mask edge, so every launcher shape crops only unlit field. -->
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="52"
                android:centerY="52"
                android:gradientRadius="46">
                <item android:offset="0" android:color="#54D9A47C" />
                <item android:offset="0.4" android:color="#2ED9A47C" />
                <item android:offset="0.75" android:color="#0FC68A5E" />
                <item android:offset="1" android:color="#00C68A5E" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''.format(r0=RAMP[0], r1=RAMP[1], r2=RAMP[2], dots="".join(lattice()))


def halo(scale, color):
    """A copy of the letter, blown up behind itself.

    The nearest thing to an outer glow a VectorDrawable can do. Two at different sizes and alphas
    give a falloff; one on its own gives a visible outline, which is worse than no glow at all.
    """
    return '''    <group
        android:pivotX="{px}"
        android:pivotY="{py}"
        android:scaleX="{s:.4f}"
        android:scaleY="{s:.4f}"
        android:translateX="{tx}">
        <path
            android:pathData="{d}"
            android:fillType="evenOdd"
            android:fillColor="{c}" />
    </group>
'''.format(px=PIVOT_X, py=PIVOT_Y, s=LETTER_SCALE * scale, tx=LETTER_NUDGE_X, d=LETTER, c=color)


def build_foreground():
    return '''<?xml version="1.0" encoding="utf-8"?>
<!--
    The B.

    Bold, rounded and balanced: the bowls are cubics rather than semicircles, because a true
    half-circle bowl is the bubble-letter tell, and the lower bowl is wider and taller than the
    upper one. Two equal bowls look top-heavy, since the eye reads a letter's centre as slightly
    above its middle, which is why making them equal looks wrong without looking like anything in
    particular.

    Solid, on a smooth diagonal gradient, with the glow carried by two scaled copies behind it.
    That is as close to an outer glow as a VectorDrawable gets: there are no filters here, so
    softness is either a gradient or nothing.

    The letter is drawn at full size in the 108 grid and scaled about the centre by the group, so
    the coordinates stay legible while the far edge of the lower bowl still clears the 66dp safe
    circle that every launcher mask is guaranteed to leave alone.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

{halo_outer}{halo_inner}
    <group
        android:pivotX="{px}"
        android:pivotY="{py}"
        android:scaleX="{s}"
        android:scaleY="{s}"
        android:translateX="{tx}">
        <path
            android:pathData="{d}"
            android:fillType="evenOdd">
            <aapt:attr name="android:fillColor">
                <gradient
                    android:type="linear"
                    android:startX="34"
                    android:startY="24"
                    android:endX="76"
                    android:endY="84">
                    <item android:offset="0" android:color="#FFF7D3B2" />
                    <item android:offset="0.5" android:color="#FFD9A47C" />
                    <item android:offset="1" android:color="#FFBB7B4E" />
                </gradient>
            </aapt:attr>
        </path>
    </group>
</vector>
'''.format(
        halo_outer=halo(1.13, "#1FD9A47C"),
        halo_inner=halo(1.06, "#33D9A47C"),
        px=PIVOT_X, py=PIVOT_Y, s=LETTER_SCALE, tx=LETTER_NUDGE_X, d=LETTER,
    )


def build_monochrome():
    return '''<?xml version="1.0" encoding="utf-8"?>
<!--
    The themed-icon layer (Android 13+). The system recolours this to the user's wallpaper palette
    and keeps only the alpha, so the gradient, the glow and the accent all mean nothing here: a flat
    silhouette is the honest version. Scaled a little larger than the colour letter, because a
    single-tone shape at 48dp reads thinner than a lit one.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:pivotX="{px}"
        android:pivotY="{py}"
        android:scaleX="0.90"
        android:scaleY="0.90"
        android:translateX="{tx}">
        <path
            android:pathData="{d}"
            android:fillType="evenOdd"
            android:fillColor="#FFFFFFFF" />
    </group>
</vector>
'''.format(px=PIVOT_X, py=PIVOT_Y, tx=LETTER_NUDGE_X, d=LETTER)


import os

# Resolves relative to this file, so the generator runs from anywhere in the tree.
os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "..", "app", "src", "main", "res", "drawable"))
open("ic_launcher_foreground.xml", "w", encoding="utf-8", newline="\n").write(build_foreground())
open("ic_launcher_background.xml", "w", encoding="utf-8", newline="\n").write(build_background())
open("ic_launcher_monochrome.xml", "w", encoding="utf-8", newline="\n").write(build_monochrome())
print("wrote 3 vectors")
