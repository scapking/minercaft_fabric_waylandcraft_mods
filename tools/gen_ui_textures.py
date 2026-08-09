#!/usr/bin/env python3
"""Generate WaylandCraft sci-fi UI 9-patch textures (PIL)."""
from PIL import Image, ImageDraw

BASE = "src/main/resources/assets/waylandcraft/textures/gui/sprites"
SIZE = 16
RADIUS = 6
BORDER = 6  # 9-slice border in px (must be >= RADIUS)

def rounded_panel(size, radius, fill, outline, outline_width=1):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=fill, outline=outline, width=outline_width)
    return img

def save(img, name):
    path = f"{BASE}/{name}.png"
    img.save(path)
    print(f"wrote {path}")

# 1) Glass panel: dark translucent blue fill + dim border
save(rounded_panel(SIZE, RADIUS, (18, 24, 38, 230), (34, 56, 79, 150), 1), "panel_9")

# 2) Inset field: darker fill + subtle border
save(rounded_panel(SIZE, RADIUS, (13, 19, 32, 230), (44, 74, 99, 120), 1), "field_9")

# 3) Neon border: transparent fill + cyan border (used for focus/hover glow)
save(rounded_panel(SIZE, RADIUS, (0, 229, 255, 26), (0, 229, 255, 200), 1), "neon_border_9")

# 4) Neon filled button: cyan fill, darker cyan edge (text on it uses dark color)
save(rounded_panel(SIZE, RADIUS, (0, 229, 255, 235), (0, 170, 200, 255), 1), "neon_filled_9")

# 5) Neon filled button dimmed (disabled)
save(rounded_panel(SIZE, RADIUS, (30, 58, 74, 160), (50, 90, 110, 120), 1), "neon_filled_dim_9")

# 6) Title bar gradient-ish solid (slightly lighter than panel)
save(rounded_panel(SIZE, RADIUS, (19, 27, 46, 235), (51, 82, 118, 170), 1), "titlebar_9")

# 7) Glow blob (soft radial cyan, for hover underglow behind buttons)
glow = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
gd = ImageDraw.Draw(glow)
for i in range(16, 0, -1):
    a = int(28 * (1 - i / 16) ** 2)
    gd.ellipse([16 - i, 16 - i, 16 + i, 16 + i], fill=(0, 229, 255, a))
glow.save(f"{BASE}/glow.png")
print("wrote", f"{BASE}/glow.png")

print("done")
