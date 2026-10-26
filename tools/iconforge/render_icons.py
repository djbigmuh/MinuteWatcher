import os
import sys
import glob
import re
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw, ImageFont

def draw_vector_icon(xml_file, size=96):
    # Renders simple VectorDrawable (octagon plate + fill paths) to PIL Image
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    scale = size / 48.0

    try:
        tree = ET.parse(xml_file)
        root = tree.getroot()
        android_ns = "http://schemas.android.com/apk/res/android"

        for path_elem in root.findall(".//path"):
            fill_color_str = path_elem.attrib.get(f"{{{android_ns}}}fillColor", "#FF000000")
            path_data = path_elem.attrib.get(f"{{{android_ns}}}pathData", "")

            # Parse color #AARRGGBB
            if fill_color_str.startswith("#") and len(fill_color_str) == 9:
                a = int(fill_color_str[1:3], 16)
                r = int(fill_color_str[3:5], 16)
                g = int(fill_color_str[5:7], 16)
                b = int(fill_color_str[7:9], 16)
                color = (r, g, b, a)
            else:
                color = (0, 0, 0, 255)

            # Simple polygon draw for M/L lines
            tokens = re.findall(r'([a-zA-Z])|([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)', path_data)
            points = []
            idx = 0
            curr_cmd = None

            while idx < len(tokens):
                t, val = tokens[idx]
                if t:
                    curr_cmd = t
                    idx += 1
                elif val:
                    if curr_cmd in ('M', 'L'):
                        x = float(tokens[idx][1]) * scale
                        y = float(tokens[idx+1][1]) * scale
                        points.append((x, y))
                        idx += 2
                    else:
                        idx += 1

            if len(points) >= 3:
                draw.polygon(points, fill=color)

    except Exception as e:
        pass

    return img

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(script_dir, "..", ".."))
    drawable_dir = os.path.join(project_root, "core", "resources", "src", "main", "res", "drawable")
    out_preview_dir = os.path.join(script_dir, "out", "preview")
    os.makedirs(out_preview_dir, exist_ok=True)

    tsv_path = os.path.join(script_dir, "brands.tsv")
    if not os.path.exists(tsv_path):
        print(f"Error: {tsv_path} not found.")
        sys.exit(1)

    with open(tsv_path, "r", encoding="utf-8") as f:
        lines = [line.strip().split("\t") for line in f if line.strip()]

    header = lines[0]
    brands = lines[1:]

    cols = 8
    rows = (len(brands) + cols - 1) // cols
    cell_w, cell_h = 120, 100
    margin = 10

    sheet_w = cols * cell_w + 2 * margin
    sheet_h = rows * cell_h + 2 * margin

    contact_sheet = Image.new("RGBA", (sheet_w, sheet_h), (240, 240, 240, 255))
    draw = ImageDraw.Draw(contact_sheet)

    font = ImageFont.load_default()

    print(f"Rendering contact sheet for {len(brands)} icons...")

    for i, b in enumerate(brands):
        brand_key = b[0]
        display_name = b[1] if len(b) > 1 else brand_key

        col = i % cols
        row = i // cols

        x = margin + col * cell_w
        y = margin + row * cell_h

        # Draw cell background box
        draw.rectangle([x, y, x + cell_w - 4, y + cell_h - 4], fill=(255, 255, 255, 255), outline=(200, 200, 200, 255))

        # Render icon
        xml_file = os.path.join(drawable_dir, f"ic_{brand_key}.xml")
        if os.path.exists(xml_file):
            icon_img = draw_vector_icon(xml_file, size=48)
            contact_sheet.paste(icon_img, (x + (cell_w - 48) // 2, y + 8), icon_img)

        # Draw brand key text
        label = brand_key.replace("operator_", "")
        draw.text((x + 6, y + 62), label[:14], fill=(0, 0, 0, 255), font=font)

    out_file = os.path.join(out_preview_dir, "contact_sheet.png")
    contact_sheet.save(out_file)
    print(f"Saved contact sheet to {out_file}")
    print("render_icons.py completed successfully.")

if __name__ == "__main__":
    main()
