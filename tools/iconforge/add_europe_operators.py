import json
import os
import re

new_operators = [
    {
        "key": "operator_monaco_telecom",
        "display": "Monaco Telecom",
        "initials": "MT",
        "color": "#E2001A",
        "category": "B",
        "slug": "",
        "tokens": ["Monaco Telecom", "monacotelecom"],
        "plmns": ["21201", "21210"],
        "mcc": "212",
        "country": "Monaco",
        "source": "ITU-T E.212 / Monaco"
    },
    {
        "key": "operator_andorra_telecom",
        "display": "Andorra Telecom",
        "initials": "ANDOR",
        "color": "#E30613",
        "category": "B",
        "slug": "",
        "tokens": ["Andorra Telecom", "andorratelecom", "somandorra"],
        "plmns": ["21303"],
        "mcc": "213",
        "country": "Andorra",
        "source": "ITU-T E.212 / Andorra"
    },
    {
        "key": "operator_gibtelecom",
        "display": "Gibtelecom",
        "initials": "GIB",
        "color": "#003399",
        "category": "B",
        "slug": "",
        "tokens": ["Gibtelecom", "gibtelecom", "gibtel"],
        "plmns": ["26601"],
        "mcc": "266",
        "country": "Gibraltar",
        "source": "ITU-T E.212 / Gibraltar"
    },
    {
        "key": "operator_foroya_tele",
        "display": "Føroya Tele",
        "initials": "FT",
        "color": "#E30613",
        "category": "B",
        "slug": "",
        "tokens": ["Føroya Tele", "Foroya Tele", "ftele"],
        "plmns": ["28801"],
        "mcc": "288",
        "country": "Faroe Islands",
        "source": "ITU-T E.212 / Faroe Islands"
    },
    {
        "key": "operator_tosa",
        "display": "Tosa",
        "initials": "TOSA",
        "color": "#008000",
        "category": "B",
        "slug": "",
        "tokens": ["Tosa", "tosa", "hey", "kall"],
        "plmns": ["28802", "28803"],
        "mcc": "288",
        "country": "Faroe Islands",
        "source": "ITU-T E.212 / Faroe Islands"
    },
    {
        "key": "operator_tusass",
        "display": "tusass",
        "initials": "TUSASS",
        "color": "#0055A5",
        "category": "B",
        "slug": "",
        "tokens": ["tusass", "TELE Greenland"],
        "plmns": ["29001"],
        "mcc": "290",
        "country": "Greenland",
        "source": "ITU-T E.212 / Greenland"
    },
    {
        "key": "operator_prima_sm",
        "display": "Prima",
        "initials": "PRIMA",
        "color": "#003399",
        "category": "B",
        "slug": "",
        "tokens": ["Prima", "Telecom Italia San Marino"],
        "plmns": ["29201"],
        "mcc": "292",
        "country": "San Marino",
        "source": "ITU-T E.212 / San Marino"
    },
    {
        "key": "operator_fl1",
        "display": "FL1",
        "initials": "FL1",
        "color": "#0055A5",
        "category": "B",
        "slug": "",
        "tokens": ["FL1", "Telecom Liechtenstein"],
        "plmns": ["29501", "29505"],
        "mcc": "295",
        "country": "Liechtenstein",
        "source": "ITU-T E.212 / Liechtenstein"
    },
    {
        "key": "operator_melita",
        "display": "Melita",
        "initials": "MELITA",
        "color": "#0055A5",
        "category": "B",
        "slug": "",
        "tokens": ["Melita", "melita"],
        "plmns": ["27877"],
        "mcc": "278",
        "country": "Malta",
        "source": "ITU-T E.212 / MCA Malta"
    },
    {
        "key": "operator_primetel",
        "display": "PrimeTel",
        "initials": "PRIME",
        "color": "#003399",
        "category": "B",
        "slug": "",
        "tokens": ["PrimeTel", "primetel"],
        "plmns": ["28020"],
        "mcc": "280",
        "country": "Cyprus",
        "source": "ITU-T E.212 / Cyprus"
    },
    {
        "key": "operator_t2_si",
        "display": "T-2",
        "initials": "T2",
        "color": "#0055A5",
        "category": "B",
        "slug": "",
        "tokens": ["T-2", "t2 slovenija"],
        "plmns": ["29364"],
        "mcc": "293",
        "country": "Slovenia",
        "source": "ITU-T E.212 / AKOS Slovenia"
    },
    {
        "key": "operator_unite_md",
        "display": "Unité",
        "initials": "UNITE",
        "color": "#0055A5",
        "category": "B",
        "slug": "",
        "tokens": ["Unité", "Unite", "Moldtelecom"],
        "plmns": ["25903", "25905"],
        "mcc": "259",
        "country": "Moldova",
        "source": "ITU-T E.212 / ANRCETI Moldova"
    },
    {
        "key": "operator_alcom",
        "display": "Alcom",
        "initials": "ALCOM",
        "color": "#0055A5",
        "category": "B",
        "slug": "",
        "tokens": ["Alcom", "Åland Mobil"],
        "plmns": ["24414"],
        "mcc": "244",
        "country": "Finland",
        "source": "ITU-T E.212 / Traficom"
    },
    {
        "key": "operator_jt",
        "display": "JT",
        "initials": "JT",
        "color": "#0055A5",
        "category": "B",
        "slug": "",
        "tokens": ["JT", "Jersey Telecom"],
        "plmns": ["23450"],
        "mcc": "234",
        "country": "United Kingdom",
        "source": "ITU-T E.212 / Ofcom UK"
    },
    {
        "key": "operator_sure",
        "display": "Sure",
        "initials": "SURE",
        "color": "#0055A5",
        "category": "B",
        "slug": "",
        "tokens": ["Sure", "Sure Guernsey", "Sure Jersey"],
        "plmns": ["23455", "23436"],
        "mcc": "234",
        "country": "United Kingdom",
        "source": "ITU-T E.212 / Ofcom UK"
    },
    {
        "key": "operator_manx",
        "display": "Manx Telecom",
        "initials": "MANX",
        "color": "#0055A5",
        "category": "B",
        "slug": "",
        "tokens": ["Manx Telecom", "manx"],
        "plmns": ["23458"],
        "mcc": "234",
        "country": "United Kingdom",
        "source": "ITU-T E.212 / Ofcom UK"
    },
    {
        "key": "operator_airtel_vodafone",
        "display": "Airtel-Vodafone",
        "initials": "AV",
        "color": "#E60000",
        "category": "B",
        "slug": "",
        "tokens": ["Airtel-Vodafone", "Airtel Vodafone"],
        "plmns": ["23403"],
        "mcc": "234",
        "country": "United Kingdom",
        "source": "ITU-T E.212 / Ofcom UK"
    }
]

def update_brands_tsv(script_dir):
    tsv_path = os.path.join(script_dir, "brands.tsv")
    with open(tsv_path, "r", encoding="utf-8") as f:
        lines = [line.strip().split("\t") for line in f if line.strip()]

    header = lines[0]
    new_rows = [header]

    overrides = {op["key"]: op for op in new_operators}

    for row in lines[1:]:
        key = row[0]
        if key in overrides:
            op = overrides[key]
            new_rows.append([key, op["display"], op["category"], op["slug"], op["color"], op["initials"]])
        else:
            new_rows.append(row)

    with open(tsv_path, "w", encoding="utf-8") as f:
        for r in new_rows:
            f.write("\t".join(r) + "\n")

    print(f"Updated brands in {tsv_path}")

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    update_brands_tsv(script_dir)

if __name__ == "__main__":
    main()
