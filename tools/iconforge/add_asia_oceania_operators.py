import json
import os
import re

new_operators = [
    {
        "key": "operator_bhutan_telecom", "display": "Bhutan Telecom", "initials": "BTL", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Bhutan Telecom", "b-mobile"], "plmns": ["40211"], "mcc": "402", "country": "Bhutan", "source": "ITU-T E.212 / BICMA"
    },
    {
        "key": "operator_tashicell", "display": "TashiCell", "initials": "TASHI", "color": "#008000", "category": "B", "slug": "",
        "tokens": ["TashiCell", "tashicell"], "plmns": ["40217"], "mcc": "402", "country": "Bhutan", "source": "ITU-T E.212 / BICMA"
    },
    {
        "key": "operator_smartcell", "display": "SmartCell", "initials": "SMC", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["SmartCell Nepal", "smartcell"], "plmns": ["42904"], "mcc": "429", "country": "Nepal", "source": "ITU-T E.212 / NTA Nepal"
    },
    {
        "key": "operator_telkomcel", "display": "Telkomcel", "initials": "TMCEL", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["Telkomcel", "telkomcel"], "plmns": ["51401"], "mcc": "514", "country": "Timor-Leste", "source": "ITU-T E.212 / ANC Timor"
    },
    {
        "key": "operator_timor_telecom", "display": "Timor Telecom", "initials": "TT", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Timor Telecom", "timor telecom"], "plmns": ["51402"], "mcc": "514", "country": "Timor-Leste", "source": "ITU-T E.212 / ANC Timor"
    },
    {
        "key": "operator_telemor", "display": "Telemor", "initials": "TELEM", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["Telemor", "telemor"], "plmns": ["51403"], "mcc": "514", "country": "Timor-Leste", "source": "ITU-T E.212 / ANC Timor"
    },
    {
        "key": "operator_tcc", "display": "TCC", "initials": "TCC", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Tonga Communications", "tcc tonga"], "plmns": ["53901"], "mcc": "539", "country": "Tonga", "source": "ITU-T E.212 / TRC Tonga"
    },
    {
        "key": "operator_our_telekom", "display": "Our Telekom", "initials": "OUR", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Our Telekom", "our telekom"], "plmns": ["54001"], "mcc": "540", "country": "Solomon Islands", "source": "ITU-T E.212 / SPTC"
    },
    {
        "key": "operator_bmobile_sb", "display": "bモバイル", "initials": "BMSB", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["BMobile Solomon", "bmobile sb"], "plmns": ["54002"], "mcc": "540", "country": "Solomon Islands", "source": "ITU-T E.212 / SPTC"
    },
    {
        "key": "operator_spm_wf", "display": "SPM", "initials": "SPM", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["SPM Wallis", "spt wf"], "plmns": ["54301"], "mcc": "543", "country": "Wallis and Futuna", "source": "ITU-T E.212 / ARCEP"
    },
    {
        "key": "operator_bluesky_as", "display": "BlueSky", "initials": "BSAS", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Bluesky American Samoa", "bluesky as"], "plmns": ["54411"], "mcc": "544", "country": "American Samoa", "source": "ITU-T E.212 / FCC"
    },
    {
        "key": "operator_kiribati_frigate", "display": "Frigate", "initials": "FRIG", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Frigate Kiribati", "ath kiribati"], "plmns": ["54509"], "mcc": "545", "country": "Kiribati", "source": "ITU-T E.212 / CCK Kiribati"
    },
    {
        "key": "operator_opt_nc", "display": "OPT NC", "initials": "OPTNC", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["OPT Nouvelle-Caledonie", "opt nc"], "plmns": ["54601"], "mcc": "546", "country": "New Caledonia", "source": "ITU-T E.212 / OPT"
    },
    {
        "key": "operator_vodafone_pf", "display": "Vodafone PF", "initials": "VPF", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["Vodafone Polynesie", "vodafone pf"], "plmns": ["54715"], "mcc": "547", "country": "French Polynesia", "source": "ITU-T E.212 / HCPS"
    },
    {
        "key": "operator_viti", "display": "Viti", "initials": "VITI", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Viti", "viti"], "plmns": ["54720"], "mcc": "547", "country": "French Polynesia", "source": "ITU-T E.212 / HCPS"
    },
    {
        "key": "operator_bluesky_ci", "display": "BlueSky", "initials": "BSCI", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Bluesky Cook Islands", "bluesky ci"], "plmns": ["54801"], "mcc": "548", "country": "Cook Islands", "source": "ITU-T E.212 / Telecom Cook"
    },
    {
        "key": "operator_bluesky_ws", "display": "BlueSky", "initials": "BSWS", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Bluesky Samoa", "bluesky ws"], "plmns": ["54901"], "mcc": "549", "country": "Samoa", "source": "ITU-T E.212 / OROT"
    },
    {
        "key": "operator_fsm_telecom", "display": "FSM Telecom", "initials": "FSM", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["FSM Telecom", "fsmtelecom"], "plmns": ["55001"], "mcc": "550", "country": "Micronesia", "source": "ITU-T E.212 / FSMTC"
    },
    {
        "key": "operator_pncc", "display": "PNCC", "initials": "PNCC", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["PNCC Palau", "pncc"], "plmns": ["55201"], "mcc": "552", "country": "Palau", "source": "ITU-T E.212 / PNCC"
    },
    {
        "key": "operator_ttc_tv", "display": "Tuvalu Telecom", "initials": "TTC", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Tuvalu Telecom", "tuvalutelecom"], "plmns": ["55301"], "mcc": "553", "country": "Tuvalu", "source": "ITU-T E.212 / TDT"
    },
    {
        "key": "operator_telecom_niue", "display": "Telecom Niue", "initials": "NIUE", "color": "#0055A5", "category": "B", "slug": "",
        "tokens": ["Telecom Niue", "telecom niue"], "plmns": ["55501"], "mcc": "555", "country": "Niue", "source": "ITU-T E.212 / Telecom Niue"
    },
    {
        "key": "operator_tpg_sg", "display": "TPG", "initials": "TPGSG", "color": "#E60000", "category": "B", "slug": "",
        "tokens": ["TPG Singapore", "simba sg"], "plmns": ["52510"], "mcc": "525", "country": "Singapore", "source": "ITU-T E.212 / IMDA Singapore"
    },
    {
        "key": "operator_unifi", "display": "unifi", "initials": "UNIFI", "color": "#003399", "category": "B", "slug": "",
        "tokens": ["unifi Mobile", "telekom malaysia"], "plmns": ["50211", "50214"], "mcc": "502", "country": "Malaysia", "source": "ITU-T E.212 / MCMC Malaysia"
    },
    {
        "key": "operator_yes_my", "display": "Yes", "initials": "YESMY", "color": "#660099", "category": "B", "slug": "",
        "tokens": ["Yes YTL", "ytl communications"], "plmns": ["502152"], "mcc": "502", "country": "Malaysia", "source": "ITU-T E.212 / MCMC Malaysia"
    }
]

existing_plmn_additions = {
    "53988": "operator_digicel",
    "54927": "operator_digicel"
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

    print("add_asia_oceania_operators.py completed successfully.")

if __name__ == "__main__":
    main()
