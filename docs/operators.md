# Operators and Brand Management

## Scope
The set of supported operator brands is determined by the keys defined in `OperatorResolver.operatorResourceMap` (implemented via `OperatorBrandDatabase`). The source code serves as the single source of truth; no separate standalone list dictates supported brands.

## Adding a Brand
To add a new operator brand to the application, the following seven artifacts must be provided and updated:

1. **String Resource**: Add string resource definition in `core/resources/src/main/res/values/donottranslate.xml` with
   `translatable="false"` (brand names are not translated).
2. **Vector Icon**: Add vector drawable icon in `core/resources/src/main/res/drawable/ic_operator_<brand>.xml`.
3. **Search Tokens**: Configure search tokens in `OperatorBrandDatabase`.
4. **Brand Map Entry**: Add mapping in `OperatorBrandDatabase.operatorResourceMap`.
5. **Icon Map Entry**: Add mapping in `SimUtils.operatorIconMap`.
6. **PLMN Entries**: Add corresponding PLMN code to brand mapping in `mcc_mnc_db.json`.
7. **Documentation**: Update documentation in `docs/operator_icons.md` and `docs/operator_plmn_sources.md`.

## MVNO Handling
Virtual network operators (MVNOs) typically share the PLMN of their host mobile network operator (MNO). Consequently, MVNOs are identified by their subscription network name rather than direct PLMN matching. Dedicated PLMN entries in `mcc_mnc_db.json` are not added for MVNO brands that piggyback on host network PLMNs.

## Ambiguous Brand Names
Single tokens that are common to unrelated brands across different countries are not registered. Resolution is performed by PLMN from `mcc_mnc_db.json`. Only unambiguous compound tokens are registered.

## Unsupported Networks
When a network is encountered whose MCC/MNC is absent from `mcc_mnc_db.json` or whose brand is unmapped, the application falls back to displaying the subscription network name or the MCC/MNC identifier string, using the generic `ic_unknown` icon.

## Display Name Resolution
The operator display name reflects network self-identification (name reported by network/SIM, e.g., EONS, SPN, `SubscriptionInfo.displayName`), without rewriting. Canonical brand resource strings apply only when the network does not report a specific name. The PLMN database and search tokens determine exclusively brand attribution (icon) and never overwrite the name reported by the network. Names and country attribution in provenance data follow network self-identification and actual numbering plans, without political judgments or external renames.

## Candidate Brands
No candidate brands at this time.
