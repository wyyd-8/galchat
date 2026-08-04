# CharacterCardVO PDF rendering

The renderer consumes the JSON returned by the character-card API and writes the two-page modern investigator sheet.

```bash
python/character_card_pdf.py character-card.json character-card.pdf \
  --background modern \
  --font-index 0
```

A complete input example is available at `python/character_card/examples/character-card.sample.json`.

The renderer and all of its supporting files are grouped under `python/character_card`. The four bundled PNG backgrounds live in `python/character_card/assets/templates`; the renderer does not read the complete rulebook PDF. The required `--background` runtime parameter accepts `1920s` or `modern`. There is no automatic background selection.

Install the renderer dependencies with:

```bash
python -m pip install -r python/character_card/requirements.txt
```

## Fonts

The required `--font-index` runtime parameter accepts:

- `0`: ZCOOL XiaoWei.
- `1`: Zhi Mang Xing.
- `2`: Ma Shan Zheng.

## Field behavior

- `character.playerName` fills the player field and `character.image` fills the portrait box.
- `hpCurrent`, `sanCurrent`, and `mpCurrent` circle the matching printed track values.
- `majorWound` and `temporaryInsanity` check their boxes.
- `dying` and `unconscious` circle their printed states.
- Long values are truncated with an ellipsis.
- Known skills use their printed rows; custom or additional specializations use the available blank rows until the sheet is full.
