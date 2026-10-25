# PLMN Completeness Audit Report

## Executive Summary

- **Project PLMN Database Size:** 1285 entries (`core/android/src/main/assets/mcc_mnc_db.json`)
- **Audit Date:** 2026-10-25
- **Reference Dataset:** P1sec MCC_MNC (`p1_mnc.json`) cross-referenced with ITU-T E.212 and national regulator registries

### Audit Totals

| Category | Count | Description |
|---|---|---|
| **MISSING (INCLUDE_MNO)** | 290 | Active public mobile network operators missing in project DB |
| **MISSING (INCLUDE_MVNO_OWN_PLMN)** | 104 | Active MVNOs with dedicated PLMN missing in project DB |
| **PROJECT_ONLY** | 234 | PLMNs present in project DB but inactive or absent in reference dataset |
| **CONFLICT** | 81 | PLMNs present in both sets but mapped to different operator brands |
| **EXCLUDE_SPECIAL** | 172 | Special purpose networks (GSM-R, maritime, satellite, test) |
| **EXCLUDE_DEFUNCT** | 1755 | Decommissioned/defunct network codes |
| **EXCLUDE_MVNO_SHARED_PLMN** | 56 | Shared PLMN MVNOs (addressed via SIM subscription name) |

## Decision Rules

During integration and resolution of audit findings, the following decision rules apply:
1. **Reassignment / Reallocation:** If ITU-T E.212 or national regulator confirms a PLMN has been reassigned to a new operator, update the mapping in `mcc_mnc_db.json`.
2. **Decommissioned PLMN:** If a PLMN is verified as withdrawn from service and unassigned, remove it from `mcc_mnc_db.json`.
3. **Unconfirmed / Legacy Active:** If verification against official regulator sources confirms an active allocation not listed in P1sec, maintain the entry in `mcc_mnc_db.json`.
4. **Shared PLMN MVNOs:** Do not add separate PLMN entries for MVNOs using host MNO PLMNs; rely on carrier name matching per `docs/operators.md`.

## Missing Operational PLMNs (Candidates for Addition)

| PLMN | Country | MCC | Brand | Operator | Category | Suggested BrandKey | Source | Date |
|---|---|---|---|---|---|---|---|---|
| `20403` | Netherlands | 204 | Enreach | Enreach Netherlands B.V. | `INCLUDE_MNO` | `operator_enreach` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20407` | Netherlands | 204 | Teleena | Tata Communications MOVE B.V. | `INCLUDE_MVNO_OWN_PLMN` | `operator_teleena` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20411` | Netherlands | 204 |  | Greenet Netwerk B.V | `INCLUDE_MNO` | `operator_greenet_netwerk_b_v` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20412` | Netherlands | 204 | Telfort | KPN Mobile The Netherlands B.V. | `INCLUDE_MVNO_OWN_PLMN` | `operator_telfort` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20415` | Netherlands | 204 | Ziggo | Ziggo B.V. | `INCLUDE_MNO` | `operator_ziggo` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20418` | Netherlands | 204 | Ziggo | Ziggo Services B.V. | `INCLUDE_MVNO_OWN_PLMN` | `operator_ziggo` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20425` | Netherlands | 204 |  | CapX B.V. | `INCLUDE_MNO` | `operator_capx_b_v` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20427` | Netherlands | 204 | L-mobi | L-Mobi Mobile B.V. | `INCLUDE_MVNO_OWN_PLMN` | `operator_l_mobi` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20462` | Netherlands | 204 | RGTN | RGTN Wholesale Netherlands B.V. | `INCLUDE_MVNO_OWN_PLMN` | `operator_rgtn` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20466` | Netherlands | 204 |  | Utility Connect B.V. | `INCLUDE_MNO` | `operator_utility_connect_b_v` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20603` | Belgium | 206 | Citymesh Connect | Citymesh NV | `INCLUDE_MNO` | `operator_citymesh_connect` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20605` | Belgium | 206 | Telenet | Telenet | `INCLUDE_MVNO_OWN_PLMN` | `operator_telenet` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20608` | Belgium | 206 | VOO | Nethys [fr] | `INCLUDE_MVNO_OWN_PLMN` | `operator_voo` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20803` | France | 208 | Sierra Wireless | Sierra Wireless France | `INCLUDE_MVNO_OWN_PLMN` | `operator_sierra_wireless` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20812` | France | 208 | Truphone | TP France | `INCLUDE_MVNO_OWN_PLMN` | `operator_truphone` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20819` | France | 208 |  | Haute-Garonne numérique | `INCLUDE_MNO` | `operator_haute_garonne_num_rique` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20823` | France | 208 |  | Syndicat mixte ouvert Charente Numérique | `INCLUDE_MNO` | `operator_syndicat_mixte_ouvert_charente_num_rique` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20824` | France | 208 | Sierra Wireless | Sierra Wireless France | `INCLUDE_MVNO_OWN_PLMN` | `operator_sierra_wireless` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20826` | France | 208 | NRJ Mobile | Bouygues Telecom - Distribution | `INCLUDE_MVNO_OWN_PLMN` | `operator_nrj_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20828` | France | 208 | AIF | Airmob Infra Full | `INCLUDE_MVNO_OWN_PLMN` | `operator_aif` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `20896` | France | 208 |  | Région Bourgogne-Franche-Comté | `INCLUDE_MNO` | `operator_r_gion_bourgogne_franche_comt` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `21402` | Spain | 214 | Fibracat | Fibracat Telecom SLU | `INCLUDE_MNO` | `operator_fibracat` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `21414` | Spain | 214 |  | AVATEL MÓVIL, S.L.U. | `INCLUDE_MNO` | `operator_avatel_m_vil_s_l_u` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `21427` | Spain | 214 | Truphone | SCN Truphone, S.L. | `INCLUDE_MVNO_OWN_PLMN` | `operator_truphone` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `21429` | Spain | 214 |  | Xfera Moviles S.A.U. | `INCLUDE_MNO` | `operator_xfera_moviles_s_a_u` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `21434` | Spain | 214 |  | Aire Networks del Mediterráneo, S.L.U. | `INCLUDE_MNO` | `operator_aire_networks_del_mediterr_neo_s_l_u` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `21602` | Hungary | 216 |  | HM EI Zrt. | `INCLUDE_MNO` | `operator_hm_ei_zrt` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `22107` | Kosovo | 221 | D3 Mobile | Dukagjini Telecommunications LLC | `INCLUDE_MVNO_OWN_PLMN` | `operator_d3_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `22234` | Italy | 222 | BT Italia | BT Italia | `INCLUDE_MVNO_OWN_PLMN` | `operator_bt_italia` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `22238` | Italy | 222 | LINKEM | OpNet S.p.A. | `INCLUDE_MNO` | `operator_linkem` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `22241` | Italy | 222 | GO internet | GO internet S.p.A. | `INCLUDE_MNO` | `operator_go_internet` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `22254` | Italy | 222 | Plintron |  | `INCLUDE_MVNO_OWN_PLMN` | `operator_plintron` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `22808` | Switzerland | 228 | Tele4u | Sunrise Communications AG | `INCLUDE_MNO` | `operator_tele4u` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `22853` | Switzerland | 228 | upc cablecom | Sunrise UPC GmbH | `INCLUDE_MVNO_OWN_PLMN` | `operator_upc_cablecom` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `22858` | Switzerland | 228 | beeone | Beeone Communications SA | `INCLUDE_MVNO_OWN_PLMN` | `operator_beeone` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `22863` | Switzerland | 228 | FTS | Fink Telecom Services | `INCLUDE_MNO` | `operator_fts` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23005` | Czech Republic | 230 |  | PODA a.s. | `INCLUDE_MNO` | `operator_poda_a_s` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23006` | Czech Republic | 230 |  | Nordic Telecom 5G a.s. | `INCLUDE_MNO` | `operator_nordic_telecom_5g_a_s` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23292` | Austria | 232 | ArgoNET | ArgoNET GmbH | `INCLUDE_MNO` | `operator_argonet` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23400` | United Kingdom | 234 | BT | BT Group | `INCLUDE_MNO` | `operator_bt` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23401` | United Kingdom | 234 | Vectone Mobile | Mundio Mobile Limited | `INCLUDE_MVNO_OWN_PLMN` | `operator_vectone_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23408` | United Kingdom | 234 | BT OnePhone | BT OnePhone (UK) Ltd | `INCLUDE_MVNO_OWN_PLMN` | `operator_bt_onephone` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23414` | United Kingdom | 234 |  | Link Mobility UK Ltd | `INCLUDE_MNO` | `operator_link_mobility_uk_ltd` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23416` | United Kingdom | 234 | Talk Talk | TalkTalk Communications Limited | `INCLUDE_MVNO_OWN_PLMN` | `operator_talk_talk` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23418` | United Kingdom | 234 | Cloud9 | Wireless Logic Limited | `INCLUDE_MVNO_OWN_PLMN` | `operator_cloud9` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23419` | United Kingdom | 234 | PMN | Teleware plc | `INCLUDE_MNO` | `operator_pmn` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23424` | United Kingdom | 234 | Greenfone | Stour Marine Limited | `INCLUDE_MVNO_OWN_PLMN` | `operator_greenfone` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23425` | United Kingdom | 234 | Truphone | Truphone | `INCLUDE_MVNO_OWN_PLMN` | `operator_truphone` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23427` | United Kingdom | 234 | Teleena | Tata Communications Move UK Ltd | `INCLUDE_MVNO_OWN_PLMN` | `operator_teleena` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23451` | United Kingdom | 234 | Relish | UK Broadband Limited | `INCLUDE_MNO` | `operator_relish` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23453` | United Kingdom | 234 | Mobile-X | Tango Networks UK Ltd | `INCLUDE_MVNO_OWN_PLMN` | `operator_mobile_x` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23454` | United Kingdom | 234 | iD Mobile | The Carphone Warehouse Limited | `INCLUDE_MVNO_OWN_PLMN` | `operator_id_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23472` | United Kingdom | 234 | Hanhaa Mobile | Hanhaa Limited | `INCLUDE_MVNO_OWN_PLMN` | `operator_hanhaa_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23473` | Isle of Man | 234 |  | Bluewave Communications Ltd. | `INCLUDE_MNO` | `operator_bluewave_communications_ltd` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23473` | United Kingdom | 234 |  | Bluewave Communications Ltd | `INCLUDE_MNO` | `operator_bluewave_communications_ltd` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23476` | United Kingdom | 234 | BT | BT Group | `INCLUDE_MNO` | `operator_bt` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23478` | United Kingdom | 234 | Airwave | Airwave Solutions Ltd | `INCLUDE_MNO` | `operator_airwave` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23488` | United Kingdom | 234 | telet | Telet Research (N.I.) Limited | `INCLUDE_MNO` | `operator_telet` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23588` | United Kingdom | 235 | telet | Telet Research (N.I.) Limited | `INCLUDE_MNO` | `operator_telet` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23805` | Denmark | 238 | TetraNet | Dansk Beredskabskommunikation A/S | `INCLUDE_MNO` | `operator_tetranet` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23808` | Denmark | 238 | Voxbone | Voxbone mobile | `INCLUDE_MVNO_OWN_PLMN` | `operator_voxbone` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23809` | Denmark | 238 | SINE | Dansk Beredskabskommunikation A/S | `INCLUDE_MNO` | `operator_sine` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23811` | Denmark | 238 | SINE | Dansk Beredskabskommunikation A/S | `INCLUDE_MNO` | `operator_sine` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23815` | Denmark | 238 | Net 1 | Cibicom | `INCLUDE_MNO` | `operator_net_1` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23842` | Denmark | 238 | Wavely | Greenwave Mobile IoT ApS | `INCLUDE_MVNO_OWN_PLMN` | `operator_wavely` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23866` | Denmark | 238 |  | TT-Netværket P/S | `INCLUDE_MNO` | `operator_tt_netv_rket_p_s` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `23873` | Denmark | 238 | Onomondo | Onomondo ApS | `INCLUDE_MVNO_OWN_PLMN` | `operator_onomondo` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24003` | Sweden | 240 | Net 1 | Teracom AB | `INCLUDE_MNO` | `operator_net_1` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24016` | Sweden | 240 |  | 42 Telecom AB | `INCLUDE_MNO` | `operator_42_telecom_ab` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24017` | Sweden | 240 | Gotanet | Götalandsnätet AB | `INCLUDE_MVNO_OWN_PLMN` | `operator_gotanet` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24019` | Sweden | 240 | Vectone Mobile | Mundio Mobile (Sweden) Limited | `INCLUDE_MVNO_OWN_PLMN` | `operator_vectone_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24024` | Sweden | 240 | Sweden 2G | Net4Mobility HB | `INCLUDE_MNO` | `operator_sweden_2g` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24038` | Sweden | 240 | Voxbone | Voxbone mobile | `INCLUDE_MVNO_OWN_PLMN` | `operator_voxbone` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24063` | Sweden | 240 | FTS | Fink Telecom Services | `INCLUDE_MNO` | `operator_fts` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24209` | Norway | 242 | Com4 | Com4 AS | `INCLUDE_MVNO_OWN_PLMN` | `operator_com4` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24299` | Norway | 242 |  | TampNet AS | `INCLUDE_MNO` | `operator_tampnet_as` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24407` | Finland | 244 | Nokia | Nokia Solutions and Networks Oy | `INCLUDE_MNO` | `operator_nokia` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24426` | Finland | 244 | Compatel | Compatel Ltd | `INCLUDE_MVNO_OWN_PLMN` | `operator_compatel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24433` | Finland | 244 | VIRVE | Suomen Turvallisuusverkko Oy | `INCLUDE_MNO` | `operator_virve` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24435` | Finland | 244 |  | Edzcom Oy | `INCLUDE_MNO` | `operator_edzcom_oy` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24606` | Lithuania | 246 | Mediafon | UAB Mediafon | `INCLUDE_MNO` | `operator_mediafon` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24608` | Lithuania | 246 |  | UAB Bitė Lietuva | `INCLUDE_MNO` | `operator_uab_bit_lietuva` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24703` | Latvia | 247 | TRIATEL | Telekom Baltija | `INCLUDE_MNO` | `operator_triatel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24708` | Latvia | 247 | VMT | SIA "VENTAmobile" | `INCLUDE_MVNO_OWN_PLMN` | `operator_vmt` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24709` | Latvia | 247 | Xomobile | Camel Mobile | `INCLUDE_MVNO_OWN_PLMN` | `operator_xomobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24804` | Estonia | 248 | Top Connect | OY Top Connect | `INCLUDE_MVNO_OWN_PLMN` | `operator_top_connect` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24805` | Estonia | 248 | CSC Telecom | CSC Telecom Estonia OÜ | `INCLUDE_MVNO_OWN_PLMN` | `operator_csc_telecom` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24816` | Estonia | 248 | dzinga | SmartTel Plus OÜ | `INCLUDE_MVNO_OWN_PLMN` | `operator_dzinga` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `24833` | Estonia | 248 | JAZZ MOBILE | J-MOBILE OÜ | `INCLUDE_MVNO_OWN_PLMN` | `operator_jazz_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `25009` | Russia | 250 | Skylink | Khabarovsky Cellular Phone | `INCLUDE_MNO` | `operator_skylink` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `25016` | Russia | 250 | Miatel | Miatel | `INCLUDE_MVNO_OWN_PLMN` | `operator_miatel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `25026` | Russia | 250 | VTB Mobile | VTB Mobile | `INCLUDE_MVNO_OWN_PLMN` | `operator_vtb_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `25040` | Russia | 250 | VTC Mobile | Voentelecom | `INCLUDE_MVNO_OWN_PLMN` | `operator_vtc_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `25045` | Russia | 250 | Gazprombank Mobile | PJSC New Mobile Communications | `INCLUDE_MVNO_OWN_PLMN` | `operator_gazprombank_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `25059` | Russia | 250 | WireFire | NetbyNet | `INCLUDE_MVNO_OWN_PLMN` | `operator_wirefire` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `25097` | Russia | 250 | Phoenix | State Unitary Enterprise of DPR "Republican Telecommunications Operator" | `INCLUDE_MNO` | `operator_phoenix` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `25504` | Ukraine | 255 | Intertelecom | Intertelecom LLC | `INCLUDE_MNO` | `operator_intertelecom` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `25521` | Ukraine | 255 | PEOPLEnet | PRJSC “Telesystems of Ukraine" | `INCLUDE_MNO` | `operator_peoplenet` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `25598` | Russia | 255 | MKS (ex. Lugacom) | OOO "MKS" | `INCLUDE_MNO` | `operator_mks_ex_lugacom` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `25706` | Belarus | 257 | beCloud | Belorussian Cloud Technologies | `INCLUDE_MNO` | `operator_becloud` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `25915` | Moldova | 259 | IDC | Interdnestrcom | `INCLUDE_MNO` | `operator_idc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `26007` | Poland | 260 | Netia | Netia S.A. | `INCLUDE_MVNO_OWN_PLMN` | `operator_netia` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `26012` | Poland | 260 | Cyfrowy Polsat | Cyfrowy Polsat S.A. | `INCLUDE_MVNO_OWN_PLMN` | `operator_cyfrowy_polsat` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `26025` | Poland | 260 | TeleCube.PL | Claude ICT Poland Sp. z o.o. | `INCLUDE_MNO` | `operator_telecube_pl` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `26033` | Poland | 260 | Truphone | TP Poland Operations Sp. z o.o. | `INCLUDE_MVNO_OWN_PLMN` | `operator_truphone` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `26039` | Poland | 260 | Voxbone | VOXBONE SA | `INCLUDE_MVNO_OWN_PLMN` | `operator_voxbone` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `26045` | Poland | 260 | Virgin Mobile | P4 Sp. z o.o. | `INCLUDE_MVNO_OWN_PLMN` | `operator_virgin_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `26212` | Germany | 262 | Simquadrat | sipgate GmbH | `INCLUDE_MVNO_OWN_PLMN` | `operator_simquadrat` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `26215` | Germany | 262 | Airdata |  | `INCLUDE_MNO` | `operator_airdata` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `26219` | Germany | 262 | 450connect | Alliander AG [ nl ] | `INCLUDE_MNO` | `operator_450connect` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `26270` | Germany | 262 |  | BDBOS | `INCLUDE_MNO` | `operator_bdbos` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `26298` | Germany | 262 |  | private networks | `INCLUDE_MNO` | `operator_private_networks` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `27211` | Republic of Ireland | 272 | Tesco Mobile | Liffey Telecom | `INCLUDE_MVNO_OWN_PLMN` | `operator_tesco_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `27215` | Republic of Ireland | 272 | Virgin Mobile | UPC | `INCLUDE_MVNO_OWN_PLMN` | `operator_virgin_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `27412` | Iceland | 274 | Tal | IP fjarskipti | `INCLUDE_MVNO_OWN_PLMN` | `operator_tal` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `27491` | Iceland | 274 |  | Neyðarlínan | `INCLUDE_MNO` | `operator_ney_arl_nan` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `28022` | Cyprus | 280 | lemontel | Lemontel Ltd | `INCLUDE_MVNO_OWN_PLMN` | `operator_lemontel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `28203` | Georgia | 282 | MagtiFix | MagtiCom | `INCLUDE_MNO` | `operator_magtifix` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `28207` | Georgia | 282 | GlobalCell | GlobalCell | `INCLUDE_MVNO_OWN_PLMN` | `operator_globalcell` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `28967` | Abkhazia | 289 | Aquafon | Aquafon JSC | `INCLUDE_MNO` | `operator_aquafon` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `28988` | Abkhazia | 289 | A-Mobile | A-Mobile LLSC | `INCLUDE_MNO` | `operator_a_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `29002` | Greenland | 290 | Nanoq Media | inu:it a/s | `INCLUDE_MNO` | `operator_nanoq_media` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `29003` | Greenland | 290 | Tuullik mobile data | GTV Greenland | `INCLUDE_MNO` | `operator_tuullik_mobile_data` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `29506` | Liechtenstein | 295 | Cubic Telecom | Cubic Telecom AG | `INCLUDE_MVNO_OWN_PLMN` | `operator_cubic_telecom` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302130` | Canada | 302 | Xplore | Xplore Inc. | `INCLUDE_MNO` | `operator_xplore` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302131` | Canada | 302 | Xplore | Xplore Inc. | `INCLUDE_MNO` | `operator_xplore` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302300` | Canada | 302 | ECOTEL | Ecotel inc. | `INCLUDE_MNO` | `operator_ecotel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302310` | Canada | 302 | ECOTEL | Ecotel inc. | `INCLUDE_MNO` | `operator_ecotel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302320` | Canada | 302 | Rogers Wireless | Rogers Communications | `INCLUDE_MNO` | `operator_rogers_wireless` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302340` | Canada | 302 | Execulink | Execulink | `INCLUDE_MVNO_OWN_PLMN` | `operator_execulink` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302351` | Canada | 302 |  | MPVWifi Inc. | `INCLUDE_MNO` | `operator_mpvwifi_inc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302352` | Canada | 302 | Lyttonnet | Lytton Area Wireless Society | `INCLUDE_MNO` | `operator_lyttonnet` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302380` | Canada | 302 | Keewaytinook Mobile | Keewaytinook Okimakanak Mobile | `INCLUDE_MNO` | `operator_keewaytinook_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302420` | Canada | 302 | ABC | A.B.C. Allen Business Communications Ltd. | `INCLUDE_MNO` | `operator_abc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302480` | Canada | 302 | Qiniq | SSi Connexions | `INCLUDE_MNO` | `operator_qiniq` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302530` | Canada | 302 | Keewaytinook Mobile | Keewaytinook Okimakanak Mobile | `INCLUDE_MNO` | `operator_keewaytinook_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302590` | Canada | 302 | Quadro Mobility | Quadro Communications Co-op | `INCLUDE_MNO` | `operator_quadro_mobility` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302620` | Canada | 302 | ICE Wireless | ICE Wireless | `INCLUDE_MNO` | `operator_ice_wireless` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302650` | Canada | 302 | TBaytel | Thunder Bay Telephone | `INCLUDE_MNO` | `operator_tbaytel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302760` | Canada | 302 | Public Mobile | Telus Mobility | `INCLUDE_MVNO_OWN_PLMN` | `operator_public_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302770` | Canada | 302 | TNW Wireless | TNW Wireless Inc. | `INCLUDE_MNO` | `operator_tnw_wireless` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302790` | Canada | 302 |  | NetSet Communications | `INCLUDE_MNO` | `operator_netset_communications` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302880` | Canada | 302 | Bell / Telus / SaskTel | Shared Telus, Bell, and SaskTel | `INCLUDE_MNO` | `operator_bell_telus_sasktel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302910` | Canada | 302 |  | Halton Regional Police Service | `INCLUDE_MNO` | `operator_halton_regional_police_service` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302911` | Canada | 302 |  | Halton Regional Police Service | `INCLUDE_MNO` | `operator_halton_regional_police_service` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `302940` | Canada | 302 | Wightman Mobility | Wightman Telecom | `INCLUDE_MNO` | `operator_wightman_mobility` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `310034` | United States | 310 | Airpeak | Airpeak | `INCLUDE_MNO` | `operator_airpeak` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `310053` | United States | 310 | Virgin Mobile | T-Mobile US | `INCLUDE_MVNO_OWN_PLMN` | `operator_virgin_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `310054` | United States | 310 |  | Alltel US | `INCLUDE_MNO` | `operator_alltel_us` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `310100` | United States | 310 | Plateau Wireless | New Mexico RSA 4 East LP | `INCLUDE_MNO` | `operator_plateau_wireless` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `310320` | United States | 310 | Cellular One | Smith Bagley, Inc. | `INCLUDE_MNO` | `operator_cellular_one` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `310390` | United States | 310 | Cellular One of East Texas | TX-11 Acquisition, LLC | `INCLUDE_MNO` | `operator_cellular_one_of_east_texas` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `310420` | United States | 310 | World Mobile | World Mobile Networks, LLC | `INCLUDE_MNO` | `operator_world_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `310500` | United States | 310 | Alltel | Public Service Cellular Inc. | `INCLUDE_MNO` | `operator_alltel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `310570` | United States | 310 |  | Broadpoint, LLC | `INCLUDE_MNO` | `operator_broadpoint_llc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `310650` | United States | 310 | Jasper | Jasper Technologies | `INCLUDE_MVNO_OWN_PLMN` | `operator_jasper` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `310840` | United States | 310 | telna Mobile | Telecom North America Mobile, Inc. | `INCLUDE_MVNO_OWN_PLMN` | `operator_telna_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `310850` | United States | 310 | Aeris | Aeris Communications, Inc. | `INCLUDE_MVNO_OWN_PLMN` | `operator_aeris` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `310990` | United States | 310 | Evolve Broadband | Evolve Cellular Inc. | `INCLUDE_MNO` | `operator_evolve_broadband` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `311060` | United States | 311 |  | Space Data Corporation | `INCLUDE_MNO` | `operator_space_data_corporation` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `311170` | United States | 311 |  | Tampnet | `INCLUDE_MNO` | `operator_tampnet` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `311450` | United States | 311 | PTCI | Panhandle Telecommunication Systems Inc. | `INCLUDE_MNO` | `operator_ptci` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `311650` | United States | 311 | United Wireless | United Wireless | `INCLUDE_MNO` | `operator_united_wireless` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `311690` | United States | 311 |  | TeleBEEPER of New Mexico | `INCLUDE_MNO` | `operator_telebeeper_of_new_mexico` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `311800` | United States | 311 |  | Verizon Wireless | `INCLUDE_MNO` | `operator_verizon_wireless` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `311810` | United States | 311 |  | Verizon Wireless | `INCLUDE_MNO` | `operator_verizon_wireless` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `311970` | United States | 311 | Big River Broadband | Big River Broadband, LLC | `INCLUDE_MNO` | `operator_big_river_broadband` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `312020` | United States | 312 |  | Infrastructure Networks, LLC | `INCLUDE_MNO` | `operator_infrastructure_networks_llc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `312080` | United States | 312 | SyncSouth | South Georgia Regional Information Technology Authority | `INCLUDE_MNO` | `operator_syncsouth` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `312300` | United States | 312 | telna Mobile | Telecom North America Mobile, Inc. | `INCLUDE_MVNO_OWN_PLMN` | `operator_telna_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `312310` | United States | 312 |  | Clear Stream Communications, LLC | `INCLUDE_MNO` | `operator_clear_stream_communications_llc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `312320` | United States | 312 |  | RTC Communications LLC | `INCLUDE_MNO` | `operator_rtc_communications_llc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `312460` | United States | 312 |  | Ketchikan Public Utilities (KPU) | `INCLUDE_MNO` | `operator_ketchikan_public_utilities_kpu` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `312590` | United States | 312 | NMU | Northern Michigan University | `INCLUDE_MNO` | `operator_nmu` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `312700` | United States | 312 |  | Wireless Partners, LLC | `INCLUDE_MNO` | `operator_wireless_partners_llc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `312710` | United States | 312 |  | Great North Woods Wireless LLC | `INCLUDE_MNO` | `operator_great_north_woods_wireless_llc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `312720` | United States | 312 | Southern LINC | Southern Communications Services | `INCLUDE_MNO` | `operator_southern_linc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `312810` | United States | 312 | BBCP | Bristol Bay Telephone Cooperative | `INCLUDE_MNO` | `operator_bbcp` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `312830` | United States | 312 |  | Kings County Office of Education | `INCLUDE_MNO` | `operator_kings_county_office_of_education` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `313090` | United States | 313 |  | Vivint Wireless, Inc. | `INCLUDE_MNO` | `operator_vivint_wireless_inc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `313550` | United States | 313 |  | Mile High Networks LLC | `INCLUDE_MNO` | `operator_mile_high_networks_llc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `313560` | United States | 313 |  | Boldyn Networks US | `INCLUDE_MNO` | `operator_boldyn_networks_us` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `313690` | United States | 313 |  | Shenandoah Cable Television, LLC | `INCLUDE_MNO` | `operator_shenandoah_cable_television_llc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `313770` | United States | 313 | Mobile-X | Tango Networks | `INCLUDE_MVNO_OWN_PLMN` | `operator_mobile_x` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `313790` | Puerto Rico | 313 | Liberty | Liberty Cablevision of Puerto Rico LLC | `INCLUDE_MNO` | `operator_liberty` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `313790` | United States | 313 | Liberty | Liberty Cablevision of Puerto Rico LLC | `INCLUDE_MNO` | `operator_liberty` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `313790` | United States Virgin Islands | 313 | Liberty | Liberty | `INCLUDE_MNO` | `operator_liberty` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `313810` | United States | 313 |  | Watch Communications | `INCLUDE_MNO` | `operator_watch_communications` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `313820` | United States | 313 |  | Inland Cellular Telephone Company | `INCLUDE_MNO` | `operator_inland_cellular_telephone_company` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `313850` | United States | 313 |  | Softcom Internet Communications, Inc | `INCLUDE_MNO` | `operator_softcom_internet_communications_inc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `313870` | United States | 313 |  | ElektraFi LLC | `INCLUDE_MNO` | `operator_elektrafi_llc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `313890` | United States | 313 | TCOE | Tulare County Office of Education | `INCLUDE_MNO` | `operator_tcoe` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `314490` | United States | 314 | UETN | Utah Education and Telehealth Network | `INCLUDE_MNO` | `operator_uetn` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `314530` | United States | 314 |  | Agile Networks | `INCLUDE_MNO` | `operator_agile_networks` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `314540` | United States | 314 | RGTN | RGTN USA Inc. | `INCLUDE_MNO` | `operator_rgtn` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `314550` | United States | 314 | REALLY | Really Communications | `INCLUDE_MNO` | `operator_really` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `330000` | Puerto Rico | 330 | Open Mobile | PR Wireless | `INCLUDE_MNO` | `operator_open_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `330120` | Puerto Rico | 330 | Open Mobile | PR Wireless | `INCLUDE_MNO` | `operator_open_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `334130` | Mexico | 334 |  | ALESTRA SERVICIOS MÓVILES, S.A. DE C.V. | `INCLUDE_MNO` | `operator_alestra_servicios_m_viles_s_a_de_c_v` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `334150` | Mexico | 334 | Ultranet | Ultravisión, S.A. de C.V. | `INCLUDE_MNO` | `operator_ultranet` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `334180` | Mexico | 334 | FreedomPop | FREEDOMPOP MÉXICO, S.A. DE C.V. | `INCLUDE_MVNO_OWN_PLMN` | `operator_freedompop` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `334200` | Mexico | 334 | Virgin Mobile | VIRGIN MOBILE MÉXICO, S. DE R.L. DE C.V. | `INCLUDE_MVNO_OWN_PLMN` | `operator_virgin_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `35000` | Bermuda | 350 | One | Bermuda Digital Communications Ltd. | `INCLUDE_MNO` | `operator_one` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `350007` | Bermuda | 350 |  | Paradise Mobile | `INCLUDE_MNO` | `operator_paradise_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `36254` | Former Netherlands Antilles | 362 | ECC | East Caribbean Cellular | `INCLUDE_MNO` | `operator_ecc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `36294` | Former Netherlands Antilles | 362 | Bayòs | Bòbò Frus N.V. | `INCLUDE_MNO` | `operator_bay_s` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40003` | Azerbaijan | 400 | FONEX | CATEL | `INCLUDE_MNO` | `operator_fonex` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40108` | Kazakhstan | 401 | Kazakhtelecom |  | `INCLUDE_MNO` | `operator_kazakhtelecom` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40409` | India | 404 | Reliance | Assam | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40418` | India | 404 | Reliance | Himachal Pradesh | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40436` | India | 404 | Reliance | Bihar & Jharkhand | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40450` | India | 404 | Reliance | North East | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40452` | India | 404 | Reliance | Odisha | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40483` | India | 404 | Reliance | Kolkata | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40485` | India | 404 | Reliance | West Bengal | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40503` | India | 405 | Reliance | Bihar | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40504` | India | 405 | Reliance | Chennai | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40505` | India | 405 | Reliance | Delhi & NCR | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40506` | India | 405 | Reliance | Gujarat | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40507` | India | 405 | Reliance | Haryana | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40508` | India | 405 | Reliance | Himachal Pradesh | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40509` | India | 405 | Reliance | Jammu & Kashmir | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40510` | India | 405 | Reliance | Karnataka | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40511` | India | 405 | Reliance | Kerala | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40512` | India | 405 | Reliance | Kolkata | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40513` | India | 405 | Reliance | Maharashtra & Goa | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40514` | India | 405 | Reliance | Madhya Pradesh | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40515` | India | 405 | Reliance | Mumbai | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40517` | India | 405 | Reliance | Odisha | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40518` | India | 405 | Reliance | Punjab | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40519` | India | 405 | Reliance | Rajasthan | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40520` | India | 405 | Reliance | Tamil Nadu | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40521` | India | 405 | Reliance | Uttar Pradesh (East) | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40522` | India | 405 | Reliance | Uttar Pradesh (West) | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `40523` | India | 405 | Reliance | West Bengal | `INCLUDE_MNO` | `operator_reliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `41002` | Pakistan | 410 | 3G EVO / CharJi 4G | PTCL | `INCLUDE_MNO` | `operator_3g_evo_charji_4g` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `41005` | Pakistan | 410 | SCO Mobile | SCO Mobile Ltd | `INCLUDE_MNO` | `operator_sco_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `41008` | Pakistan | 410 | SCO Mobile | SCO Mobile Ltd | `INCLUDE_MNO` | `operator_sco_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `41255` | Afghanistan | 412 | WASEL | WASEL Afghanistan | `INCLUDE_MNO` | `operator_wasel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `41304` | Sri Lanka | 413 | Lanka Bell | Lanka Bell Ltd | `INCLUDE_MNO` | `operator_lanka_bell` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `41403` | Myanmar | 414 | CDMA800 | Myanmar Economic Corporation | `INCLUDE_MNO` | `operator_cdma800` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `41420` | Myanmar | 414 | ACS | Amara Communication Co., Ltd | `INCLUDE_MNO` | `operator_acs` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `41421` | Myanmar | 414 | ACS | Amara Communication Co., Ltd | `INCLUDE_MNO` | `operator_acs` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `41808` | Iraq | 418 | SanaTel |  | `INCLUDE_MNO` | `operator_sanatel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `41845` | Iraq | 418 | Mobitel | Mobitel Co. Ltd. | `INCLUDE_MNO` | `operator_mobitel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `41862` | Iraq | 418 | Itisaluna | Itisaluna Wireless CO. | `INCLUDE_MNO` | `operator_itisaluna` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `41892` | Iraq | 418 | Omnnea | Omnnea Wireless | `INCLUDE_MNO` | `operator_omnnea` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `42005` | Saudi Arabia | 420 | Virgin Mobile | Virgin Mobile Saudi Arabia | `INCLUDE_MVNO_OWN_PLMN` | `operator_virgin_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `42006` | Saudi Arabia | 420 | Lebara Mobile | Lebara Mobile | `INCLUDE_MVNO_OWN_PLMN` | `operator_lebara_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `42110` | Yemen | 421 | Yemen-4G | PTC/Yemen-Telecom | `INCLUDE_MNO` | `operator_yemen_4g` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `42504` | Israel | 425 |  | Globalsim Ltd | `INCLUDE_MNO` | `operator_globalsim_ltd` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `42512` | Israel | 425 | x2one | Widely Mobile | `INCLUDE_MVNO_OWN_PLMN` | `operator_x2one` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `42516` | Israel | 425 | Rami Levy | Rami Levy Communications Ltd. | `INCLUDE_MVNO_OWN_PLMN` | `operator_rami_levy` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `42518` | Israel | 425 | Cellact Communications | Cellact Communications Ltd. | `INCLUDE_MVNO_OWN_PLMN` | `operator_cellact_communications` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `42519` | Israel | 425 | 019 Mobile | 019 Communication Services Ltd. / TELZAR | `INCLUDE_MVNO_OWN_PLMN` | `operator_019_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `42524` | Israel | 425 | 012 Mobile | Partner Communications Company Ltd. | `INCLUDE_MVNO_OWN_PLMN` | `operator_012_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `42525` | Israel | 425 | IMOD | Israel Ministry of Defense | `INCLUDE_MNO` | `operator_imod` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `42526` | Israel | 425 | Annatel | LB Annatel Ltd. | `INCLUDE_MVNO_OWN_PLMN` | `operator_annatel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `42705` | Qatar | 427 | Ministry of Interior | Ministry of Interior | `INCLUDE_MNO` | `operator_ministry_of_interior` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `42706` | Qatar | 427 | Ministry of Interior | Ministry of Interior | `INCLUDE_MNO` | `operator_ministry_of_interior` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `43202` | Iran | 432 | ApTel, AzarTel | Negin Ertebatat Ava | `INCLUDE_MVNO_OWN_PLMN` | `operator_aptel_azartel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `43206` | Iran | 432 | Arian-Tel | Ertebatat-e Arian Tel Co. | `INCLUDE_MVNO_OWN_PLMN` | `operator_arian_tel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `43208` | Iran | 432 | Shatel Mobile | Shatel Group | `INCLUDE_MVNO_OWN_PLMN` | `operator_shatel_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `43210` | Iran | 432 | Samantel | Samantel Mobile | `INCLUDE_MVNO_OWN_PLMN` | `operator_samantel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `43212` | Iran | 432 | Avacell (HiWEB) | Dadeh Dostar asr Novin PJSC | `INCLUDE_MNO` | `operator_avacell_hiweb` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `43214` | Iran | 432 | TKC/KFZO | Kish Free Zone Organization | `INCLUDE_MNO` | `operator_tkc_kfzo` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `43232` | Iran | 432 | Taliya | Telecommunication Company of Iran (TCI) | `INCLUDE_MNO` | `operator_taliya` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `43244` | Iran | 432 | Mobinnet | Ertebatat Mobinnet | `INCLUDE_MNO` | `operator_mobinnet` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `43245` | Iran | 432 | Zi-Tel | Farabord Dadeh Haye Iranian Co. | `INCLUDE_MNO` | `operator_zi_tel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `43246` | Iran | 432 | HiWEB | Dadeh Dostar asr Novin PJSC | `INCLUDE_MNO` | `operator_hiweb` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `43290` | Iran | 432 | Iraphone | IRAPHONE GHESHM of Iran | `INCLUDE_MNO` | `operator_iraphone` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `43293` | Iran | 432 | Iraphone | Iraphone | `INCLUDE_MNO` | `operator_iraphone` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `43293` | Iran | 432 | Farzanegan Pars | Farzanegan Pars | `INCLUDE_MNO` | `operator_farzanegan_pars` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `43409` | Uzbekistan | 434 | EVO | OOO «Super iMAX» | `INCLUDE_MNO` | `operator_evo` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `43410` | Uzbekistan | 434 | HUMANS | OOO «HUMANS» | `INCLUDE_MVNO_OWN_PLMN` | `operator_humans` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `43710` | Kyrgyzstan | 437 |  | Saima Telecom | `INCLUDE_MNO` | `operator_saima_telecom` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `43803` | Turkmenistan | 438 | AGTS CDMA | AŞTU | `INCLUDE_MNO` | `operator_agts_cdma` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `44001` | Japan | 440 |  | KDDI Corporation | `INCLUDE_MNO` | `operator_kddi_corporation` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `44005` | Japan | 440 |  | Wireless City Planning Inc. | `INCLUDE_MNO` | `operator_wireless_city_planning_inc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `44100` | Japan | 441 |  | Wireless City Planning Inc. | `INCLUDE_MNO` | `operator_wireless_city_planning_inc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `441205` | Japan | 441 |  | Katch Network Inc. | `INCLUDE_MNO` | `operator_katch_network_inc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `45011` | South Korea | 450 | Tplus | Korea Cable Telecom | `INCLUDE_MVNO_OWN_PLMN` | `operator_tplus` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `45408` | Hong Kong | 454 | Truphone | Truphone Limited | `INCLUDE_MVNO_OWN_PLMN` | `operator_truphone` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `45431` | Hong Kong | 454 | CTExcel | China Telecom Global Limited | `INCLUDE_MVNO_OWN_PLMN` | `operator_ctexcel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `45505` | Macau | 455 | 3 Macau | Hutchison Telephone (Macau), Limitada | `INCLUDE_MNO` | `operator_3_macau` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `45707` | Laos | 457 | Best | Best Telecom Co., Ltd | `INCLUDE_MNO` | `operator_best` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `45708` | Laos | 457 | TPLUS | TPLUS Digital Sole Co., Ltd | `INCLUDE_MNO` | `operator_tplus` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `46612` | Taiwan | 466 |  | Asia Pacific Telecom | `INCLUDE_MNO` | `operator_asia_pacific_telecom` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `47010` | Bangladesh | 470 | Banglalion | Banglalion Communications Ltd. | `INCLUDE_MNO` | `operator_banglalion` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `50211` | Malaysia | 502 | TM Homeline | Telekom Malaysia Bhd | `INCLUDE_MNO` | `operator_tm_homeline` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `502150` | Malaysia | 502 | Tune Talk | Tune Talk Sdn Bhd | `INCLUDE_MVNO_OWN_PLMN` | `operator_tune_talk` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `502152` | Malaysia | 502 | Yes | YTL Communications Sdn Bhd | `INCLUDE_MNO` | `operator_yes` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `502157` | Malaysia | 502 | Telin | Telekomunikasi Indonesia International (M) Sdn Bhd | `INCLUDE_MVNO_OWN_PLMN` | `operator_telin` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `50514` | Australia | 505 | AAPT | TPG Telecom | `INCLUDE_MVNO_OWN_PLMN` | `operator_aapt` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `50523` | Australia | 505 |  | Challenge Networks Pty Ltd | `INCLUDE_MNO` | `operator_challenge_networks_pty_ltd` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `50538` | Australia | 505 | Truphone | Truphone Pty Ltd | `INCLUDE_MVNO_OWN_PLMN` | `operator_truphone` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `50552` | Australia | 505 |  | OptiTel Australia | `INCLUDE_MNO` | `operator_optitel_australia` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `50558` | Australia | 505 |  | Wi-Sky (NSW) Pty Ltd | `INCLUDE_MNO` | `operator_wi_sky_nsw_pty_ltd` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `50562` | Australia | 505 | NBN | National Broadband Network Co. | `INCLUDE_MNO` | `operator_nbn` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `50568` | Australia | 505 | NBN | National Broadband Network Co. | `INCLUDE_MNO` | `operator_nbn` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `51505` | Philippines | 515 | Sun Cellular | Digital Telecommunications Philippines | `INCLUDE_MNO` | `operator_sun_cellular` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `51524` | Philippines | 515 | ABS-CBN Mobile | ABS-CBN Convergence with Globe Telecom | `INCLUDE_MVNO_OWN_PLMN` | `operator_abs_cbn_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `52009` | Thailand | 520 |  | Royal Thai Police | `INCLUDE_MNO` | `operator_royal_thai_police` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `52509` | Singapore | 525 | Circles.Life | Liberty Wireless Pte Ltd | `INCLUDE_MVNO_OWN_PLMN` | `operator_circles_life` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `52512` | Singapore | 525 | Grid | GRID Communications Pte Ltd. | `INCLUDE_MNO` | `operator_grid` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `52803` | Brunei | 528 | UNN | Unified National Networks Sdn Bhd | `INCLUDE_MNO` | `operator_unn` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `53702` | Papua New Guinea | 537 | citifon | Telikom PNG Ltd. | `INCLUDE_MNO` | `operator_citifon` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `53943` | Tonga | 539 |  | Shoreline Communication | `INCLUDE_MNO` | `operator_shoreline_communication` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `54100` | Vanuatu | 541 | AIL | ACeS International (AIL) | `INCLUDE_MNO` | `operator_ail` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `54107` | Vanuatu | 541 | WanTok | WanTok Vanuatu Ltd | `INCLUDE_MNO` | `operator_wantok` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `54203` | Fiji | 542 | TFL | Telecom Fiji Ltd | `INCLUDE_MNO` | `operator_tfl` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `54705` | French Polynesia | 547 | Ora | VITI | `INCLUDE_MNO` | `operator_ora` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `55202` | Palau | 552 | PT Waves | Palau Equipment Company Inc. | `INCLUDE_MNO` | `operator_pt_waves` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `60307` | Algeria | 603 | AT | Algérie Télécom | `INCLUDE_MNO` | `operator_at` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `60309` | Algeria | 603 | AT | Algérie Télécom | `INCLUDE_MNO` | `operator_at` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `60602` | Libya | 606 | Al-Jeel Phone | Al-Jeel Al-Jadeed | `INCLUDE_MVNO_OWN_PLMN` | `operator_al_jeel_phone` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `60603` | Libya | 606 | Libya Phone | Libya Telecom & Technology (LTT) | `INCLUDE_MVNO_OWN_PLMN` | `operator_libya_phone` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `60606` | Libya | 606 | Hatef Libya | Hatef Libya | `INCLUDE_MNO` | `operator_hatef_libya` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `60703` | The Gambia | 607 | Comium | Comium | `INCLUDE_MNO` | `operator_comium` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `60706` | The Gambia | 607 |  | NETPAGE | `INCLUDE_MNO` | `operator_netpage` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `61102` | Guinea | 611 | Sotelgui | Sotelgui Lagui | `INCLUDE_MNO` | `operator_sotelgui` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `61204` | Ivory Coast | 612 | KoZ | Comium Ivory Coast Inc | `INCLUDE_MNO` | `operator_koz` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `61206` | Ivory Coast | 612 | GreenN | Oricel | `INCLUDE_MNO` | `operator_greenn` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `61207` | Ivory Coast | 612 | café | Aircomm | `INCLUDE_MNO` | `operator_caf` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `61218` | Ivory Coast | 612 |  | YooMee | `INCLUDE_MNO` | `operator_yoomee` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `61401` | Niger | 614 | SahelCom | La Société Sahélienne de Télécommunications (SahelCom) | `INCLUDE_MNO` | `operator_sahelcom` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `61601` | Benin | 616 |  | Benin Telecoms Mobile | `INCLUDE_MNO` | `operator_benin_telecoms_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `61702` | Mauritius | 617 | MOKOZE / AZU | Mahanagar Telephone Mauritius Limited (MTML) | `INCLUDE_MNO` | `operator_mokoze_azu` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `61703` | Mauritius | 617 | CHILI | Mahanagar Telephone Mauritius Limited (MTML) | `INCLUDE_MNO` | `operator_chili` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `61804` | Liberia | 618 | Novafone | Novafone Inc. | `INCLUDE_MNO` | `operator_novafone` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `61820` | Liberia | 618 | LIBTELCO | Liberia Telecommunications Corporation | `INCLUDE_MNO` | `operator_libtelco` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `61903` | Sierra Leone | 619 | Africell | Lintel Sierra Leone Limited | `INCLUDE_MNO` | `operator_africell` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `62007` | Ghana | 620 | Globacom | Globacom Group | `INCLUDE_MNO` | `operator_globacom` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `62010` | Ghana | 620 | Blu | Blu Telecommunications | `INCLUDE_MNO` | `operator_blu` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `62014` | Ghana | 620 | busy | BusyInternet | `INCLUDE_MNO` | `operator_busy` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `62016` | Ghana | 620 |  | Telesol | `INCLUDE_MNO` | `operator_telesol` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `62122` | Nigeria | 621 | InterC | InterC Network Ltd. | `INCLUDE_MNO` | `operator_interc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `62124` | Nigeria | 621 |  | Spectranet | `INCLUDE_MNO` | `operator_spectranet` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `62126` | Nigeria | 621 |  | Swift | `INCLUDE_MNO` | `operator_swift` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `62140` | Nigeria | 621 | Ntel | Nigerian Mobile Telecommunications Limited | `INCLUDE_MNO` | `operator_ntel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `62304` | Central African Republic | 623 | Azur | Azur RCA | `INCLUDE_MNO` | `operator_azur` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `63005` | Democratic Republic of the Congo | 630 | Supercell | Supercell SPRL | `INCLUDE_MNO` | `operator_supercell` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `63405` | Sudan | 634 | canar | Canar Telecom | `INCLUDE_MNO` | `operator_canar` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `63409` | Sudan | 634 | khartoum INC | NEC | `INCLUDE_MNO` | `operator_khartoum_inc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `63517` | Rwanda | 635 | Olleh | Olleh Rwanda Networks | `INCLUDE_MNO` | `operator_olleh` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `63710` | Somalia | 637 | Nationlink | NationLink Telecom | `INCLUDE_MNO` | `operator_nationlink` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `63720` | Somalia | 637 | SOMNET | SOMNET | `INCLUDE_MNO` | `operator_somnet` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `63760` | Somalia | 637 | Nationlink | Nationlink Telecom | `INCLUDE_MNO` | `operator_nationlink` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `63767` | Somalia | 637 | Horntel Group | HTG Group Somalia | `INCLUDE_MNO` | `operator_horntel_group` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `63782` | Somalia | 637 | Telcom | Telcom Somalia | `INCLUDE_MNO` | `operator_telcom` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `64006` | Tanzania | 640 |  | WIA Company Limited | `INCLUDE_MNO` | `operator_wia_company_limited` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `64011` | Tanzania | 640 | SmileCom | Smile Telecoms Holdings Ltd. | `INCLUDE_MNO` | `operator_smilecom` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `64104` | Uganda | 641 |  | Tangerine Uganda Limited | `INCLUDE_MNO` | `operator_tangerine_uganda_limited` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `64144` | Uganda | 641 | K2 | K2 Telecom Ltd | `INCLUDE_MVNO_OWN_PLMN` | `operator_k2` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `64203` | Burundi | 642 | Onatel | Onatel | `INCLUDE_MNO` | `operator_onatel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `64605` | Madagascar | 646 | BIP / blueline | Gulfsat Madagascar S.A. | `INCLUDE_MNO` | `operator_bip_blueline` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `64904` | Namibia | 649 |  | Paratus Telecommunications (Pty) | `INCLUDE_MNO` | `operator_paratus_telecommunications_pty` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `64906` | Namibia | 649 |  | MTN Namibia | `INCLUDE_MNO` | `operator_mtn_namibia` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `65003` | Malawi | 650 | MTL | Malawi Telecommunications Limited | `INCLUDE_MNO` | `operator_mtl` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `65506` | South Africa | 655 |  | Sentech (Pty) Ltd | `INCLUDE_MNO` | `operator_sentech_pty_ltd` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `65514` | South Africa | 655 | Neotel | Neotel Pty Ltd | `INCLUDE_MNO` | `operator_neotel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `65530` | South Africa | 655 |  | Bokamoso Consortium | `INCLUDE_MNO` | `operator_bokamoso_consortium` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `65531` | South Africa | 655 |  | Karabo Telecoms (Pty) Ltd. | `INCLUDE_MNO` | `operator_karabo_telecoms_pty_ltd` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `65532` | South Africa | 655 |  | Ilizwi Telecommunications | `INCLUDE_MNO` | `operator_ilizwi_telecommunications` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `65533` | South Africa | 655 |  | Thinta Thinta Telecommunications Pty Ltd | `INCLUDE_MNO` | `operator_thinta_thinta_telecommunications_pty_ltd` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `65576` | South Africa | 655 |  | Comsol Networks (Pty) Ltd | `INCLUDE_MNO` | `operator_comsol_networks_pty_ltd` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `708030` | Honduras | 708 | Hondutel | Empresa Hondureña de Telecomunicaciones | `INCLUDE_MNO` | `operator_hondutel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `722040` | Argentina | 722 | Globalstar | TE.SA.M Argentina S.A. | `INCLUDE_MNO` | `operator_globalstar` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `72207` | Argentina | 722 | Movistar | Telefónica Móviles Argentina S.A. | `INCLUDE_MNO` | `operator_movistar` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `72413` | Brazil | 724 | NLT | NLT Telecom | `INCLUDE_MVNO_OWN_PLMN` | `operator_nlt` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `72415` | Brazil | 724 | Sercomtel | Sercomtel Celular | `INCLUDE_MNO` | `operator_sercomtel` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `72417` | Brazil | 724 | Surf Telecom | Correios Celular[pt] | `INCLUDE_MVNO_OWN_PLMN` | `operator_surf_telecom` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `72421` | Brazil | 724 | LIGUE | Ligue Telecom | `INCLUDE_MNO` | `operator_ligue` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `72428` | Brazil | 724 | No name |  | `INCLUDE_MNO` | `operator_no_name` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `72429` | Brazil | 724 | Unifique | Unifique Telecomunicações S/A | `INCLUDE_MNO` | `operator_unifique` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `72440` | Brazil | 724 | Telecall | Telexperts Telecomunicações S.A | `INCLUDE_MVNO_OWN_PLMN` | `operator_telecall` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `72499` | Brazil | 724 | Local |  | `INCLUDE_MNO` | `operator_local` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `73006` | Chile | 730 | Telsur | Blue Two Chile S.A. | `INCLUDE_MVNO_OWN_PLMN` | `operator_telsur` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `73008` | Chile | 730 | VTR Móvil | VTR S.A. | `INCLUDE_MVNO_OWN_PLMN` | `operator_vtr_m_vil` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `73012` | Chile | 730 | Colo-Colo MóvilWanderers Móvil | Telestar Móvil S.A. | `INCLUDE_MVNO_OWN_PLMN` | `operator_colo_colo_m_vilwanderers_m_vil` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `73013` | Chile | 730 | Virgin Mobile | Tribe Mobile Chile SPA | `INCLUDE_MVNO_OWN_PLMN` | `operator_virgin_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `73019` | Chile | 730 | móvil Falabella | Sociedad Falabella Móvil SPA | `INCLUDE_MVNO_OWN_PLMN` | `operator_m_vil_falabella` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `73099` | Chile | 730 | Will | WILL Telefonía | `INCLUDE_MNO` | `operator_will` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `732099` | Colombia | 732 | EMCALI | Empresas Municipales de Cali | `INCLUDE_MNO` | `operator_emcali` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `732154` | Colombia | 732 | Virgin Mobile | Virgin Mobile Colombia S.A.S. | `INCLUDE_MVNO_OWN_PLMN` | `operator_virgin_mobile` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `732176` | Colombia | 732 |  | DirecTV Colombia Ltda | `INCLUDE_MNO` | `operator_directv_colombia_ltda` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `732187` | Colombia | 732 | eTb | Empresa de Telecomunicaciones de Bogotá S.A. ESP | `INCLUDE_MNO` | `operator_etb` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `732240` | Colombia | 732 |  | Logistica Flash Colombia SAS | `INCLUDE_MNO` | `operator_logistica_flash_colombia_sas` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `738002` | Guyana | 738 | GT&T Cellink Plus | Guyana Telephone & Telegraph Co. | `INCLUDE_MNO` | `operator_gt_t_cellink_plus` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `738003` | Guyana | 738 |  | Quark Communications Inc. | `INCLUDE_MNO` | `operator_quark_communications_inc` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `74003` | Ecuador | 740 | Tuenti | Otecel S.A. | `INCLUDE_MVNO_OWN_PLMN` | `operator_tuenti` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `74406` | Paraguay | 744 | Copaco | Copaco S.A. | `INCLUDE_MNO` | `operator_copaco` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `90201` | International_operators | 902 |  | MulteFire Alliance | `INCLUDE_MNO` | `operator_multefire_alliance` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |
| `99501` | British Indian Ocean Territory | 995 | FonePlus | Sure (Diego Garcia) Ltd | `INCLUDE_MNO` | `operator_foneplus` | [https://www.itu.int/rec/T-REC-E.212 / Wikipedia](https://www.itu.int/rec/T-REC-E.212 / Wikipedia) | 2026-10-25 |

## Project-Only PLMNs

| PLMN | Current BrandKey | Verification Result | Source | Date |
|---|---|---|---|---|
| `20821` | `operator_bouygues` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `20891` | `operator_orange` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `21201` | `operator_monaco_telecom` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `21418` | `operator_vodafone` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `21421` | `operator_jazztel` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `21425` | `operator_lycamobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `21438` | `operator_movistar` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `22002` | `operator_yettel` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `22237` | `operator_windtre` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `22248` | `operator_tim` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `22606` | `operator_vodafone` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `23107` | `operator_orange` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `23115` | `operator_orange` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `23150` | `operator_telekom` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `23202` | `operator_a1` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `23204` | `operator_magenta` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `23206` | `operator_three` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `23214` | `operator_three` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `23407` | `operator_vodafone` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `23431` | `operator_ee` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `23432` | `operator_ee` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `23457` | `operator_sky_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `23486` | `operator_ee` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `23491` | `operator_vodafone` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `23877` | `operator_telenor` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `23896` | `operator_telia` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `24008` | `operator_telenor` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `24207` | `operator_ice` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `24212` | `operator_telenor` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `24404` | `operator_dna` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `24406` | `operator_elisa` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `24413` | `operator_dna` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `25012` | `operator_tele2` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `25028` | `operator_beeline` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `25039` | `operator_rostelecom` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `25567` | `operator_kyivstar` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `25568` | `operator_kyivstar` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `26204` | `operator_vodafone` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `26205` | `operator_o2` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `26206` | `operator_telekom` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `26208` | `operator_o2` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `26211` | `operator_o2` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `26217` | `operator_o2` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `26277` | `operator_o2` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `26278` | `operator_telekom` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `26802` | `operator_digi` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `26807` | `operator_nos` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `26808` | `operator_meo` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `26880` | `operator_meo` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `26891` | `operator_vodafone` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `26893` | `operator_nos` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `27403` | `operator_vodafone` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `27431` | `operator_siminn` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `27830` | `operator_go` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `28002` | `operator_cyta` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `28604` | `operator_turk_telekom` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `28803` | `operator_tosa` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `29402` | `operator_a1` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `302221` | `operator_telus` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `302222` | `operator_telus` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `302630` | `operator_bell` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `302640` | `operator_bell` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310005` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310010` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310013` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310016` | `operator_att` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310026` | `operator_t_mobile` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310030` | `operator_att` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310040` | `operator_mta` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310180` | `operator_west_central` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310200` | `operator_t_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310210` | `operator_t_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310220` | `operator_t_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310230` | `operator_t_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310240` | `operator_t_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310250` | `operator_t_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310270` | `operator_t_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310280` | `operator_att` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310300` | `operator_t_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310310` | `operator_t_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310360` | `operator_pioneer_cellular` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310380` | `operator_att` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310400` | `operator_iconnect` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310460` | `operator_conecto` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310470` | `operator_docomo_pacific` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310530` | `operator_t_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310560` | `operator_att` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310600` | `operator_cellcom` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310660` | `operator_t_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310730` | `operator_us_cellular` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310800` | `operator_t_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310860` | `operator_five_star` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310890` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310900` | `operator_midrivers` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310910` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310930` | `operator_copper_valley` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `310960` | `operator_strata` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311000` | `operator_west_central` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311020` | `operator_chariton` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311110` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311180` | `operator_att` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311220` | `operator_us_cellular` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311221` | `operator_us_cellular` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311222` | `operator_us_cellular` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311223` | `operator_us_cellular` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311224` | `operator_us_cellular` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311225` | `operator_us_cellular` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311226` | `operator_us_cellular` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311227` | `operator_us_cellular` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311228` | `operator_us_cellular` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311229` | `operator_us_cellular` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311250` | `operator_iconnect` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311270` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311271` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311272` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311273` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311274` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311275` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311276` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311277` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311278` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311279` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311280` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311281` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311282` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311283` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311284` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311285` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311286` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311287` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311288` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311289` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311390` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311410` | `operator_chat_mobility` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311481` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311482` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311483` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311484` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311485` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311486` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311487` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311488` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311489` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311630` | `operator_cspire` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311870` | `operator_t_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `311880` | `operator_t_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `312170` | `operator_chat_mobility` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `312190` | `operator_t_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `312220` | `operator_chariton` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `312270` | `operator_pioneer_cellular` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `312280` | `operator_pioneer_cellular` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `312400` | `operator_midrivers` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `312770` | `operator_verizon` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `313000` | `operator_tn_wireless` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `313010` | `operator_bravado` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `313030` | `operator_snake_river` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `313050` | `operator_breakaway` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `313110` | `operator_att` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `313210` | `operator_att` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `313350` | `operator_dish` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `313360` | `operator_dish` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `338020` | `operator_flow` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `338110` | `operator_flow` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `346050` | `operator_digicel` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `350000` | `operator_cellone` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `358050` | `operator_digicel` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `36259` | `operator_chippie` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `37413` | `operator_digicel` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `376050` | `operator_digicel` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `40404` | `operator_vi` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `40413` | `operator_vi` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `40424` | `operator_vi` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `40427` | `operator_vi` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `40484` | `operator_vi` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `40488` | `operator_vi` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `40566` | `operator_vi` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `405752` | `operator_vi` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `405754` | `operator_vi` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `405756` | `operator_vi` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `405845` | `operator_vi` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `405908` | `operator_vi` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `41303` | `operator_hutch_lk` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `41309` | `operator_hutch_lk` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `42204` | `operator_omantel` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `43002` | `operator_e_and` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `43102` | `operator_e_and` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `43221` | `operator_rightel` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `43612` | `operator_tcell` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `44000` | `operator_ymobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `44004` | `operator_softbank` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `44006` | `operator_softbank` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `44030` | `operator_ntt_docomo` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `44054` | `operator_au_kddi` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `44055` | `operator_kddi` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `44101` | `operator_softbank` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `45007` | `operator_kt` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `45404` | `operator_three_hk` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `45414` | `operator_three_hk` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `45415` | `operator_smartone` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `45417` | `operator_smartone` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `45418` | `operator_csl` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `45428` | `operator_china_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `45502` | `operator_china_telecom` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `45504` | `operator_ctm` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `46002` | `operator_china_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `46005` | `operator_china_telecom` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `46006` | `operator_china_unicom` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `46007` | `operator_china_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `46008` | `operator_china_mobile` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `502198` | `operator_celcom` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `50506` | `operator_vodafone` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `51008` | `operator_xl_axiata` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `52502` | `operator_singtel` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `54900` | `operator_digicel` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `55280` | `operator_pmci` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `61902` | `operator_africell_sl` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `61907` | `operator_qcell_sl` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `63514` | `operator_airtel` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `63911` | `operator_jambo` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `64207` | `operator_smart_bi` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `64701` | `operator_maore` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `65538` | `operator_rain` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `65573` | `operator_rain` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `71030` | `operator_movistar` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `722007` | `operator_movistar` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `722340` | `operator_personal` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `72408` | `operator_tim` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `72439` | `operator_claro` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `73007` | `operator_movistar` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `732102` | `operator_movistar` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `732142` | `operator_tigo` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `73802` | `operator_eplus` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `74201` | `operator_orange` | Verified in ITU-T E.212 national allocation table | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |
| `74800` | `operator_antel` | Inactive or decommissioned in dataset; verified with regulator | [https://www.itu.int/rec/T-REC-E.212](https://www.itu.int/rec/T-REC-E.212) | 2026-10-25 |

## Brand Conflicts

| PLMN | Project BrandKey | Dataset Brand / Operator | Source | Recommended Action |
|---|---|---|---|---|
| `22207` | `operator_tim` | Kena Mobile (Noverca) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `22603` | `operator_vodafone` | Telekom (Telekom Romania Mobile) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `23207` | `operator_magenta` | Hofer Telekom (T-Mobile Austria) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `24004` | `operator_telenor` | SWEDEN (3G Infrastructure Services AB) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `25050` | `operator_mts` | SberMobile (Sberbank-Telecom) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `25054` | `operator_tattelecom` | Miranda-Media (Miranda-Media) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `25096` | `operator_seven_telecom` | +7Telecom (K-Telecom) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `25903` | `operator_unite_md` | Moldtelecom (Moldtelecom) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `25905` | `operator_unite_md` | Moldtelecom (Moldtelecom) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `25999` | `operator_unite_md` | Moldtelecom (Moldtelecom) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `26220` | `operator_o2` | Enreach (Enreach Germany GmbH) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `26223` | `operator_oneandone` | 1&1 (Drillisch Online AG) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `27401` | `operator_siminn` | Síminn (Iceland Telecom) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `27408` | `operator_siminn` | On-waves (Iceland Telecom) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `27801` | `operator_vodafone` | Epic (Epic) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `28603` | `operator_turk_telekom` | Türk Telekom (Türk Telekom) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `28801` | `operator_foroya_tele` | Føroya Tele (Føroya Tele) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `28802` | `operator_tosa` | Nema (Nema) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `29341` | `operator_telekom_si` | Mobitel (Telekom Slovenije) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `310032` | `operator_iconnect` | IT&E Wireless (PTI Pacifica Inc.) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `310032` | `operator_iconnect` | IT&E Wireless (PTI Pacifica Inc.) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `310110` | `operator_iconnect` | IT&E Wireless (PTI Pacifica Inc.) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `310110` | `operator_iconnect` | IT&E Wireless (PTI Pacifica Inc.) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `310480` | `operator_iconnect` | IT&E Wireless (PTI Pacifica Inc.) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `310480` | `operator_iconnect` | IT&E Wireless (PTI Pacifica Inc.) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `311120` | `operator_iconnect` | IT&E Wireless (PTI Pacifica Inc.) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `311120` | `operator_iconnect` | IT&E Wireless (PTI Pacifica Inc.) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `334140` | `operator_altan` | Red Compartida (Altán Redes S.A.P.I. de C.V.) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `356070` | `operator_the_cable` | FLOW (UTS) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `36260` | `operator_chippie` | FLOW (Liberty Latin America) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `36291` | `operator_chippie` | FLOW (Liberty Latin America) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `40468` | `operator_bsnl` | MTNL (Delhi & NCR) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `40469` | `operator_bsnl` | MTNL (Mumbai) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `41301` | `operator_mobitel_lk` | SLTMobitel (Mobitel (Pvt) Ltd) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `41312` | `operator_mobitel_lk` | SLTMobitel (Sri Lanka Telecom PLC) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `41406` | `operator_atom` | Telenor (Telenor Myanmar) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `42102` | `operator_y_yemen` | YOU (Yemen Oman United Telecom) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `42402` | `operator_e_and` | Etisalat (Emirates Telecom Corp) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `42901` | `operator_ntc` | Namaste / NT Mobile / Sky Phone (Nepal Telecom (NDCL)) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `43299` | `operator_mci` | TCI (TCI of Iran and Rightel) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `45416` | `operator_csl` | PCCW Mobile (2G) (PCCW) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `45419` | `operator_csl` | PCCW Mobile (3G) (PCCW-HKT) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `45420` | `operator_csl` | PCCW Mobile (4G) (PCCW-HKT) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `45429` | `operator_csl` | PCCW Mobile (CDMA) (PCCW-HKT) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `46605` | `operator_fareastone` | Gt (Asia Pacific Telecom) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `46689` | `operator_taiwan_mobile` | T Star (Taiwan Star Telecom) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `46692` | `operator_cht` | Chunghwa (Chunghwa Telecom) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `46706` | `operator_koryolink` | Kang Song NET (Korea Posts and Telecommunications Corporation) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `51028` | `operator_smartfren` | Fren/Hepi (PT Mobile-8 Telecom) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `51089` | `operator_tri_id` | 3 (PT Hutchison 3 Indonesia) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `52802` | `operator_bmobile` | PCSB (Progresif Cellular Sdn Bhd) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `54002` | `operator_our_telekom` | BeMobile (BMobile (SI) Ltd) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `54501` | `operator_frigate` | Kiribati - ATH (Amalgamated Telecom Holdings Kiribati Limited) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `54901` | `operator_bluesky` | Digicel (Digicel Pacific Ltd.) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `60203` | `operator_e_and` | Etisalat (Etisalat Egypt) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `60901` | `operator_mauritel` | Mattel (Mattel) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `60910` | `operator_mattel` | Moov (Mauritel Mobiles) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `61001` | `operator_moov` | Malitel (Malitel SA) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `61301` | `operator_moov` | Telmob (Onatel) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `61604` | `operator_celtiis` | BBCOM (Bell Benin Communications) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `61801` | `operator_orange` | Lonestar Cell MTN (Lonestar Communications Corporation) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `62002` | `operator_telecel` | Vodafone (Vodafone Group) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `62203` | `operator_moov` | Tigo (Millicom) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `62207` | `operator_salaam_td` | Salam (SotelTchad) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `62302` | `operator_telecel_cf` | TC (Telecel Centrafrique) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `62801` | `operator_moov` | Libertis (Gabon Telecom S.A.) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `63202` | `operator_orange` | MTN Areeba (Spacetel Guiné-Bissau S.A.) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `63301` | `operator_cws` | Cable & Wireless (Cable & Wireless Seychelles) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `63704` | `operator_telcom_so` | Somafone (Somafone FZLLC) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `64002` | `operator_yas` | tiGO (MIC Tanzania Limited) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `64111` | `operator_utl` | Uganda Telecom (Uganda Telecom Ltd.) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `64702` | `operator_sfr` | Only (Telco OI) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `65204` | `operator_btc_bw` | beMobile (Botswana Telecommunications Corporation) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `70267` | `operator_digi_bz` | DigiCell (Belize Telemedia Limited (BTL)) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `70403` | `operator_movistar` | Claro (Telecomunicaciones de Guatemala, S.A.) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `71201` | `operator_kolbi` | Kölbi ICE (Instituto Costarricense de Electricidad) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `71202` | `operator_kolbi` | Kölbi ICE (Instituto Costarricense de Electricidad) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `71204` | `operator_movistar` | Liberty (Liberty Latin America) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `71401` | `operator_flow` | +Móvil (Cable & Wireless Panama S.A.) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `71402` | `operator_movistar` | Tigo (Grupo de Comunicaciones Digitales, S.A.) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |
| `714020` | `operator_movistar` | Tigo (Grupo de Comunicaciones Digitales, S.A.) | ITU-T E.212 / Wikipedia | Review brand assignment; update mapping if operator changed or reallocated. |

## Excluded Networks Summary

- **EXCLUDE_SPECIAL:** 172 (Railway GSM-R, Satellite, Test networks)
- **EXCLUDE_DEFUNCT:** 1755 (Non-operational or historical PLMNs)
- **EXCLUDE_MVNO_SHARED_PLMN:** 56 (Shared PLMNs)

