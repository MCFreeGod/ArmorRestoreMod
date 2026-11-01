"""Generates the ArmorRestore mod icon (256x256 PNG) in vanilla pixel-art style.

The mod restores strong pre-1.9 armor - which is exactly why a shield would be the
wrong symbol, since the point is that you no longer need one. The icon is the
diamond chestplate, the strongest armor of that era.

The chestplate pixels are a verbatim copy of the vanilla texture
(assets/minecraft/textures/item/diamond_chestplate.png). It is pasted 1:1 onto a
20x20 grid - no scaling, no blending - so the sprite stays exactly as drawn in
game, then the whole canvas is nearest-neighbour upscaled to 256 px.
"""

from PIL import Image

SIZE = 256
GRID = 20           # canvas grid; the 16x16 sprite sits inside with a 2-cell margin
MARGIN = 2

# Vanilla diamond palette, sampled from the texture itself.
PALETTE = {
    "K": (8, 37, 32),        # #082520  outline / deep shadow
    "D": (14, 63, 54),       # #0E3F36  shoulder top
    "M": (32, 197, 181),     # #20C5B5  mid tone
    "N": (26, 170, 167),     # #1AAAA7  mid-deep
    "B": (74, 237, 217),     # #4AEDD9  base body
    "L": (161, 251, 232),    # #A1FBE8  highlight
    "W": (255, 255, 255),    # specular
}

# Verbatim pixel layout of the vanilla diamond chestplate.
CHESTPLATE = [
    "................",
    "................",
    ".DDDDD....DDDDD.",
    ".DWLBD....DWLBD.",
    ".DLBBMD..DMLBBD.",
    ".DBBBLMDDMBBBBD.",
    ".DNMBWLLLBBBMND.",
    ".KKNLLLBBBBBNKK.",
    "...KLLBBBBBBK...",
    "...KBLBBBBBMK...",
    "...KBBBBBBBMK...",
    "...KMBBBBBMMK...",
    "...KNMMMMMMNK...",
    "....KNMMMMNK....",
    ".....KKKKKK.....",
    "................",
]

# Backdrop: near-black slate, so the cyan armor carries all the contrast.
BG_TOP = (32, 43, 56)
BG_BOTTOM = (15, 21, 29)
BG_EDGE = (9, 13, 18)


def backdrop() -> Image.Image:
    """Dark panel with a slight vertical lift and one-cell rounded corners."""
    img = Image.new("RGBA", (GRID, GRID), (0, 0, 0, 0))

    for y in range(GRID):
        for x in range(GRID):
            if (x < 1 and y < 1) or (x > 18 and y < 1) or (x < 1 and y > 18) or (x > 18 and y > 18):
                continue
            if x in (0, 19) or y in (0, 19):
                img.putpixel((x, y), BG_EDGE + (255,))
                continue
            t = y / (GRID - 1)
            col = tuple(int(BG_TOP[i] * (1 - t) + BG_BOTTOM[i] * t) for i in range(3))
            img.putpixel((x, y), col + (255,))

    return img


def chestplate() -> Image.Image:
    """The vanilla sprite at its native 16x16 resolution."""
    img = Image.new("RGBA", (GRID, GRID), (0, 0, 0, 0))
    for y, row in enumerate(CHESTPLATE):
        for x, key in enumerate(row):
            if key == ".":
                continue
            img.putpixel((x + MARGIN, y + MARGIN), PALETTE[key] + (255,))
    return img


def main() -> None:
    canvas = Image.alpha_composite(backdrop(), chestplate())
    icon = canvas.resize((SIZE, SIZE), Image.NEAREST)
    icon.save("icon.png")
    for size in (128, 64, 32):
        icon.resize((size, size), Image.NEAREST).save(f"icon_{size}.png")
    print("wrote icon.png, icon_128.png, icon_64.png, icon_32.png")


if __name__ == "__main__":
    main()
