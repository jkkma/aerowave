"""Draw the Aerowave app icon: a glossy aqua orb broadcasting amber arcs,
seated in a bevelled dark-metal plate. Renders at 4x and downsamples."""
from PIL import Image, ImageDraw, ImageFilter
import math
import pathlib

S = 4                      # supersample factor
N = 1024                   # final icon edge
W = N * S

OUT = pathlib.Path(__file__).resolve().parent.parent / "src-tauri" / "icons"


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def vgrad(size, top, bottom):
    """Vertical gradient image."""
    w, h = size
    g = Image.new("RGB", (1, h))
    px = g.load()
    for y in range(h):
        px[0, y] = lerp(top, bottom, y / max(1, h - 1))
    return g.resize((w, h), Image.BICUBIC)


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius, fill=255)
    return m


img = Image.new("RGBA", (W, W), (0, 0, 0, 0))

# --- metal plate -----------------------------------------------------------
pad = int(W * 0.045)
plate_box = (pad, pad, W - pad, W - pad)
pw, ph = plate_box[2] - plate_box[0], plate_box[3] - plate_box[1]
radius = int(W * 0.20)

plate = vgrad((pw, ph), (58, 78, 94), (14, 24, 36)).convert("RGBA")
plate.putalpha(rounded_mask((pw, ph), radius))
img.alpha_composite(plate, (plate_box[0], plate_box[1]))

d = ImageDraw.Draw(img)
# outer bevel: bright top-left edge, dark bottom-right
d.rounded_rectangle(plate_box, radius, outline=(150, 196, 214, 200), width=int(W * 0.010))
inset = int(W * 0.022)
d.rounded_rectangle(
    (plate_box[0] + inset, plate_box[1] + inset, plate_box[2] - inset, plate_box[3] - inset),
    int(radius * 0.86), outline=(8, 16, 24, 170), width=int(W * 0.006),
)

# corner rivets
rv = int(W * 0.017)
for cx, cy in ((0.155, 0.155), (0.845, 0.155), (0.155, 0.845), (0.845, 0.845)):
    x, y = int(W * cx), int(W * cy)
    d.ellipse((x - rv, y - rv, x + rv, y + rv), fill=(96, 122, 140, 255),
              outline=(18, 30, 42, 255), width=int(W * 0.004))
    d.ellipse((x - rv * 0.45, y - rv * 0.75, x + rv * 0.45, y - rv * 0.15), fill=(196, 226, 238, 220))

# --- amber broadcast arcs --------------------------------------------------
cx, cy = W // 2, int(W * 0.545)
for i, (r, a) in enumerate(((0.30, 210), (0.375, 150), (0.45, 95))):
    rr = int(W * r)
    d.arc((cx - rr, cy - rr, cx + rr, cy + rr), 205, 335,
          fill=(255, 176, 46, a), width=int(W * 0.028))

# --- antenna mast (behind the orb) -----------------------------------------
mx = cx
d.line((mx, int(W * 0.285), mx, cy), fill=(198, 226, 240, 235), width=int(W * 0.020))
tip = int(W * 0.038)
ty = int(W * 0.285)
d.ellipse((mx - tip, ty - tip, mx + tip, ty + tip), fill=(255, 190, 66, 255))
d.ellipse((mx - tip * 0.42, ty - tip * 0.72, mx + tip * 0.12, ty - tip * 0.18),
          fill=(255, 246, 218, 245))

# --- glossy aqua orb -------------------------------------------------------
orb = Image.new("RGBA", (W, W), (0, 0, 0, 0))
od = ImageDraw.Draw(orb)
R = int(W * 0.235)

# radial body lit from the upper left: deep teal rim to bright aqua core
lx, ly = cx - R * 0.34, cy - R * 0.40
steps = 300
for i in range(steps):
    t = i / (steps - 1)
    r = R * (1 - t)
    col = lerp((7, 52, 76), (128, 240, 252), t ** 1.6)
    ox = cx + (lx - cx) * t
    oy = cy + (ly - cy) * t
    od.ellipse((ox - r, oy - r, ox + r, oy + r), fill=col + (255,))

# bounce light at the bottom of the sphere
bl = Image.new("RGBA", (W, W), (0, 0, 0, 0))
ImageDraw.Draw(bl).ellipse(
    (cx - R * 0.70, cy + R * 0.24, cx + R * 0.70, cy + R * 0.90), fill=(120, 255, 232, 170))
bl = bl.filter(ImageFilter.GaussianBlur(W * 0.018))
orb.alpha_composite(bl)

# specular gel highlight across the top third
hl = Image.new("RGBA", (W, W), (0, 0, 0, 0))
ImageDraw.Draw(hl).ellipse(
    (cx - R * 0.76, cy - R * 0.94, cx + R * 0.76, cy - R * 0.24), fill=(255, 255, 255, 170))
hl = hl.filter(ImageFilter.GaussianBlur(W * 0.020))
orb.alpha_composite(hl)

# small hard glint
ImageDraw.Draw(orb).ellipse(
    (cx - R * 0.54, cy - R * 0.72, cx - R * 0.14, cy - R * 0.44), fill=(255, 255, 255, 240))

# clip to the sphere
mask = Image.new("L", (W, W), 0)
ImageDraw.Draw(mask).ellipse((cx - R, cy - R, cx + R, cy + R), fill=255)
orb.putalpha(Image.composite(orb.getchannel("A"), Image.new("L", (W, W), 0), mask))
ImageDraw.Draw(orb).ellipse((cx - R, cy - R, cx + R, cy + R),
                            outline=(232, 252, 255, 240), width=int(W * 0.012))

glow = orb.filter(ImageFilter.GaussianBlur(W * 0.032))
glow.putalpha(glow.getchannel("A").point(lambda v: v // 2))
img.alpha_composite(glow)
img.alpha_composite(orb)

final = img.resize((N, N), Image.LANCZOS)
OUT.mkdir(parents=True, exist_ok=True)
src = OUT / "icon-source.png"
final.save(src)
print("wrote", src)
