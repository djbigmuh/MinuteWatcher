import json
import os
import re

new_operators = [
    {
        "key": "operator_gamcel", "display": "Gamcel", "initials": "GAM", "color": "#008000", "category": "B", "slug": "",
        "tokens": ["Gamcel", "gamcel"], "plmns": ["60701"], "mcc": "607", "country": "Gambia", "source": "ITU-T E.212 / PURA Gambia"
    },
    {
        "key": "operator_africell_gm", "display": "Africell", "initials": "AGM", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["Africell Gambia", "africell gm"], "plmns": ["60702"], "mcc": "607", "country": "Gambia", "source": "ITU-T E.212 / PURA Gambia"
    },
    {
        "key": "operator_qcell", "display": "QCell", "initials": "QCELL", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["QCell", "qcell"], "plmns": ["60704"], "mcc": "607", "country": "Gambia", "source": "ITU-T E.212 / PURA Gambia"
    },
    {
        "key": "operator_mauritel", "display": "Mauritel", "initials": "MAUR", "color": "#008000", "category": "B", "slug": "",
        "tokens": ["Mauritel", "mauritel"], "plmns": ["60901"], "mcc": "609", "country": "Mauritania", "source": "ITU-T E.212 / ARE Mauritania"
    },
    {
        "key": "operator_chinguitel", "display": "Chinguitel", "initials": "CHING", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Chinguitel", "chinguitel"], "plmns": ["60902"], "mcc": "609", "country": "Mauritania", "source": "ITU-T E.212 / ARE Mauritania"
    },
    {
        "key": "operator_mattel", "display": "Mattel", "initials": "MATTEL", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["Mattel", "mattel"], "plmns": ["60910"], "mcc": "609", "country": "Mauritania", "source": "ITU-T E.212 / ARE Mauritania"
    },
    {
        "key": "operator_africell_sl", "display": "Africell", "initials": "ASL", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["Africell Sierra Leone", "africell sl"], "plmns": ["61902", "61903"], "mcc": "619", "country": "Sierra Leone", "source": "ITU-T E.212 / NATCA"
    },
    {
        "key": "operator_qcell_sl", "display": "QCell", "initials": "QSL", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["QCell Sierra Leone", "qcell sl"], "plmns": ["61907"], "mcc": "619", "country": "Sierra Leone", "source": "ITU-T E.212 / NATCA"
    },
    {
        "key": "operator_cv_movel", "display": "CVMóvel", "initials": "CVM", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["CVMóvel", "CVMovel", "cvmovel"], "plmns": ["62501"], "mcc": "625", "country": "Cape Verde", "source": "ITU-T E.212 / ARME Cape Verde"
    },
    {
        "key": "operator_unitel_t", "display": "Unitel T+", "initials": "UTP", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["Unitel T+", "unitel t+"], "plmns": ["62502"], "mcc": "625", "country": "Cape Verde", "source": "ITU-T E.212 / ARME Cape Verde"
    },
    {
        "key": "operator_cst", "display": "CST", "initials": "CST", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["CST", "cst sao tome"], "plmns": ["62601"], "mcc": "626", "country": "Sao Tome and Principe", "source": "ITU-T E.212 / AGER"
    },
    {
        "key": "operator_unitel_st", "display": "Unitel", "initials": "UST", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["Unitel Sao Tome", "unitel st"], "plmns": ["62602"], "mcc": "626", "country": "Sao Tome and Principe", "source": "ITU-T E.212 / AGER"
    },
    {
        "key": "operator_muni", "display": "Muni", "initials": "MUNI", "color": "#008000", "category": "B", "slug": "",
        "tokens": ["Muni", "muni"], "plmns": ["62703"], "mcc": "627", "country": "Equatorial Guinea", "source": "ITU-T E.212 / ORTEL"
    },
    {
        "key": "operator_guinetel", "display": "Guinetel", "initials": "GNT", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Guinetel", "guinetel"], "plmns": ["63201"], "mcc": "632", "country": "Guinea-Bissau", "source": "ITU-T E.212 / ARN"
    },
    {
        "key": "operator_cws", "display": "Cable & Wireless", "initials": "CWS", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Cable & Wireless", "cws seychelles"], "plmns": ["63301"], "mcc": "633", "country": "Seychelles", "source": "ITU-T E.212 / DICT Seychelles"
    },
    {
        "key": "operator_telcom_so", "display": "Telcom", "initials": "TSO", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Telcom Somalia", "telcom so"], "plmns": ["63704"], "mcc": "637", "country": "Somalia", "source": "ITU-T E.212 / NCA Somalia"
    },
    {
        "key": "operator_golis", "display": "Golis", "initials": "GOLIS", "color": "#008000", "category": "B", "slug": "",
        "tokens": ["Golis", "golis"], "plmns": ["63730"], "mcc": "637", "country": "Somalia", "source": "ITU-T E.212 / NCA Somalia"
    },
    {
        "key": "operator_hormuud", "display": "Hormuud", "initials": "HORM", "color": "#008000", "category": "B", "slug": "",
        "tokens": ["Hormuud", "hormuud"], "plmns": ["63750"], "mcc": "637", "country": "Somalia", "source": "ITU-T E.212 / NCA Somalia"
    },
    {
        "key": "operator_somtel", "display": "Somtel", "initials": "SOMTEL", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Somtel", "somtel"], "plmns": ["63771"], "mcc": "637", "country": "Somalia", "source": "ITU-T E.212 / NCA Somalia"
    },
    {
        "key": "operator_djibouti_telecom", "display": "Djibouti Telecom", "initials": "DJT", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Djibouti Telecom", "djibouti telecom"], "plmns": ["63801"], "mcc": "638", "country": "Djibouti", "source": "ITU-T E.212 / MCPT Djibouti"
    },
    {
        "key": "operator_smart_bi", "display": "Smart", "initials": "SMTBI", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Smart Burundi", "onatel burundi"], "plmns": ["64201"], "mcc": "642", "country": "Burundi", "source": "ITU-T E.212 / ARCT Burundi"
    },
    {
        "key": "operator_lumitel", "display": "Lumitel", "initials": "LUMI", "color": "#008000", "category": "B", "slug": "",
        "tokens": ["Lumitel", "lumitel"], "plmns": ["64207"], "mcc": "642", "country": "Burundi", "source": "ITU-T E.212 / ARCT Burundi"
    },
    {
        "key": "operator_eswatini_mobile", "display": "Eswatini Mobile", "initials": "ESM", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["Eswatini Mobile", "eswatini mobile"], "plmns": ["65301"], "mcc": "653", "country": "Eswatini", "source": "ITU-T E.212 / ESCCOM"
    },
    {
        "key": "operator_comores_telecom", "display": "Comores Telecom", "initials": "CTEL", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Comores Telecom", "comores telecom"], "plmns": ["65401"], "mcc": "654", "country": "Comoros", "source": "ITU-T E.212 / ANRTIC"
    },
    {
        "key": "operator_eritel", "display": "Eritel", "initials": "ERI", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Eritel", "eritel"], "plmns": ["65701"], "mcc": "657", "country": "Eritrea", "source": "ITU-T E.212 / MoC Eritrea"
    },
    {
        "key": "operator_gemtel", "display": "Gemtel", "initials": "GEM", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Gemtel", "gemtel"], "plmns": ["65903"], "mcc": "659", "country": "South Sudan", "source": "ITU-T E.212 / NCA South Sudan"
    },
    {
        "key": "operator_zamtel", "display": "Zamtel", "initials": "ZAM", "color": "#008000", "category": "B", "slug": "",
        "tokens": ["Zamtel", "zamtel"], "plmns": ["64503"], "mcc": "645", "country": "Zambia", "source": "ITU-T E.212 / ZICTA"
    },
    {
        "key": "operator_tn_mobile", "display": "TN Mobile", "initials": "TNMOB", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["TN Mobile", "tn mobile"], "plmns": ["64903"], "mcc": "649", "country": "Namibia", "source": "ITU-T E.212 / CRAN"
    },
    {
        "key": "operator_maore", "display": "Maoré Mobile", "initials": "MAORE", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Maoré Mobile", "maore mobile"], "plmns": ["64701"], "mcc": "647", "country": "Mayotte", "source": "ITU-T E.212 / ARCEP"
    },
    {
        "key": "operator_zeop", "display": "Zeop", "initials": "ZEOP", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["Zeop", "zeop"], "plmns": ["64704"], "mcc": "647", "country": "Reunion", "source": "ITU-T E.212 / ARCEP"
    },
    {
        "key": "operator_ttcl", "display": "TTCL", "initials": "TTCL", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["TTCL", "ttcl tanzania"], "plmns": ["64007"], "mcc": "640", "country": "Tanzania", "source": "ITU-T E.212 / TCRA"
    },
    {
        "key": "operator_faiba", "display": "Faiba", "initials": "FAIBA", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Faiba", "Jamii Telecom"], "plmns": ["63910"], "mcc": "639", "country": "Kenya", "source": "ITU-T E.212 / CA Kenya"
    },
    {
        "key": "operator_jambo", "display": "Jambo Telcoms", "initials": "JAMBO", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Jambo Telcoms", "jambo"], "plmns": ["63911"], "mcc": "639", "country": "Kenya", "source": "ITU-T E.212 / CA Kenya"
    },
    {
        "key": "operator_sabafon", "display": "Sabafon", "initials": "SABA", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["Sabafon", "sabafon"], "plmns": ["42101"], "mcc": "421", "country": "Yemen", "source": "ITU-T E.212 / MTIT Yemen"
    },
    {
        "key": "operator_y_yemen", "display": "Y", "initials": "YYEM", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["Y Yemen", "y telecom"], "plmns": ["42102"], "mcc": "421", "country": "Yemen", "source": "ITU-T E.212 / MTIT Yemen"
    },
    {
        "key": "operator_yemen_mobile", "display": "Yemen Mobile", "initials": "YEMEN", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["Yemen Mobile", "yemen mobile"], "plmns": ["42103"], "mcc": "421", "country": "Yemen", "source": "ITU-T E.212 / MTIT Yemen"
    },
    {
        "key": "operator_tm_cell", "display": "TM Cell", "initials": "TMC", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["TM Cell", "Altyn Asyr"], "plmns": ["43801"], "mcc": "438", "country": "Turkmenistan", "source": "ITU-T E.212 / Turkmenistan"
    }
]

existing_plmn_additions = {
    "61801": "operator_orange",
    "61807": "operator_orange",
    "61901": "operator_orange",
    "62301": "operator_moov",
    "62303": "operator_orange",
    "62701": "operator_orange",
    "63202": "operator_orange",
    "63310": "operator_airtel",
    "64282": "operator_econet",
    "65101": "operator_vodacom",
    "65102": "operator_econet",
    "65310": "operator_mtn",
    "65402": "operator_telma",
    "65902": "operator_mtn",
    "65906": "operator_zain",
    "43002": "operator_e_and",
    "43102": "operator_e_and"
}

def update_donottranslate(project_root):
    xml_path = os.path.join(project_root, "core", "resources", "src", "main", "res", "values", "donottranslate.xml")
    with open(xml_path, "r", encoding="utf-8") as f:
        text = f.read()

    new_strings = []
    for op in new_operators:
        k = op["key"]
        v = op["display"]
        tag = f'    <string name="{k}" translatable="false">{v}</string>'
        if tag not in text:
            new_strings.append(tag)

    if new_strings:
        idx = text.rfind("</resources>")
        updated_text = text[:idx] + "\n".join(new_strings) + "\n" + text[idx:]
        with open(xml_path, "w", encoding="utf-8") as f:
            f.write(updated_text)
        print(f"Added {len(new_strings)} strings to {xml_path}")

def update_operator_db(project_root):
    db_path = os.path.join(project_root, "core", "android", "src", "main", "java", "host", "serv", "djbigmuh", "minutewatcher", "core", "android", "util", "OperatorBrandDatabase.kt")
    with open(db_path, "r", encoding="utf-8") as f:
        text = f.read()

    new_entries = []
    for op in new_operators:
        k = op["key"]
        entry = f'            "{k}" to R.string.{k},'
        if entry not in text:
            new_entries.append(entry)

    if new_entries:
        target = "        )"
        idx = text.rfind(target)
        updated_text = text[:idx] + "\n".join(new_entries) + "\n" + text[idx:]
        with open(db_path, "w", encoding="utf-8") as f:
            f.write(updated_text)
        print(f"Added {len(new_entries)} entries to {db_path}")

def update_sim_utils(project_root):
    sim_path = os.path.join(project_root, "core", "android", "src", "main", "java", "host", "serv", "djbigmuh", "minutewatcher", "core", "android", "util", "SimUtils.kt")
    with open(sim_path, "r", encoding="utf-8") as f:
        text = f.read()

    new_entries = []
    for op in new_operators:
        k = op["key"]
        entry = f'            R.string.{k} to R.drawable.ic_{k},'
        if entry not in text:
            new_entries.append(entry)

    if new_entries:
        target = "        )"
        idx = sim_path_idx = text.find(target)
        updated_text = text[:idx] + "\n".join(new_entries) + "\n" + text[idx:]
        with open(sim_path, "w", encoding="utf-8") as f:
            f.write(updated_text)
        print(f"Added {len(new_entries)} entries to {sim_path}")

def update_tokens(project_root):
    tokens_path = os.path.join(project_root, "core", "android", "src", "main", "java", "host", "serv", "djbigmuh", "minutewatcher", "core", "android", "util", "OperatorBrandTokens.kt")
    with open(tokens_path, "r", encoding="utf-8") as f:
        text = f.read()

    new_entries = []
    for op in new_operators:
        k = op["key"]
        for t in op["tokens"]:
            t_clean = t.lower()
            entry = f'            "{t_clean}" to "{k}",'
            if entry not in text:
                new_entries.append(entry)

    if new_entries:
        target = "        )"
        idx = text.find(target)
        updated_text = text[:idx] + "\n".join(new_entries) + "\n" + text[idx:]
        with open(tokens_path, "w", encoding="utf-8") as f:
            f.write(updated_text)
        print(f"Added {len(new_entries)} tokens to {tokens_path}")

def update_brands_tsv(script_dir):
    tsv_path = os.path.join(script_dir, "brands.tsv")
    with open(tsv_path, "r", encoding="utf-8") as f:
        text = f.read()

    existing_keys = set(re.findall(r'^(operator_[a-z0-9_]+)\t', text, re.MULTILINE))
    new_rows = []
    for op in new_operators:
        k = op["key"]
        if k not in existing_keys:
            new_rows.append(f"{k}\t{op['display']}\t{op['category']}\t{op['slug']}\t{op['color']}\t{op['initials']}")

    if new_rows:
        with open(tsv_path, "a", encoding="utf-8") as f:
            for r in new_rows:
                f.write(r + "\n")
        print(f"Appended {len(new_rows)} rows to {tsv_path}")

def update_mcc_mnc_db(project_root, script_dir):
    json_path = os.path.join(project_root, "core", "android", "src", "main", "assets", "mcc_mnc_db.json")
    sources_path = os.path.join(project_root, "docs", "operator_plmn_sources.md")

    with open(json_path, "r", encoding="utf-8") as f:
        db = json.load(f)

    for op in new_operators:
        k = op["key"]
        for p in op["plmns"]:
            db[p] = k

    for p, k in existing_plmn_additions.items():
        db[p] = k

    sorted_db = {k: db[k] for k in sorted(db.keys())}

    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(sorted_db, f, indent=4)

    print(f"Updated {json_path} (total {len(sorted_db)} PLMNs)")

    from update_plmn_db import sync_sources_doc
    sync_sources_doc(sorted_db, sources_path)

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(script_dir, "..", ".."))

    update_donottranslate(project_root)
    update_operator_db(project_root)
    update_sim_utils(project_root)
    update_tokens(project_root)
    update_brands_tsv(script_dir)
    update_mcc_mnc_db(project_root, script_dir)

    print("add_africa_me_operators.py completed successfully.")

if __name__ == "__main__":
    main()
