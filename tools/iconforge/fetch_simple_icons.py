import os
import sys
import json
import requests

VERSION = "16.35.0"

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    json_path = os.path.join(script_dir, "simple_icons.json")
    version_path = os.path.join(script_dir, "simple_icons_version.txt")

    # Record exact version
    with open(version_path, "w", encoding="utf-8") as f:
        f.write(f"{VERSION}\n")

    print(f"Recorded simple-icons version {VERSION} to {version_path}")

    # Fetch Category A slugs SVG path data and store/update simple_icons.json
    slugs = [
        "airtel", "atandt", "jio", "movistar", "o2", "odido",
        "orange", "sunrise", "deutschetelekom", "telenor", "vodafone"
    ]

    # Load existing simple_icons.json if available
    icons_data = []
    if os.path.exists(json_path):
        try:
            with open(json_path, "r", encoding="utf-8") as f:
                icons_data = json.load(f)
        except Exception as e:
            print(f"Warning: could not read existing {json_path}: {e}")

    # Map existing items by title / slug
    item_map = {}
    for item in icons_data:
        title = item.get("title", "")
        slug = item.get("slug", title.lower())
        item_map[slug] = item

    print(f"Loaded {len(icons_data)} items from simple_icons.json")

    # Ensure required slugs have path data
    for slug in slugs:
        url = f"https://raw.githubusercontent.com/simple-icons/simple-icons/{VERSION}/icons/{slug}.svg"
        print(f"Fetching {slug} SVG from {url}...")
        try:
            r = requests.get(url, timeout=10)
            if r.status_code == 200:
                import re
                match = re.search(r'd="([^"]+)"', r.text)
                if match:
                    path_data = match.group(1)
                    if slug in item_map:
                        item_map[slug]["path"] = path_data
                    else:
                        item_map[slug] = {"title": slug, "slug": slug, "path": path_data}
                    print(f"Updated path for {slug} ({len(path_data)} chars)")
            else:
                print(f"Warning: HTTP {r.status_code} for {slug}")
        except Exception as e:
            print(f"Error fetching {slug}: {e}")

    updated_list = list(item_map.values())
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(updated_list, f, indent=4)

    print(f"Saved {len(updated_list)} items to {json_path}")
    print("fetch_simple_icons.py completed successfully.")

if __name__ == "__main__":
    main()
