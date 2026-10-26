import os
import io
import json
import tarfile
import requests
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen

DEB_URL = 'https://deb.debian.org/debian/pool/main/f/fonts-liberation/fonts-liberation_2.1.5-3_all.deb'

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    cache_dir = os.path.join(script_dir, "cache")
    os.makedirs(cache_dir, exist_ok=True)
    out_path = os.path.join(cache_dir, "liberation_sans_bold_glyphs.json")

    print(f"Fetching Liberation Sans Bold font from Debian pool ({DEB_URL})...")
    r = requests.get(DEB_URL, timeout=30)
    if r.status_code != 200:
        raise RuntimeError(f"Failed to download deb package: status {r.status_code}")

    deb_data = r.content
    pos = 8
    ttf_bytes = None
    while pos < len(deb_data):
        header = deb_data[pos:pos+60]
        if len(header) < 60:
            break
        size = int(header[48:58].strip())
        name = header[:16].strip().decode('ascii', errors='ignore')
        pos += 60
        content = deb_data[pos:pos+size]
        pos += size + (size % 2)
        if 'data.tar' in name:
            tf = tarfile.open(fileobj=io.BytesIO(content), mode='r:xz')
            for member in tf.getmembers():
                if 'LiberationSans-Bold.ttf' in member.name:
                    ttf_bytes = tf.extractfile(member).read()
                    break

    if not ttf_bytes:
        raise RuntimeError("LiberationSans-Bold.ttf not found in deb archive")

    print("Loaded LiberationSans-Bold.ttf. Extracting glyph outlines via fontTools...")
    font = TTFont(io.BytesIO(ttf_bytes))
    units_per_em = font['head'].unitsPerEm
    cmap = font.getBestCmap()
    glyph_set = font.getGlyphSet()

    glyphs_data = {}
    for char_code, glyph_name in cmap.items():
        if 32 <= char_code <= 126 or char_code in (1060, 1070):  # Basic ASCII + Cyrillic F, Yu
            char = chr(char_code)
            pen = SVGPathPen(glyph_set)
            glyph_set[glyph_name].draw(pen)
            path_data = pen.getCommands()
            width = font['hmtx'][glyph_name][0]
            glyphs_data[char] = {
                "glyph_name": glyph_name,
                "path": path_data,
                "width": width,
                "units_per_em": units_per_em
            }

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(glyphs_data, f, indent=2)

    print(f"Saved {len(glyphs_data)} glyphs to {out_path}")
    print("fetch_fonts.py completed successfully.")

if __name__ == "__main__":
    main()
