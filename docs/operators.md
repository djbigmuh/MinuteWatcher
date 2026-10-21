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

## Unsupported Networks
When a network is encountered whose MCC/MNC is absent from `mcc_mnc_db.json` or whose brand is unmapped, the application falls back to displaying the subscription network name or the MCC/MNC identifier string, using the generic `ic_unknown` icon.

## Candidate Brands
The following table lists candidate brands under consideration for addition across specified regions, categorized by MCC:

| Brand | Country | MCC | Priority |
|---|---|---|---|
| ATOM | Myanmar | 414 | High |
| Telecel | Ghana | 620 | Medium |
| Nova | Greece | 202 | Low |
| VOX | Paraguay | 744 | Low |
| RED | El Salvador | 706 | Low |
| Unitel | Angola | 631 | Low |
| Econet | Zimbabwe | 648 | Low |
| Telkom | South Africa | 655 | Low |
| Zantel | Tanzania | 640 | Low |
| VivaCell-MTS | Armenia | 283 | Low |
| Ucom | Armenia | 283 | Low |
