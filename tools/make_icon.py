"""Draw the Aerowave app icon: a rounded aqua orb with an amber radio cue.

The artwork is rendered at 4x and downsampled so the rounded frame, orb
silhouette and radio arcs retain clean alpha edges at native icon sizes.
"""
from PIL import Image, ImageChops, ImageDraw, ImageFilter
import math
import pathlib


S = 4
N = 1024
W = N * S

OUT = pathlib.Path(__file__).resolve().parent.parent / "src-tauri" / "icons"


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def vgrad(size, top, bottom):
    """Return a vertical RGB gradient."""
    width, height = size
    gradient = Image.new("RGB", (1, height))
    pixels = gradient.load()
    for y in range(height):
        pixels[0, y] = lerp(top, bottom, y / max(1, height - 1))
    return gradient.resize((width, height), Image.Resampling.BICUBIC)


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size[0] - 1, size[1] - 1], radius, fill=255
    )
    return mask


def rounded_arc(draw, box, start, end, fill, width):
    """Draw a radio arc with round caps."""
    draw.arc(box, start, end, fill=fill, width=width)
    cx = (box[0] + box[2]) / 2
    cy = (box[1] + box[3]) / 2
    rx = (box[2] - box[0]) / 2 - width / 2
    ry = (box[3] - box[1]) / 2 - width / 2
    cap = width / 2
    for angle in (start, end):
        radians = math.radians(angle)
        x = cx + math.cos(radians) * rx
        y = cy + math.sin(radians) * ry
        draw.ellipse((x - cap, y - cap, x + cap, y + cap), fill=fill)


img = Image.new("RGBA", (W, W), (0, 0, 0, 0))

# A quiet frame gives the bright item enough contrast at taskbar size.
pad = round(W * 0.045)
plate_box = (pad, pad, W - pad, W - pad)
plate_size = (plate_box[2] - plate_box[0], plate_box[3] - plate_box[1])
plate_radius = round(W * 0.19)
plate = vgrad(plate_size, (14, 57, 76), (4, 22, 38)).convert("RGBA")
plate.putalpha(rounded_mask(plate_size, plate_radius))
img.alpha_composite(plate, (plate_box[0], plate_box[1]))
frame_mask = Image.new("L", (W, W), 0)
ImageDraw.Draw(frame_mask).rounded_rectangle(plate_box, plate_radius, fill=255)

draw = ImageDraw.Draw(img)
draw.rounded_rectangle(
    plate_box,
    plate_radius,
    outline=(91, 213, 223, 225),
    width=round(W * 0.011),
)
inset = round(W * 0.025)
draw.rounded_rectangle(
    (
        plate_box[0] + inset,
        plate_box[1] + inset,
        plate_box[2] - inset,
        plate_box[3] - inset,
    ),
    round(plate_radius * 0.84),
    outline=(3, 30, 47, 220),
    width=round(W * 0.006),
)

# Soft teal depth replaces the old rivets and heavy plate decoration.
halo = Image.new("RGBA", (W, W), (0, 0, 0, 0))
ImageDraw.Draw(halo).ellipse(
    (W * 0.12, W * 0.18, W * 0.83, W * 0.90), fill=(39, 179, 206, 88)
)
halo = halo.filter(ImageFilter.GaussianBlur(round(W * 0.10)))
halo.putalpha(ImageChops.multiply(halo.getchannel("A"), frame_mask))
img.alpha_composite(halo)
draw = ImageDraw.Draw(img)

# Two compact arcs preserve the radio identity without competing with the orb.
emitter = (W * 0.635, W * 0.365)
for arc_radius, width, color in (
    (W * 0.135, W * 0.032, (255, 190, 67, 255)),
    (W * 0.220, W * 0.027, (255, 172, 47, 225)),
):
    rounded_arc(
        draw,
        (
            emitter[0] - arc_radius,
            emitter[1] - arc_radius,
            emitter[0] + arc_radius,
            emitter[1] + arc_radius,
        ),
        280,
        350,
        color,
        round(width),
    )

# The orb floats like a game item. Smooth shading gives it depth, and the round
# silhouette remains recognizable when reduced to 16px.
center = (W * 0.46, W * 0.575)
orb_radius = W * 0.315
shadow = Image.new("RGBA", (W, W), (0, 0, 0, 0))
ImageDraw.Draw(shadow).ellipse(
    (
        center[0] - orb_radius * 0.83,
        center[1] + orb_radius * 0.64,
        center[0] + orb_radius * 0.83,
        center[1] + orb_radius * 1.05,
    ),
    fill=(0, 7, 18, 190),
)
shadow = shadow.filter(ImageFilter.GaussianBlur(round(W * 0.035)))
shadow.putalpha(ImageChops.multiply(shadow.getchannel("A"), frame_mask))
img.alpha_composite(shadow)

orb = Image.new("RGBA", (W, W), (0, 0, 0, 0))
orb_draw = ImageDraw.Draw(orb)
light = (center[0] - orb_radius * 0.30, center[1] - orb_radius * 0.34)
steps = 360
for step in range(steps):
    t = step / (steps - 1)
    color = lerp((7, 56, 79), (156, 237, 233), t ** 0.84)
    ring_radius = orb_radius * (1 - t)
    ring_x = center[0] + (light[0] - center[0]) * t
    ring_y = center[1] + (light[1] - center[1]) * t
    orb_draw.ellipse(
        (
            ring_x - ring_radius,
            ring_y - ring_radius,
            ring_x + ring_radius,
            ring_y + ring_radius,
        ),
        fill=color + (255,),
    )

orb_mask = Image.new("L", (W, W), 0)
ImageDraw.Draw(orb_mask).ellipse(
    (
        center[0] - orb_radius,
        center[1] - orb_radius,
        center[0] + orb_radius,
        center[1] + orb_radius,
    ),
    fill=255,
)

# Deep lower-right shade and mint bounce give the sphere weight without facets.
shade = Image.new("RGBA", (W, W), (0, 0, 0, 0))
ImageDraw.Draw(shade).ellipse(
    (
        center[0] - orb_radius * 0.05,
        center[1] - orb_radius * 0.02,
        center[0] + orb_radius * 1.22,
        center[1] + orb_radius * 1.16,
    ),
    fill=(1, 25, 46, 105),
)
shade = shade.filter(ImageFilter.GaussianBlur(round(W * 0.050)))
shade.putalpha(ImageChops.multiply(shade.getchannel("A"), orb_mask))
orb.alpha_composite(shade)

bounce = Image.new("RGBA", (W, W), (0, 0, 0, 0))
ImageDraw.Draw(bounce).ellipse(
    (
        center[0] - orb_radius * 0.68,
        center[1] + orb_radius * 0.46,
        center[0] + orb_radius * 0.22,
        center[1] + orb_radius * 0.84,
    ),
    fill=(156, 237, 233, 115),
)
bounce = bounce.filter(ImageFilter.GaussianBlur(round(W * 0.025)))
bounce.putalpha(ImageChops.multiply(bounce.getchannel("A"), orb_mask))
orb.alpha_composite(bounce)

# Keep the main highlight tight so it looks polished rather than glassy.
highlight = Image.new("RGBA", (W, W), (0, 0, 0, 0))
ImageDraw.Draw(highlight).ellipse(
    (
        center[0] - orb_radius * 0.55,
        center[1] - orb_radius * 0.61,
        center[0] - orb_radius * 0.27,
        center[1] - orb_radius * 0.42,
    ),
    fill=(221, 255, 245, 235),
)
highlight = highlight.filter(ImageFilter.GaussianBlur(round(W * 0.008)))
highlight.putalpha(ImageChops.multiply(highlight.getchannel("A"), orb_mask))
orb.alpha_composite(highlight)

orb_draw = ImageDraw.Draw(orb)
orb_draw.arc(
    (
        center[0] - orb_radius,
        center[1] - orb_radius,
        center[0] + orb_radius,
        center[1] + orb_radius,
    ),
    165,
    288,
    fill=(200, 255, 244, 220),
    width=round(W * 0.006),
)
orb.putalpha(ImageChops.multiply(orb.getchannel("A"), orb_mask))
img.alpha_composite(orb)

# A small transmitter bead ties the amber signal to the crystal surface.
draw = ImageDraw.Draw(img)
bead_radius = W * 0.030
draw.ellipse(
    (
        emitter[0] - bead_radius,
        emitter[1] - bead_radius,
        emitter[0] + bead_radius,
        emitter[1] + bead_radius,
    ),
    fill=(255, 184, 54, 255),
    outline=(255, 225, 153, 255),
    width=round(W * 0.008),
)

final = img.resize((N, N), Image.Resampling.LANCZOS)
OUT.mkdir(parents=True, exist_ok=True)
source_path = OUT / "icon-source.png"
final.save(source_path, optimize=True)
final.resize((64, 64), Image.Resampling.LANCZOS).save(
    OUT / "64x64.png", optimize=True
)
print("wrote", source_path)
print("wrote", OUT / "64x64.png")
