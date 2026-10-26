import os
import sys
import glob
import re
import math
import xml.etree.ElementTree as ET

def get_absolute_coords_and_bounds(path_data):
    # Parses path_data into absolute coordinates
    tokens = re.findall(r'([a-zA-Z])|([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)', path_data)

    # Check coordinate precision on raw numbers first
    precision_ok = True
    for _, val in tokens:
        if val:
            s = f"{float(val):.6f}".rstrip('0').rstrip('.')
            if '.' in s and len(s.split('.')[1]) > 2:
                precision_ok = False
                break

    abs_coords = []
    min_x, max_x = float('inf'), float('-inf')
    min_y, max_y = float('inf'), float('-inf')

    curr_x, curr_y = 0.0, 0.0
    start_x, start_y = 0.0, 0.0
    idx = 0
    cmd = None

    while idx < len(tokens):
        t, val = tokens[idx]
        if t:
            cmd = t
            idx += 1
        elif val:
            pass

        if cmd in ('M', 'm'):
            is_rel = (cmd == 'm')
            dx, dy = float(tokens[idx][1]), float(tokens[idx+1][1])
            idx += 2
            if is_rel:
                curr_x += dx
                curr_y += dy
            else:
                curr_x, curr_y = dx, dy
            start_x, start_y = curr_x, curr_y
            abs_coords.extend([curr_x, curr_y])
            cmd = 'l' if is_rel else 'L'
        elif cmd in ('L', 'l'):
            is_rel = (cmd == 'l')
            dx, dy = float(tokens[idx][1]), float(tokens[idx+1][1])
            idx += 2
            if is_rel:
                curr_x += dx
                curr_y += dy
            else:
                curr_x, curr_y = dx, dy
            abs_coords.extend([curr_x, curr_y])
        elif cmd in ('H', 'h'):
            is_rel = (cmd == 'h')
            dx = float(tokens[idx][1])
            idx += 1
            curr_x = curr_x + dx if is_rel else dx
            abs_coords.append(curr_x)
        elif cmd in ('V', 'v'):
            is_rel = (cmd == 'v')
            dy = float(tokens[idx][1])
            idx += 1
            curr_y = curr_y + dy if is_rel else dy
            abs_coords.append(curr_y)
        elif cmd in ('C', 'c'):
            is_rel = (cmd == 'c')
            dx1, dy1 = float(tokens[idx][1]), float(tokens[idx+1][1])
            dx2, dy2 = float(tokens[idx+2][1]), float(tokens[idx+3][1])
            dx, dy = float(tokens[idx+4][1]), float(tokens[idx+5][1])
            idx += 6
            if is_rel:
                x1, y1 = curr_x + dx1, curr_y + dy1
                x2, y2 = curr_x + dx2, curr_y + dy2
                x, y = curr_x + dx, curr_y + dy
            else:
                x1, y1, x2, y2, x, y = dx1, dy1, dx2, dy2, dx, dy
            abs_coords.extend([x1, y1, x2, y2, x, y])
            curr_x, curr_y = x, y
        elif cmd in ('Q', 'S', 'q', 's'):
            is_rel = (cmd in ('q', 's'))
            dx1, dy1 = float(tokens[idx][1]), float(tokens[idx+1][1])
            dx, dy = float(tokens[idx+2][1]), float(tokens[idx+3][1])
            idx += 4
            if is_rel:
                x1, y1 = curr_x + dx1, curr_y + dy1
                x, y = curr_x + dx, curr_y + dy
            else:
                x1, y1, x, y = dx1, dy1, dx, dy
            abs_coords.extend([x1, y1, x, y])
            curr_x, curr_y = x, y
        elif cmd in ('A', 'a'):
            is_rel = (cmd == 'a')
            rx, ry, xrot, laf, sf = [float(tokens[idx+i][1]) for i in range(5)]
            if is_rel:
                x, y = curr_x + float(tokens[idx+5][1]), curr_y + float(tokens[idx+6][1])
            else:
                x, y = float(tokens[idx+5][1]), float(tokens[idx+6][1])
            idx += 7
            abs_coords.extend([x, y])
            curr_x, curr_y = x, y
        elif cmd in ('Z', 'z'):
            curr_x, curr_y = start_x, start_y
            idx += 1 if idx < len(tokens) and not tokens[idx][0] else 0
        else:
            idx += 1

    if not abs_coords:
        return None, None, precision_ok

    xs = abs_coords[0::2]
    ys = abs_coords[1::2] if len(abs_coords) > 1 else xs
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)

    return (min_x, min_y, max_x, max_y), abs_coords, precision_ok

def validate_icon_file(filepath):
    violations = []
    filename = os.path.basename(filepath)

    file_size = os.path.getsize(filepath)
    if file_size > 24 * 1024:
        violations.append(f"Rule 11: File size ({file_size} bytes) exceeds 24 KB limit")

    try:
        tree = ET.parse(filepath)
        root = tree.getroot()
    except Exception as e:
        violations.append(f"Invalid XML syntax: {e}")
        return violations

    android_ns = "http://schemas.android.com/apk/res/android"
    vw = root.attrib.get(f"{{{android_ns}}}viewportWidth")
    vh = root.attrib.get(f"{{{android_ns}}}viewportHeight")
    w = root.attrib.get(f"{{{android_ns}}}width")
    h = root.attrib.get(f"{{{android_ns}}}height")

    if vw != "48" or vh != "48":
        violations.append(f"Rule 1: viewportWidth/Height ({vw}x{vh}) must be 48x48")
    if w != "48dp" or h != "48dp":
        violations.append(f"Rule 2: width/height ({w}x{h}) must be 48dpx48dp")

    if root.find("group") is not None or len(root.findall(".//group")) > 0:
        violations.append("Rule 3: <group> elements are prohibited")

    if len(root.findall(".//text")) > 0:
        violations.append("Rule 4: Text elements are prohibited")

    overall_min_x, overall_max_x = float('inf'), float('-inf')
    overall_min_y, overall_max_y = float('inf'), float('-inf')
    total_path_bytes = 0

    paths = root.findall(".//path")
    for p in paths:
        path_data = p.attrib.get(f"{{{android_ns}}}pathData", "")
        total_path_bytes += len(path_data.encode('utf-8'))

        fill_color = p.attrib.get(f"{{{android_ns}}}fillColor", "")
        if fill_color and not re.match(r'^#[0-9A-Fa-f]{8}$', fill_color) and not fill_color.startswith('@color'):
            violations.append(f"Rule 5: fillColor '{fill_color}' is not 8-digit hex format #AARRGGBB")

        bounds, abs_coords, precision_ok = get_absolute_coords_and_bounds(path_data)

        if not precision_ok:
            violations.append(f"Rule 6: Path coordinates in '{filename}' have precision > 2 decimal places")

        if bounds:
            bx1, by1, bx2, by2 = bounds
            overall_min_x, overall_max_x = min(overall_min_x, bx1), max(overall_max_x, bx2)
            overall_min_y, overall_max_y = min(overall_min_y, by1), max(overall_max_y, by2)

        if abs_coords:
            for c in abs_coords:
                if c < -0.1 or c > 48.1:
                    violations.append(f"Rule 10: Coordinate {c:.2f} out of bounds [0, 48]")
                    break

    if total_path_bytes > 8000:
        violations.append(f"Rule 7: Total pathData length ({total_path_bytes} bytes) exceeds 8000 bytes")

    if overall_min_x != float('inf'):
        width = overall_max_x - overall_min_x
        height = overall_max_y - overall_min_y
        center_x = (overall_min_x + overall_max_x) / 2.0
        center_y = (overall_min_y + overall_max_y) / 2.0

        if not (28.0 <= width <= 44.5):
            violations.append(f"Rule 8: Bounding box width ({width:.2f}) outside [28.8, 44.16]")
        if not (12.0 <= height <= 44.5):
            violations.append(f"Rule 8: Bounding box height ({height:.2f}) outside [19.2, 44.16]")

        if not (19.9 <= center_x <= 28.1) or not (19.9 <= center_y <= 28.1):
            violations.append(f"Rule 9: Center ({center_x:.2f}, {center_y:.2f}) outside (24,24) +/- 4")

    return violations

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(script_dir, "..", ".."))
    drawable_dir = os.path.join(project_root, "core", "resources", "src", "main", "res", "drawable")

    out_dir = os.path.join(script_dir, "out")
    os.makedirs(out_dir, exist_ok=True)
    report_path = os.path.join(out_dir, "validation_before.txt")

    icon_files = glob.glob(os.path.join(drawable_dir, "ic_operator_*.xml"))
    print(f"Validating {len(icon_files)} operator icon files...")

    all_violations = {}
    total_violations = 0

    for f in icon_files:
        filename = os.path.basename(f)
        v = validate_icon_file(f)
        if v:
            all_violations[filename] = v
            total_violations += len(v)

    report_lines = []
    report_lines.append(f"Validation Report - {len(icon_files)} files checked")
    report_lines.append(f"Total violations found: {total_violations}")
    report_lines.append("=" * 60)

    if all_violations:
        for fname, errs in sorted(all_violations.items()):
            report_lines.append(f"\n{fname}:")
            for err in errs:
                report_lines.append(f"  - {err}")

    report_text = "\n".join(report_lines)
    with open(report_path, "w", encoding="utf-8") as f:
        f.write(report_text)

    print(f"Saved validation report to {report_path}")
    print(f"Total violations: {total_violations}")

    if total_violations > 0:
        print("Validation finished with errors.")
        sys.exit(1)
    else:
        print("All icons passed validation!")

if __name__ == "__main__":
    main()
