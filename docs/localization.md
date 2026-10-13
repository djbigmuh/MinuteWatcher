# Localization Guidelines

This document outlines the best practices and requirements for localizing the MinuteWatcher application.

## Plural Rules

Different languages have different rules for pluralizing nouns based on quantity. Android uses the CLDR (Common Locale Data Repository) plural rules.

### Standard Requirements

The required cardinal plural categories for all supported app languages according to Unicode CLDR are listed below:

| Language Tag | Language              | Required Cardinal Plural Categories          | Notes                                                                             |
|:-------------|:----------------------|:---------------------------------------------|:----------------------------------------------------------------------------------|
| `en`         | English               | `one`, `other`                               |                                                                                   |
| `ar`         | Arabic                | `zero`, `one`, `two`, `few`, `many`, `other` | RTL direction                                                                     |
| `ur`         | Urdu                  | `one`, `other`                               | RTL direction                                                                     |
| `bn`         | Bengali               | `one`, `other`                               |                                                                                   |
| `de`         | German                | `one`, `other`                               |                                                                                   |
| `es`         | Spanish               | `one`, `many`, `other`                       | `many` is selected only for values 1,000,000+; added for CLDR and Lint compliance |
| `fr`         | French                | `one`, `many`, `other`                       | `many` is selected only for values 1,000,000+; added for CLDR and Lint compliance |
| `hi`         | Hindi                 | `one`, `other`                               |                                                                                   |
| `id`         | Indonesian            | `other`                                      |                                                                                   |
| `ja`         | Japanese              | `other`                                      |                                                                                   |
| `pt-BR`      | Portuguese (Brazil)   | `one`, `many`, `other`                       | `many` is selected only for values 1,000,000+; added for CLDR and Lint compliance |
| `pt-PT`      | Portuguese (Portugal) | `one`, `many`, `other`                       | `many` is selected only for values 1,000,000+; added for CLDR and Lint compliance |
| `ru`         | Russian               | `one`, `few`, `many`, `other`                |                                                                                   |
| `zh`         | Chinese               | `other`                                      |                                                                                   |
| `ta`         | Tamil                 | `one`, `other`                               |                                                                                   |
| `te`         | Telugu                | `one`, `other`                               |                                                                                   |
| `mr`         | Marathi               | `one`, `other`                               |                                                                                   |
| `gu`         | Gujarati              | `one`, `other`                               |                                                                                   |
| `pa`         | Punjabi               | `one`, `other`                               |                                                                                   |
| `vi`         | Vietnamese            | `other`                                      |                                                                                   |
| `tr`         | Turkish               | `one`, `other`                               |                                                                                   |
| `ko`         | Korean                | `other`                                      |                                                                                   |
| `it`         | Italian               | `one`, `many`, `other`                       | `many` is selected only for values 1,000,000+; added for CLDR and Lint compliance |
| `fa`         | Persian               | `one`, `other`                               | RTL direction                                                                     |
| `ha`         | Hausa                 | `one`, `other`                               |                                                                                   |
| `yo`         | Yoruba                | `other`                                      |                                                                                   |
| `jv`         | Javanese              | `other`                                      |                                                                                   |
| `bho`        | Bhojpuri              | `one`, `other`                               |                                                                                   |

> [!IMPORTANT]
> Always use the full set of quantities according to CLDR/Lint requirements. Missing any quantity can cause Lint fatal
> errors or incorrect display at runtime. Re-check this table whenever AGP, `compileSdk`, or CLDR versions are updated.

### Heuristics and Quality

- **Multiples of 1,000,000**: In languages like Spanish and French, the `many` quantity is used for large numbers (e.g., exactly 1,000,000). Even if the application currently deals with smaller numbers (e.g., minutes in a month), providing these quantities ensures compliance with Android Lint and future-proofs the translations.
- **Verification**: Run `./gradlew lintDebug` to verify that all required quantities are present and no `MissingTranslation` or `PluralsCandidate` issues exist.

## Source Language and Translation Provenance

- **Native Source Language**: The native (source) language of the UI and internal documentation is Russian.
- **Fallback Language**: The default resource set (`values/`) is English, serving as the fallback for unsupported
  locales.
- **Machine Generation**: All translations except Russian were generated automatically using a Large Language Model
  (LLM) and may not have been proofread by native speakers.
- **Feedback & Corrections**: User reports on language inaccuracies are welcomed via the developer contact channel
  (e-mail available in the About dialog). Native speaker corrections are integrated key by key to replace machine
  values.
- **Mandatory Compliance**: The plural category table and Android Lint requirements remain strictly mandatory regardless
  of translation provenance.
