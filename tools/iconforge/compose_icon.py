import os
import sys
import re
import json
import xml.etree.ElementTree as ET

OCTAGON_PLATE_PATH = "M 2.5 13 L 2.5 35 L 13 45.5 L 35 45.5 L 45.5 35 L 45.5 13 L 35 2.5 L 13 2.5 Z"

def path_to_absolute(path_data):
    tokens = re.findall(r'([a-zA-Z])|([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)', path_data)
    curr_x, curr_y = 0.0, 0.0
    start_x, start_y = 0.0, 0.0

    abs_tokens = []
    idx = 0
    cmd = None

    while idx < len(tokens):
        t, val = tokens[idx]
        if t:
            cmd = t
            idx += 1
        elif val:
            pass

        if cmd == 'M':
            x, y = float(tokens[idx][1]), float(tokens[idx+1][1])
            idx += 2
            curr_x, curr_y = x, y
            start_x, start_y = x, y
            abs_tokens.append(f"M {x:g} {y:g}")
            cmd = 'L'
        elif cmd == 'm':
            dx, dy = float(tokens[idx][1]), float(tokens[idx+1][1])
            idx += 2
            curr_x += dx
            curr_y += dy
            start_x, start_y = curr_x, curr_y
            abs_tokens.append(f"M {curr_x:g} {curr_y:g}")
            cmd = 'l'
        elif cmd == 'L':
            x, y = float(tokens[idx][1]), float(tokens[idx+1][1])
            idx += 2
            curr_x, curr_y = x, y
            abs_tokens.append(f"L {x:g} {y:g}")
        elif cmd == 'l':
            dx, dy = float(tokens[idx][1]), float(tokens[idx+1][1])
            idx += 2
            curr_x += dx
            curr_y += dy
            abs_tokens.append(f"L {curr_x:g} {curr_y:g}")
        elif cmd == 'H':
            x = float(tokens[idx][1])
            idx += 1
            curr_x = x
            abs_tokens.append(f"H {x:g}")
        elif cmd == 'h':
            dx = float(tokens[idx][1])
            idx += 1
            curr_x += dx
            abs_tokens.append(f"H {curr_x:g}")
        elif cmd == 'V':
            y = float(tokens[idx][1])
            idx += 1
            curr_y = y
            abs_tokens.append(f"V {y:g}")
        elif cmd == 'v':
            dy = float(tokens[idx][1])
            idx += 1
            curr_y += dy
            abs_tokens.append(f"V {curr_y:g}")
        elif cmd == 'C':
            x1, y1 = float(tokens[idx][1]), float(tokens[idx+1][1])
            x2, y2 = float(tokens[idx+2][1]), float(tokens[idx+3][1])
            x, y = float(tokens[idx+4][1]), float(tokens[idx+5][1])
            idx += 6
            curr_x, curr_y = x, y
            abs_tokens.append(f"C {x1:g} {y1:g} {x2:g} {y2:g} {x:g} {y:g}")
        elif cmd == 'c':
            dx1, dy1 = float(tokens[idx][1]), float(tokens[idx+1][1])
            dx2, dy2 = float(tokens[idx+2][1]), float(tokens[idx+3][1])
            dx, dy = float(tokens[idx+4][1]), float(tokens[idx+5][1])
            idx += 6
            x1, y1 = curr_x + dx1, curr_y + dy1
            x2, y2 = curr_x + dx2, curr_y + dy2
            x, y = curr_x + dx, curr_y + dy
            curr_x, curr_y = x, y
            abs_tokens.append(f"C {x1:g} {y1:g} {x2:g} {y2:g} {x:g} {y:g}")
        elif cmd in ('Q', 'S'):
            x1, y1 = float(tokens[idx][1]), float(tokens[idx+1][1])
            x, y = float(tokens[idx+2][1]), float(tokens[idx+3][1])
            idx += 4
            curr_x, curr_y = x, y
            abs_tokens.append(f"{cmd} {x1:g} {y1:g} {x:g} {y:g}")
        elif cmd in ('q', 's'):
            dx1, dy1 = float(tokens[idx][1]), float(tokens[idx+1][1])
            dx, dy = float(tokens[idx+2][1]), float(tokens[idx+3][1])
            idx += 4
            x1, y1 = curr_x + dx1, curr_y + dy1
            x, y = curr_x + dx, curr_y + dy
            curr_x, curr_y = x, y
            abs_cmd = 'Q' if cmd == 'q' else 'S'
            abs_tokens.append(f"{abs_cmd} {x1:g} {y1:g} {x:g} {y:g}")
        elif cmd in ('A', 'a'):
            rx, ry, xrot, laf, sf = [float(tokens[idx+i][1]) for i in range(5)]
            if cmd == 'A':
                x, y = float(tokens[idx+5][1]), float(tokens[idx+6][1])
            else:
                x, y = curr_x + float(tokens[idx+5][1]), curr_y + float(tokens[idx+6][1])
            idx += 7
            curr_x, curr_y = x, y
            abs_tokens.append(f"A {rx:g} {ry:g} {xrot:g} {int(laf)} {int(sf)} {x:g} {y:g}")
        elif cmd in ('Z', 'z'):
            curr_x, curr_y = start_x, start_y
            abs_tokens.append("Z")
            idx += 1 if idx < len(tokens) and not tokens[idx][0] else 0
        else:
            idx += 1

    return " ".join(abs_tokens)

def parse_path_bounds(path_data):
    abs_path = path_to_absolute(path_data)
    tokens = re.findall(r'([a-zA-Z])|([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)', abs_path)
    min_x, max_x = float('inf'), float('-inf')
    min_y, max_y = float('inf'), float('-inf')

    idx = 0
    cmd = None
    while idx < len(tokens):
        t, val = tokens[idx]
        if t:
            cmd = t
            idx += 1
        elif val:
            pass

        if cmd in ('M', 'L', 'T', 'Q', 'S'):
            x, y = float(tokens[idx][1]), float(tokens[idx+1][1])
            idx += 2 if cmd in ('M', 'L', 'T') else 4
            min_x, max_x = min(min_x, x), max(max_x, x)
            min_y, max_y = min(min_y, y), max(max_y, y)
        elif cmd == 'H':
            x = float(tokens[idx][1])
            idx += 1
            min_x, max_x = min(min_x, x), max(max_x, x)
        elif cmd == 'V':
            y = float(tokens[idx][1])
            idx += 1
            min_y, max_y = min(min_y, y), max(max_y, y)
        elif cmd == 'C':
            x1, y1 = float(tokens[idx][1]), float(tokens[idx+1][1])
            x2, y2 = float(tokens[idx+2][1]), float(tokens[idx+3][1])
            x, y = float(tokens[idx+4][1]), float(tokens[idx+5][1])
            idx += 6
            for px, py in [(x1,y1), (x2,y2), (x,y)]:
                min_x, max_x = min(min_x, px), max(max_x, px)
                min_y, max_y = min(min_y, py), max(max_y, py)
        elif cmd == 'A':
            x, y = float(tokens[idx+5][1]), float(tokens[idx+6][1])
            idx += 7
            min_x, max_x = min(min_x, x), max(max_x, x)
            min_y, max_y = min(min_y, y), max(max_y, y)
        elif cmd == 'Z':
            idx += 1 if idx < len(tokens) and not tokens[idx][0] else 0
        else:
            idx += 1

    return min_x, min_y, max_x, max_y

def scale_and_center_path_to_fit(path_data, target_center_x=24.0, target_center_y=24.0, target_w=30.0, target_h=24.0):
    abs_path = path_to_absolute(path_data)
    min_x, min_y, max_x, max_y = parse_path_bounds(abs_path)
    w = max_x - min_x
    h = max_y - min_y
    if w <= 0 or h <= 0:
        return abs_path

    scale = min(target_w / w, target_h / h)

    curr_cx = (min_x + max_x) / 2.0
    curr_cy = (min_y + max_y) / 2.0

    tx = target_center_x - curr_cx * scale
    ty = target_center_y - curr_cy * scale

    tokens = re.findall(r'([a-zA-Z])|([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)', abs_path)
    res = []
    is_x = True

    for t, val in tokens:
        if t:
            res.append(t)
            is_x = True if t in ('M', 'm', 'L', 'l', 'C', 'c', 'Q', 'q', 'S', 's', 'H', 'h') else False
        elif val:
            n = float(val)
            scaled_val = n * scale + (tx if is_x else ty)
            scaled_val = max(2.5, min(45.5, scaled_val))
            r_val = round(scaled_val, 2)
            s_val = f"{r_val:.2f}".rstrip('0').rstrip('.')
            res.append(s_val if s_val else "0")
            is_x = not is_x

    return " ".join(res)

def compose_operator_xml(brand_key, display_name, category, slug, brand_color, initials, font_glyphs, simple_icons):
    if not brand_color.startswith('#'):
        brand_color = '#' + brand_color
    if len(brand_color) == 7:
        fg_color = '#FF' + brand_color[1:].upper()
    elif len(brand_color) == 9:
        fg_color = brand_color.upper()
    else:
        fg_color = '#FF0055A5'

    bg_color = "#FFFFFFFF"
    if brand_key == "operator_mtn":
        bg_color = "#FF141218"
        fg_color = "#FFFFCC00"

    xml_lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="48dp"',
        '    android:height="48dp"',
        '    android:viewportWidth="48"',
        '    android:viewportHeight="48">'
    ]

    xml_lines.append('    <path')
    xml_lines.append(f'        android:fillColor="{bg_color}"')
    xml_lines.append(f'        android:pathData="{OCTAGON_PLATE_PATH}" />')

    if category == 'B' and initials and font_glyphs:
        char_paths = []
        curr_x_off = 0
        for ch in initials:
            if ch in font_glyphs:
                g = font_glyphs[ch]
                pdata = g['path']
                w = g['width']

                tokens = re.findall(r'([a-zA-Z])|([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)', pdata)
                flipped_tokens = []
                is_x = True
                for t, val in tokens:
                    if t:
                        flipped_tokens.append(t)
                        is_x = True if t in ('M', 'm', 'L', 'l', 'C', 'c', 'Q', 'q', 'S', 's', 'H', 'h') else False
                    elif val:
                        n = float(val)
                        if is_x:
                            n += curr_x_off
                            flipped_tokens.append(f"{n:g}")
                        else:
                            flipped_tokens.append(f"{-n:g}")
                        is_x = not is_x

                char_paths.append(" ".join(flipped_tokens))
                curr_x_off += w

        if char_paths:
            raw_combined = " ".join(char_paths)

            if len(initials) >= 5:
                t_w, t_h = 32.0, 18.0
            elif len(initials) == 4:
                t_w, t_h = 30.0, 20.0
            elif len(initials) == 3:
                t_w, t_h = 29.0, 22.0
            elif len(initials) == 2:
                t_w, t_h = 27.0, 24.0
            else:
                t_w, t_h = 20.0, 26.0

            scaled_glyph = scale_and_center_path_to_fit(raw_combined, target_center_x=24.0, target_center_y=24.0, target_w=t_w, target_h=t_h)

            xml_lines.append('    <path')
            xml_lines.append(f'        android:fillColor="{fg_color}"')
            xml_lines.append('        android:fillType="evenOdd"')
            xml_lines.append(f'        android:pathData="{scaled_glyph}" />')

    xml_lines.append('</vector>')
    return "\n".join(xml_lines) + "\n"

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(script_dir, "..", ".."))
    drawable_dir = os.path.join(project_root, "core", "resources", "src", "main", "res", "drawable")

    tsv_path = os.path.join(script_dir, "brands.tsv")
    glyphs_path = os.path.join(script_dir, "cache", "liberation_sans_bold_glyphs.json")
    simple_icons_path = os.path.join(script_dir, "simple_icons.json")

    font_glyphs = {}
    if os.path.exists(glyphs_path):
        with open(glyphs_path, "r", encoding="utf-8") as f:
            font_glyphs = json.load(f)

    simple_icons = {}
    if os.path.exists(simple_icons_path):
        with open(simple_icons_path, "r", encoding="utf-8") as f:
            s_data = json.load(f)
            for item in s_data:
                slug = item.get("slug", item.get("title", "").lower())
                simple_icons[slug] = item

    if not os.path.exists(tsv_path):
        print(f"Error: {tsv_path} not found.")
        sys.exit(1)

    print(f"Reading brands from {tsv_path}...")
    with open(tsv_path, "r", encoding="utf-8") as f:
        lines = [line.strip().split("\t") for line in f if line.strip()]

    header = lines[0]
    rows = lines[1:]

    regenerated_count = 0
    for row in rows:
        brand_key = row[0]
        display_name = row[1]
        category = row[2]
        slug = row[3]
        brand_color = row[4]
        initials = row[5]

        # ONLY regenerate Category B icons!
        if category == 'B':
            xml_content = compose_operator_xml(brand_key, display_name, category, slug, brand_color, initials, font_glyphs, simple_icons)
            out_file = os.path.join(drawable_dir, f"ic_{brand_key}.xml")
            with open(out_file, "w", encoding="utf-8") as f:
                f.write(xml_content)
            regenerated_count += 1

    print(f"Regenerated {regenerated_count} Category B operator icons in {drawable_dir}.")

if __name__ == "__main__":
    main()
