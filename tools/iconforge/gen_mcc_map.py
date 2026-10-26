import json

with open('core/android/src/main/assets/mcc_mnc_db.json', 'r') as f:
    db = json.load(f)

mcc_lens = {}
for plmn in db:
    mcc = plmn[:3]
    mnc_len = len(plmn) - 3
    if mcc not in mcc_lens:
        mcc_lens[mcc] = set()
    mcc_lens[mcc].add(mnc_len)

print(f"Total MCCs in DB: {len(mcc_lens)}")
for mcc in sorted(mcc_lens.keys()):
    lens_str = ", ".join(str(x) for x in sorted(mcc_lens[mcc]))
    print(f'            "{mcc}" to setOf({lens_str}),')
