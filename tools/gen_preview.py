#!/usr/bin/env python3
"""Generate sci-fi UI mockup previews to visually verify the new design system."""
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import os

OUT = "tools/preview"
os.makedirs(OUT, exist_ok=True)

W, H = 640, 360
BG = (11, 15, 26, 255)
PANEL = (18, 24, 38, 230)
FIELD = (13, 19, 32, 230)
TITLEBAR = (19, 27, 46, 235)
CYAN = (0, 229, 255)
CYAN_DIM = (0, 229, 255, 70)
VIOLET = (167, 139, 250)
TEXT = (226, 232, 240)
TEXT_DIM = (148, 163, 184)
DANGER = (248, 113, 113)
SUCCESS = (52, 211, 153)
WARNING = (251, 191, 36)
BORDER = (44, 74, 99, 160)

def glow_layer(size, color, alpha=40):
    """Soft radial glow."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for i in range(size // 2, 0, -1):
        a = int(alpha * (1 - i / (size // 2)) ** 2)
        d.ellipse([size//2 - i, size//2 - i, size//2 + i, size//2 + i], fill=(*color, a))
    return img

def rounded(x, y, w, h, r, fill, outline=None, ow=1):
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([x, y, x + w - 1, y + h - 1], radius=r, fill=fill, outline=outline, width=ow)

def text(x, y, s, color=TEXT, size=10, bold=False):
    try:
        f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", size)
    except Exception:
        f = ImageFont.load_default()
    d = ImageDraw.Draw(img)
    d.text((x, y), s, font=f, fill=color)

def neon_button(x, y, w, h, label, enabled=True, glow=True):
    if glow:
        g = glow_layer(max(w, h) + 30, CYAN, 30)
        img.alpha_composite(g, (x + w//2 - g.width//2, y + h//2 - g.height//2))
    if enabled:
        rounded(x, y, w, h, 4, CYAN, (0, 170, 200, 255))
        text(x + w//2 - len(label) * 3, y + h//2 - 6, label, (4, 18, 26), 10, bold=True)
    else:
        rounded(x, y, w, h, 4, (30, 58, 74, 160), (50, 90, 110, 120))
        text(x + w//2 - len(label) * 3, y + h//2 - 6, label, (71, 85, 105), 10, bold=True)

def field_btn(x, y, size, icon_color=CYAN):
    rounded(x, y, size, size, 4, FIELD, BORDER)
    d = ImageDraw.Draw(img)
    d.rectangle([x + size//2 - 2, y + size//2 - 2, x + size//2 + 2, y + size//2 + 2], fill=icon_color)

def toggle(x, y, w, h, on):
    if on:
        rounded(x, y, w, h, 4, CYAN, (0, 170, 200, 255))
        d = ImageDraw.Draw(img)
        d.rectangle([x + w - h + 1, y + 3, x + w - 3, y + h - 3], fill=(4, 18, 26))
        text(x + 8, y + h//2 - 6, "ON", (4, 18, 26), 9, bold=True)
    else:
        rounded(x, y, w, h, 4, FIELD, BORDER)
        d = ImageDraw.Draw(img)
        d.rectangle([x + 3, y + 3, x + h - 1, y + h - 3], fill=TEXT_DIM)
        text(x + w - 30, y + h//2 - 6, "OFF", TEXT_DIM, 9, bold=True)

def window_card(x, y, w, h, title, focused=True, content_color=(60, 90, 140, 200)):
    # glow for focused
    if focused:
        g = glow_layer(max(w, h) + 60, CYAN, 26)
        img.alpha_composite(g, (x + w//2 - g.width//2, y + h//2 - g.height//2))
    rounded(x, y, w, h, 6, TITLEBAR, (0, 229, 255, 255) if focused else (74, 111, 165, 153), 1)
    rounded(x, y + 18, w, h - 18, 0, FIELD)
    # fake window content (gradient)
    d = ImageDraw.Draw(img)
    for i in range(h - 36):
        c = (int(30 + 30 * i / max(1, h)), int(50 + 60 * i / max(1, h)), int(90 + 80 * i / max(1, h)))
        d.line([x + 2, y + 20 + i, x + w - 3, y + 20 + i], fill=(*c, 255))
    text(x + 6, y + 4, title, TEXT if focused else TEXT_DIM, 8)
    if focused:
        d.rectangle([x + w - 4, y + 8, x + w - 2, y + 10], fill=CYAN)

# ============ WindowManagerScreen ============
img = Image.new("RGBA", (W, H), BG)
# work area panel
rounded(34, 34, 560, 300, 10, PANEL, (0, 229, 255, 60), 1)
d = ImageDraw.Draw(img)
d.rectangle([33, 33, 595, 335], outline=(0, 229, 255, 40), width=1)
# toolbar
neon_button(520, 6, 100, 18, "Grab", True)
neon_button(270, 6, 100, 18, "Resize", True)
# capture mode
text(48, 9, "Capture Mode", CYAN, 9, bold=True)
field_btn(28, 6, 22)
# side icon buttons
for i, (tt, c) in enumerate([("Hide", CYAN), ("Pin", VIOLET), ("Window", CYAN)]):
    field_btn(6, 34 + i * 30, 22, c)
    if i == 0:
        g = glow_layer(40, CYAN, 25)
        img.alpha_composite(g, (6 + 11 - g.width//2, 34 + 11 - g.height//2))
# selector
rounded(34, 12, 200, 16, 4, FIELD, (0, 229, 255, 200), 1)
text(40, 14, "Firefox", (4, 18, 26), 8, bold=True)
# window card
window_card(150, 60, 320, 220, "Firefox — WaylandCraft", True)
img.convert("RGB").save(f"{OUT}/window_manager.png")
print("wrote", f"{OUT}/window_manager.png")

# ============ AppLauncherScreen ============
img = Image.new("RGBA", (W, H), BG)
# main panel
rounded(W//2 - 130, 8, 260, 344, 10, PANEL, BORDER, 1)
# title
text(W//2 - 40, 14, "APP LAUNCHER", CYAN, 11, bold=True)
# search field
rounded(W//2 - 110, 32, 220, 20, 4, FIELD, BORDER)
text(W//2 - 100, 36, "Search applications...", TEXT_DIM, 9)
# app list
rounded(W//2 - 110, 60, 200, 240, 6, FIELD)
apps = [("Firefox", True), ("Code", False), ("Terminal", False), ("Files", False), ("Settings", False)]
for i, (name, sel) in enumerate(apps):
    y = 66 + i * 46
    if sel:
        rounded(W//2 - 104, y, 188, 40, 4, CYAN, (0, 170, 200, 255))
        text(W//2 - 92, y + 12, name, (4, 18, 26), 10, bold=True)
    else:
        rounded(W//2 - 104, y, 188, 40, 4, (17, 26, 42, 255), (44, 74, 99, 120))
        text(W//2 - 92, y + 12, name, TEXT, 10)
# neon scrollbar
d = ImageDraw.Draw(img)
d.rectangle([W//2 + 96, 62, W//2 + 99, 300], fill=(13, 19, 32, 255))
d.rectangle([W//2 + 96, 62, W//2 + 99, 110], fill=CYAN)
# category selector column
for i, c in enumerate([CYAN, VIOLET, SUCCESS, WARNING, DANGER, TEXT_DIM]):
    x = W//2 - 122 - 24
    y = 60 + i * 24
    rounded(x, y, 19, 19, 4, FIELD, BORDER)
    d.rectangle([x + 7, y + 7, x + 12, y + 12], fill=c)
img.convert("RGB").save(f"{OUT}/app_launcher.png")
print("wrote", f"{OUT}/app_launcher.png")

# ============ SharedWindowManagerScreen ============
img = Image.new("RGBA", (W, H), BG)
# title
text(W//2 - 90, 10, "SHARED WINDOWS", CYAN, 11, bold=True)
# left list panel
rounded(60, 40, 240, 230, 6, PANEL, BORDER)
text(70, 48, "Remote Windows", TEXT_DIM, 9, bold=True)
windows = [("Firefox (Alice)", SUCCESS, True), ("Code (Bob)", WARNING, False), ("Terminal (Carol)", DANGER, False)]
for i, (name, pcolor, sel) in enumerate(windows):
    y = 66 + i * 30
    if sel:
        rounded(66, y, 228, 24, 4, CYAN, (0, 170, 200, 255))
        text(74, y + 6, name, (4, 18, 26), 9, bold=True)
    else:
        text(74, y + 6, name, TEXT, 9)
    d = ImageDraw.Draw(img)
    d.rectangle([280, y + 8, 288, y + 16], fill=pcolor)
# details
rounded(60, 280, 240, 52, 6, FIELD, BORDER)
text(70, 286, "name: Firefox", TEXT, 8)
text(70, 298, "owner: Alice   perm: CONTROL", CYAN, 8)
text(70, 310, "size: 1920x1080", TEXT_DIM, 8)
# right preview (component!)
window_card(330, 40, 260, 180, "Firefox — shared", True, content_color=(50, 90, 160, 200))
# bottom buttons
neon_button(60, 340, 100, 20, "Subscribe", True, glow=False)
neon_button(170, 340, 100, 20, "Unsubscribe", False, glow=False)
neon_button(420, 340, 100, 20, "Close", True, glow=False)
img.convert("RGB").save(f"{OUT}/shared_windows.png")
print("wrote", f"{OUT}/shared_windows.png")
print("done")
