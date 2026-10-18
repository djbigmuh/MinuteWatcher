import os
import glob

drawable_dir = "C:/MinuteWatcher/core/resources/src/main/res/drawable"

# List of all operator keys or discover files
xml_files = glob.glob(os.path.join(drawable_dir, "ic_operator_*.xml"))
xml_files.append(os.path.join(drawable_dir, "ic_unknown.xml"))

normative_template = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp"
    android:height="48dp"
    android:viewportWidth="48"
    android:viewportHeight="48">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M 2.5 13 L 2.5 35 L 13 45.5 L 35 45.5 L 45.5 35 L 45.5 13 L 35 2.5 L 13 2.5 Z" />
    <path
        android:fillColor="#FF005BB9"
        android:fillType="evenOdd"
        android:pathData="M 14 14 L 34 14 L 34 34 L 14 34 Z" />
</vector>
"""

for filepath in xml_files:
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(normative_template)

print(f"Normalized {len(xml_files)} operator/unknown icons.")
