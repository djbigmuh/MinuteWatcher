# IconForge Tooling

Reproducible tooling for generating operator vector icons and managing the 206-brand reference table (`brands.tsv`).

## Components

- `brands.tsv`: Reference table for all 206 operator brands.
- `fetch_simple_icons.py`: Downloads and verifies simple-icons catalog.
- `fetch_fonts.py`: Fetches Liberation Sans Bold (SIL OFL 1.1) glyph outlines.
- `compose_icon.py`: Deterministically builds Android VectorDrawable XML.
- `validate_icons.py`: Validates asset compliance against normative rules.
- `render_icons.py`: Renders preview PNGs.

## Requirements

Python dependencies in `requirements.txt`:

```bash
pip install -r requirements.txt
```

## Usage

```bash
python fetch_simple_icons.py
python fetch_fonts.py
python validate_icons.py
```
