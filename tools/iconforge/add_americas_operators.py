import json
import os
import re

new_operators = [
    {
        "key": "operator_spm_telecom", "display": "SPM Télécom", "initials": "SPM", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["SPM Telecom", "spm telecom"], "plmns": ["30801"], "mcc": "308", "country": "Saint Pierre and Miquelon", "source": "ITU-T E.212 / ARCEP"
    },
    {
        "key": "operator_one_bm", "display": "One", "initials": "ONEBM", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["One Bermuda", "one bermuda"], "plmns": ["350000", "35002"], "mcc": "350", "country": "Bermuda", "source": "ITU-T E.212 / RA Bermuda"
    },
    {
        "key": "operator_telcell", "display": "Telcell", "initials": "TELC", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Telcell", "telcell"], "plmns": ["36278"], "mcc": "362", "country": "Curaçao", "source": "ITU-T E.212 / BTTP"
    },
    {
        "key": "operator_chippie", "display": "Chippie", "initials": "CHIP", "color": "#008000", "category": "B", "slug": "",
        "tokens": ["Chippie", "uts curacao"], "plmns": ["36291"], "mcc": "362", "country": "Curaçao", "source": "ITU-T E.212 / BTTP"
    },
    {
        "key": "operator_btc_bs", "display": "BTC", "initials": "BTCBS", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["BTC Bahamas", "btc bs"], "plmns": ["36439", "364390"], "mcc": "364", "country": "Bahamas", "source": "ITU-T E.212 / URCA Bahamas"
    },
    {
        "key": "operator_aliv", "display": "Aliv", "initials": "ALIV", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["Aliv", "aliv bahamas"], "plmns": ["36449", "364490"], "mcc": "364", "country": "Bahamas", "source": "ITU-T E.212 / URCA Bahamas"
    },
    {
        "key": "operator_smart_bz", "display": "Smart", "initials": "SMRT", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Smart Speednet", "smart bz"], "plmns": ["70267"], "mcc": "702", "country": "Belize", "source": "ITU-T E.212 / PUC Belize"
    },
    {
        "key": "operator_digi_bz", "display": "Digi", "initials": "DIGIBZ", "color": "#008000", "category": "B", "slug": "",
        "tokens": ["Digi Belize", "btl bz"], "plmns": ["70269"], "mcc": "702", "country": "Belize", "source": "ITU-T E.212 / PUC Belize"
    },
    {
        "key": "operator_gtt", "display": "GTT", "initials": "GTT", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["GTT Guyana", "cellink"], "plmns": ["73802"], "mcc": "738", "country": "Guyana", "source": "ITU-T E.212 / PURA Guyana"
    },
    {
        "key": "operator_telesur", "display": "Telesur", "initials": "TELE", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Telesur", "telesur suriname"], "plmns": ["74602"], "mcc": "746", "country": "Suriname", "source": "ITU-T E.212 / TAS Suriname"
    },
    {
        "key": "operator_ite", "display": "IT&E", "initials": "ITE", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["IT&E Guam", "ite guam"], "plmns": ["310032"], "mcc": "310", "country": "Guam", "source": "ITU-T E.212 / FCC"
    },
    {
        "key": "operator_gta", "display": "GTA", "initials": "GTA", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["GTA Guam", "gta teleGuam"], "plmns": ["310033"], "mcc": "310", "country": "Guam", "source": "ITU-T E.212 / FCC"
    },
    {
        "key": "operator_docomo_pacific", "display": "Docomo Pacific", "initials": "DOCPAC", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["Docomo Pacific", "docomo pacific"], "plmns": ["310370", "310470"], "mcc": "310", "country": "Guam / CNMI", "source": "ITU-T E.212 / FCC"
    },
    {
        "key": "operator_cspire", "display": "C Spire", "initials": "CSPIRE", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["C Spire", "cspire"], "plmns": ["311230"], "mcc": "311", "country": "United States", "source": "ITU-T E.212 / FCC"
    },
    {
        "key": "operator_gci", "display": "GCI", "initials": "GCI", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["GCI Alaska", "gci"], "plmns": ["311370"], "mcc": "311", "country": "United States", "source": "ITU-T E.212 / FCC"
    }
]

existing_plmn_additions = {
    "34001": "operator_orange",
    "34002": "operator_sfr",
    "34020": "operator_digicel",
    "35001": "operator_digicel",
    "356110": "operator_flow",
    "35650": "operator_digicel",
    "36269": "operator_digicel",
    "73801": "operator_digicel",
    "74603": "operator_digicel",
    "310980": "operator_att"
}

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(script_dir, "..", ".."))

    from add_africa_me_operators import update_donottranslate, update_operator_db, update_sim_utils, update_tokens, update_brands_tsv, update_mcc_mnc_db

    update_donottranslate(project_root)
    update_operator_db(project_root)
    update_sim_utils(project_root)
    update_tokens(project_root)
    update_brands_tsv(script_dir)
    update_mcc_mnc_db(project_root, script_dir)

    print("add_americas_operators.py completed successfully.")

if __name__ == "__main__":
    main()
