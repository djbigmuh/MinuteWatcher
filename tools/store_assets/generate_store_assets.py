#!/usr/bin/env python3
"""
Store Assets Generator for MinuteWatcher.
Generates fastlane store metadata images (icon.png 512x512, featureGraphic.png 1024x500)
from canonical vector drawables in app/src/main/res/drawable/.
"""

import os
import re
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw, ImageChops, ImageFont

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT_DIR = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))

FOREGROUND_XML = os.path.join(ROOT_DIR, "app", "src", "main", "res", "drawable", "ic_launcher_foreground.xml")
MONOCHROME_XML = os.path.join(ROOT_DIR, "app", "src", "main", "res", "drawable", "ic_launcher_monochrome.xml")

OUTPUT_DIR = os.path.join(ROOT_DIR, "fastlane", "metadata", "android", "en-US", "images")
ICON_PNG = os.path.join(OUTPUT_DIR, "icon.png")
FEATURE_PNG = os.path.join(OUTPUT_DIR, "featureGraphic.png")

BACKGROUND_COLOR = (10, 30, 54, 255) # #0A1E36

def hex_to_rgba(hex_str, default_color=(255, 255, 255, 255)):
    if not hex_str:
        return default_color
    hex_str = hex_str.lstrip("#")
    if len(hex_str) == 8: # #AARRGGBB in Android
        a = int(hex_str[0:2], 16)
        r = int(hex_str[2:4], 16)
        g = int(hex_str[4:6], 16)
        b = int(hex_str[6:8], 16)
        return (r, g, b, a)
    elif len(hex_str) == 6: # #RRGGBB
        r = int(hex_str[0:2], 16)
        g = int(hex_str[2:4], 16)
        b = int(hex_str[4:6], 16)
        return (r, g, b, 255)
    elif len(hex_str) == 3:
        r = int(hex_str[0]*2, 16)
        g = int(hex_str[1]*2, 16)
        b = int(hex_str[2]*2, 16)
        return (r, g, b, 255)
    return default_color

def parse_svg_path(path_str, scale=1.0, offset_x=0.0, offset_y=0.0):
    tokens = re.findall(r'[A-Za-z]|-?\d+\.?\d*', path_str)
    subpaths = []
    current_subpath = []
    curr_x, curr_y = 0.0, 0.0
    start_x, start_y = 0.0, 0.0

    i = 0
    cmd = ''
    while i < len(tokens):
        t = tokens[i]
        if t.isalpha():
            cmd = t
            i += 1

        if cmd == 'M':
            x, y = float(tokens[i]) * scale + offset_x, float(tokens[i+1]) * scale + offset_y
            curr_x, curr_y = x, y
            start_x, start_y = x, y
            current_subpath.append((x, y))
            i += 2
            cmd = 'L'
        elif cmd == 'm':
            x, y = curr_x + float(tokens[i]) * scale, curr_y + float(tokens[i+1]) * scale
            curr_x, curr_y = x, y
            start_x, start_y = x, y
            current_subpath.append((x, y))
            i += 2
            cmd = 'l'
        elif cmd == 'L':
            x, y = float(tokens[i]) * scale + offset_x, float(tokens[i+1]) * scale + offset_y
            curr_x, curr_y = x, y
            current_subpath.append((x, y))
            i += 2
        elif cmd == 'l':
            x, y = curr_x + float(tokens[i]) * scale, curr_y + float(tokens[i+1]) * scale
            curr_x, curr_y = x, y
            current_subpath.append((x, y))
            i += 2
        elif cmd == 'C':
            x1, y1 = float(tokens[i]) * scale + offset_x, float(tokens[i+1]) * scale + offset_y
            x2, y2 = float(tokens[i+2]) * scale + offset_x, float(tokens[i+3]) * scale + offset_y
            x, y = float(tokens[i+4]) * scale + offset_x, float(tokens[i+5]) * scale + offset_y
            for step in range(1, 21):
                u = step / 20.0
                px = (1-u)**3 * curr_x + 3*(1-u)**2*u * x1 + 3*(1-u)*u**2 * x2 + u**3 * x
                py = (1-u)**3 * curr_y + 3*(1-u)**2*u * y1 + 3*(1-u)*u**2 * y2 + u**3 * y
                current_subpath.append((px, py))
            curr_x, curr_y = x, y
            i += 6
        elif cmd == 'Q':
            x1, y1 = float(tokens[i]) * scale + offset_x, float(tokens[i+1]) * scale + offset_y
            x, y = float(tokens[i+2]) * scale + offset_x, float(tokens[i+3]) * scale + offset_y
            for step in range(1, 21):
                u = step / 20.0
                px = (1-u)**2 * curr_x + 2*(1-u)*u * x1 + u**2 * x
                py = (1-u)**2 * curr_y + 2*(1-u)*u * y1 + u**2 * y
                current_subpath.append((px, py))
            curr_x, curr_y = x, y
            i += 4
        elif cmd in ('Z', 'z'):
            current_subpath.append((start_x, start_y))
            subpaths.append(current_subpath)
            current_subpath = []
            curr_x, curr_y = start_x, start_y
            i += 1
        else:
            i += 1

    if current_subpath:
        subpaths.append(current_subpath)
    return subpaths

def render_vector_file(xml_path, width, height, offset_x=0.0, offset_y=0.0, target_size=None):
    tree = ET.parse(xml_path)
    root = tree.getroot()
    viewport_w = float(root.get('{http://schemas.android.com/apk/res/android}viewportWidth', '108'))
    viewport_h = float(root.get('{http://schemas.android.com/apk/res/android}viewportHeight', '108'))

    target_w = target_size if target_size else width
    target_h = target_size if target_size else height

    scale_x = target_w / viewport_w
    scale_y = target_h / viewport_h
    scale = min(scale_x, scale_y)

    # 4x Supersampling for clean anti-aliasing
    ss = 4
    canvas_w, canvas_h = width * ss, height * ss
    scale_ss = scale * ss
    off_x_ss = offset_x * ss
    off_y_ss = offset_y * ss

    img = Image.new('RGBA', (canvas_w, canvas_h), (0, 0, 0, 0))

    for elem in root.findall('.//{http://schemas.android.com/apk/res/android}path') + root.findall('path'):
        path_data = elem.get('{http://schemas.android.com/apk/res/android}pathData') or elem.get('pathData')
        fill_hex = elem.get('{http://schemas.android.com/apk/res/android}fillColor') or elem.get('fillColor')
        stroke_hex = elem.get('{http://schemas.android.com/apk/res/android}strokeColor') or elem.get('strokeColor')
        stroke_width_val = elem.get('{http://schemas.android.com/apk/res/android}strokeWidth') or elem.get('strokeWidth')
        fill_type = elem.get('{http://schemas.android.com/apk/res/android}fillType') or elem.get('fillType') or 'nonZero'

        if path_data:
            subpaths = parse_svg_path(path_data, scale=scale_ss, offset_x=off_x_ss, offset_y=off_y_ss)

            if fill_hex:
                color = hex_to_rgba(fill_hex)
                if fill_type == 'evenOdd' and len(subpaths) > 1:
                    compound_mask = Image.new('L', (canvas_w, canvas_h), 0)
                    for sp in subpaths:
                        if len(sp) >= 3:
                            sp_mask = Image.new('L', (canvas_w, canvas_h), 0)
                            d = ImageDraw.Draw(sp_mask)
                            d.polygon(sp, fill=255)
                            compound_mask = ImageChops.logical_xor(compound_mask, sp_mask)
                    color_layer = Image.new('RGBA', (canvas_w, canvas_h), color)
                    img = Image.composite(color_layer, img, compound_mask)
                else:
                    for sp in subpaths:
                        if len(sp) >= 3:
                            layer = Image.new('RGBA', (canvas_w, canvas_h), (0, 0, 0, 0))
                            d = ImageDraw.Draw(layer)
                            d.polygon(sp, fill=color)
                            img = Image.alpha_composite(img, layer)

            if stroke_hex and stroke_width_val:
                stroke_color = hex_to_rgba(stroke_hex)
                stroke_w = float(stroke_width_val) * scale_ss
                for sp in subpaths:
                    if len(sp) >= 2:
                        layer = Image.new('RGBA', (canvas_w, canvas_h), (0, 0, 0, 0))
                        d = ImageDraw.Draw(layer)
                        d.line(sp, fill=stroke_color, width=int(round(stroke_w)))
                        img = Image.alpha_composite(img, layer)

    # Downsample using LANCZOS
    result = img.resize((width, height), Image.Resampling.LANCZOS)
    return result

def generate_icon_png():
    base = Image.new('RGBA', (512, 512), BACKGROUND_COLOR)
    fg = render_vector_file(FOREGROUND_XML, 512, 512, target_size=512)
    final_icon = Image.alpha_composite(base, fg)
    final_icon.save(ICON_PNG, 'PNG')
    print(f"Generated {ICON_PNG} (512x512)")

def generate_feature_graphic_png():
    canvas = Image.new('RGBA', (1024, 500), BACKGROUND_COLOR)

    # Render gauge silhouette on left side
    # Size 320x320 centered vertically at Y=90, X=120
    gauge_layer = render_vector_file(MONOCHROME_XML, 1024, 500, offset_x=120, offset_y=90, target_size=320)
    canvas = Image.alpha_composite(canvas, gauge_layer)

    # Render text "MinuteWatcher"
    draw = ImageDraw.Draw(canvas)
    try:
        font = ImageFont.truetype("arialbd.ttf", 72)
    except IOError:
        font = ImageFont.load_default()

    text = "MinuteWatcher"
    text_x = 480
    text_y = 210
    draw.text((text_x, text_y), text, fill=(255, 255, 255, 255), font=font)

    # Subtitle
    try:
        sub_font = ImageFont.truetype("arial.ttf", 28)
        draw.text((text_x, text_y + 90), "SIM Minutes & Cellular Data Tracker", fill=(180, 210, 240, 255), font=sub_font)
    except IOError:
        pass

    canvas.save(FEATURE_PNG, 'PNG')
    print(f"Generated {FEATURE_PNG} (1024x500)")

def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    generate_icon_png()
    generate_feature_graphic_png()

if __name__ == "__main__":
    main()
