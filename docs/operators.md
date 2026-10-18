# Operators Architecture and Brand Management

## Scope

The set of supported mobile operator brands is determined by the keys defined in `OperatorResolver.operatorResourceMap`. The single source of truth for operator brand resolution is code (`OperatorResolver` and `OperatorBrandDatabase`), not a separate configuration list.

## Adding a Brand

Adding a new operator brand requires a complete set of seven artifacts:
1. String resource in `core/resources/src/main/res/values/donottranslate.xml`:
   `<string name="operator_<brand_key>" translatable="false"><Display Name></string>`
2. Vector drawable icon in `core/resources/src/main/res/drawable/ic_operator_<brand_key>.xml` following icon guidelines.
3. Token mappings `"<token>" to "operator_<brand_key>"` in `OperatorResolver.tokenToResKey`.
4. Resource map entry `"operator_<brand_key>" to R.string.operator_<brand_key>` in `OperatorResolver.operatorResourceMap`.
5. Icon map entry `R.string.operator_<brand_key> to R.drawable.ic_operator_<brand_key>` in `SimUtils.operatorIconMap`.
6. PLMN database entries in `core/android/src/main/assets/mcc_mnc_db.json`.
7. Provenance and documentation entries in `docs/operator_plmn_sources.md` and `docs/operator_icons.md`.

## MVNO Handling

Virtual network operators (MVNOs) are identified by the network name provided in the subscription / network operator details, because MVNOs share host network PLMN identifiers. PLMN database entries are not added for pure MVNO brands unless assigned a dedicated MCC/MNC by ITU-T E.212.

## Unsupported Networks

For networks not present in the supported brand set, the application falls back to displaying the network name provided by the subscription or the raw MCC/MNC string, using the generic `ic_unknown` icon.

## Candidate Brands

| Brand | Country | MCC | Priority |
|---|---|---|---|
| SK Telecom | South Korea | 450 | High |
| KT | South Korea | 450 | High |
| LG U+ | South Korea | 450 | High |
| Globe | Philippines | 515 | High |
| Smart | Philippines | 515 | High |
| DITO | Philippines | 515 | Medium |
| Telkomsel | Indonesia | 510 | High |
| Indosat | Indonesia | 510 | High |
| XL Axiata | Indonesia | 510 | High |
| Smartfren | Indonesia | 510 | Medium |
| Chunghwa Telecom | Taiwan | 466 | High |
| Taiwan Mobile | Taiwan | 466 | High |
| FarEasTone | Taiwan | 466 | Medium |
| Rogers | Canada | 302 | High |
| Bell | Canada | 302 | High |
| Telus | Canada | 302 | High |
| Vodacom | South Africa | 655 | High |
| MTN | South Africa | 655 | High |
| Cell C | South Africa | 655 | Medium |
| Telkom | South Africa | 655 | Medium |
| MTN | Nigeria | 621 | High |
| Globacom | Nigeria | 621 | High |
| Safaricom | Kenya | 639 | High |
| Maroc Telecom | Morocco | 604 | High |
| Orange | Morocco | 604 | High |
| Tunisie Telecom | Tunisia | 605 | High |
| Ooredoo | Tunisia | 605 | High |
| Mobilis | Algeria | 603 | High |
| Djezzy | Algeria | 603 | High |
| Vodafone | Egypt | 602 | High |
| Orange | Egypt | 602 | High |
| Etisalat | Egypt | 602 | High |
| Azercell | Azerbaijan | 400 | High |
| Bakcell | Azerbaijan | 400 | High |
| Kcell | Kazakhstan | 401 | High |
| Beeline | Kazakhstan | 401 | High |
| Vodafone | Ireland | 272 | High |
| Three | Ireland | 272 | High |
| Vodafone | Portugal | 268 | High |
| MEO | Portugal | 268 | High |
| A1 | Bulgaria | 284 | High |
| Yettel | Bulgaria | 284 | High |
| Cosmote | Greece | 202 | High |
| Vodafone | Greece | 202 | High |
| Hrvatski Telekom | Croatia | 219 | High |
| A1 | Croatia | 219 | High |
| Telekom Srbija | Serbia | 220 | High |
| Yettel | Serbia | 220 | High |
| Telia | Lithuania | 246 | High |
| Tele2 | Lithuania | 246 | High |
| LMT | Latvia | 247 | High |
| Tele2 | Latvia | 247 | High |
| Pelephone | Israel | 425 | High |
| Cellcom | Israel | 425 | High |
| GO Malta | Malta | 278 | High |
| Epic Malta | Malta | 278 | High |
| Síminn | Iceland | 274 | High |
| Nova | Iceland | 274 | High |
