# PLMN Completeness Audit Tool

Tooling for auditing the completeness and accuracy of the MinuteWatcher PLMN database (`core/android/src/main/assets/mcc_mnc_db.json`) against external reference datasets.

## Purpose

This tool cross-references the application's PLMN mapping database against the open dataset `p1_mnc.json` from P1sec MCC_MNC repository and verified primary sources (ITU-T E.212 operational bulletins, national telecommunications regulators).

It categorizes discrepancies into:
- **MISSING:** Operational mobile networks or MVNOs missing from the project database.
- **PROJECT_ONLY:** PLMN codes in the project database not marked as operational in the dataset.
- **CONFLICT:** PLMN codes mapped to different operator brands between the project database and reference dataset.
- **EXCLUDE_*:** Non-target networks (GSM-R railway, satellite, test networks, shared MVNOs, or defunct networks).

## Usage

Run the audit script from the repository root:

```powershell
python tools/plmn_audit/audit_plmn.py
```

The script is idempotent and can be re-run at any time.

## Directory Structure

- `audit_plmn.py`: Main audit script (Python 3, standard library only).
- `cache/`: Local dataset cache and metadata (`p1_mnc.json`, `metadata.json`).
- `reports/`: Generated markdown audit report (`plmn_audit_report.md`).

## Report Structure

The generated report `reports/plmn_audit_report.md` contains:
1. **Executive Summary:** Overall statistics and totals by category.
2. **Decision Rules:** Rules applied during PLMN database integration.
3. **Missing Operational PLMNs Table:** Candidate PLMNs for inclusion with country, brand, suggested brand key, and verification source.
4. **Project-Only PLMNs Table:** Existing project PLMNs and their verification status against ITU-T E.212 and regulators.
5. **Brand Conflicts Table:** Mappings requiring brand name resolution or re-alignment.
6. **Excluded Networks Summary:** Classification breakdown of non-target networks.
