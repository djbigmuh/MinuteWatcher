import json
import os
import re

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(script_dir, "..", ".."))
    json_path = os.path.join(project_root, "core", "android", "src", "main", "assets", "mcc_mnc_db.json")
    sources_path = os.path.join(project_root, "docs", "operator_plmn_sources.md")

    with open(json_path, "r", encoding="utf-8") as f:
        db = json.load(f)

    # Table A updates & deletions
    db["61002"] = "operator_orange"
    db["61603"] = "operator_mtn"
    if "64011" in db: del db["64011"]
    db["62803"] = "operator_airtel"
    if "62804" in db: del db["62804"]
    db["50210"] = "operator_digi"
    db["25050"] = "operator_mts"
    db["24004"] = "operator_telenor"
    db["24207"] = "operator_ice"
    db["71401"] = "operator_flow"
    if "71404" in db: del db["71404"]
    db["22603"] = "operator_orange"
    db["22606"] = "operator_orange"
    db["64803"] = "operator_telecel"
    if "302320" in db: del db["302320"]

    # Table B additions
    table_b = {
        "61001": "operator_moov",
        "61602": "operator_moov",
        "64003": "operator_zantel",
        "62803": "operator_airtel",
        "63086": "operator_orange",
        "62201": "operator_airtel",
        "27402": "operator_vodafone",
        "27403": "operator_vodafone",
        "27408": "operator_siminn",
        "27431": "operator_siminn",
        "24404": "operator_dna",
        "24406": "operator_elisa",
        "24421": "operator_elisa",
        "22002": "operator_yettel",
        "23104": "operator_telekom",
        "23150": "operator_telekom",
        "23105": "operator_orange",
        "23107": "operator_orange",
        "23115": "operator_orange",
        "23204": "operator_magenta",
        "23206": "operator_three",
        "23214": "operator_three",
        "23211": "operator_a1",
        "23212": "operator_a1",
        "23810": "operator_tdc",
        "23877": "operator_telenor",
        "23896": "operator_telia",
        "26217": "operator_o2",
        "26220": "operator_o2",
        "26277": "operator_o2",
        "26278": "operator_telekom",
        "23407": "operator_vodafone",
        "23486": "operator_ee",
        "25567": "operator_kyivstar",
        "25568": "operator_kyivstar",
        "28205": "operator_silknet",
        "28208": "operator_silknet",
        "26807": "operator_nos",
        "26893": "operator_nos",
        "26808": "operator_meo",
        "27830": "operator_go",
        "28002": "operator_cyta",
        "45402": "operator_csl",
        "45416": "operator_csl",
        "45418": "operator_csl",
        "45420": "operator_csl",
        "45429": "operator_csl",
        "45413": "operator_china_mobile",
        "45428": "operator_china_mobile",
        "45415": "operator_smartone",
        "45417": "operator_smartone",
        "45404": "operator_three_hk",
        "45414": "operator_three_hk",
        "72408": "operator_tim",
        "72439": "operator_claro",
        "73007": "operator_movistar",
        "73010": "operator_entel",
        "722007": "operator_movistar",
        "732102": "operator_movistar",
        "732020": "operator_tigo",
        "732142": "operator_tigo",
        "40404": "operator_vi",
        "40422": "operator_vi",
        "40478": "operator_vi",
        "40468": "operator_bsnl",
        "40469": "operator_bsnl",
        "502198": "operator_celcom",
        "25012": "operator_tele2",
        "25054": "operator_tattelecom",
    }

    for k, v in table_b.items():
        db[k] = v

    # Sort DB keys numerically/alphabetically by PLMN ascending
    sorted_db = {k: db[k] for k in sorted(db.keys())}

    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(sorted_db, f, indent=4)

    print(f"Saved {len(sorted_db)} PLMN entries to {json_path}")

    # Synchronize docs/operator_plmn_sources.md
    sync_sources_doc(sorted_db, sources_path)

def sync_sources_doc(sorted_db, sources_path):
    # Group DB by MCC
    mcc_map = {}
    for plmn, brand in sorted_db.items():
        mcc = plmn[:3]
        if mcc not in mcc_map:
            mcc_map[mcc] = []
        mcc_map[mcc].append(f"{plmn}={brand}")

    # Read sources_path
    with open(sources_path, "r", encoding="utf-8") as f:
        lines = f.readlines()

    new_lines = []
    in_table = False

    for line in lines:
        if line.startswith("|") and not line.startswith("| MCC |") and not line.startswith("|---"):
            parts = [p.strip() for p in line.split("|")]
            if len(parts) >= 5 and len(parts[1]) == 3 and parts[1].isdigit():
                mcc = parts[1]
                country = parts[2]
                src = parts[4]
                retrieved = parts[5] if len(parts) >= 6 else "2026-10-22"

                if mcc in mcc_map:
                    plmn_str = ", ".join(mcc_map[mcc])
                    new_line = f"| {mcc} | {country:<32} | {plmn_str:<200} | {src:<48} | {retrieved} |\n"
                    new_lines.append(new_line)
                    del mcc_map[mcc]
                else:
                    # MCC removed from DB
                    pass
            else:
                new_lines.append(line)
        else:
            new_lines.append(line)

    # If any new MCC was added to DB that wasn't in doc, append rows
    if mcc_map:
        for mcc in sorted(mcc_map.keys()):
            plmn_str = ", ".join(mcc_map[mcc])
            new_lines.append(f"| {mcc} | Country {mcc:<24} | {plmn_str:<200} | ITU-T E.212                                    | 2026-10-22 |\n")

    with open(sources_path, "w", encoding="utf-8") as f:
        f.writelines(new_lines)

    print(f"Synchronized {sources_path}")

if __name__ == "__main__":
    main()
