# Character-card PDF generator

This directory contains the complete implementation behind the outer
`python/character_card_pdf.py` command entry point.

- `renderer.py`: rendering implementation and CLI argument handling.
- `assets/`: bundled sheet templates, fonts, and their licenses.
- `examples/`: sample `CharacterCardVO` JSON input.
- `tests/`: renderer unit tests.
- `requirements.txt`: isolated runtime dependencies.

From the repository root:

```bash
python -m pip install -r python/character_card/requirements.txt
python python/character_card_pdf.py \
  python/character_card/examples/character-card.sample.json \
  character-card.pdf \
  --background modern \
  --font-index 0
```
