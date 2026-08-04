#!/usr/bin/env python3
"""Render a CoC investigator sheet from CharacterCardVO JSON."""

from __future__ import annotations

import argparse
import base64
from io import BytesIO
import json
from pathlib import Path
from typing import Any
from urllib.request import urlopen

from PIL import Image, ImageOps
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas


PAGE_WIDTH = 612
PAGE_HEIGHT = 792
ROOT = Path(__file__).resolve().parent
ASSET_ROOT = ROOT / "assets"
DEFAULT_TEMPLATE_DIR = ASSET_ROOT / "templates"
SHEET_BACKGROUNDS = {
    "1920s": ("coc-character-sheet-1920s-front.png", "coc-character-sheet-1920s-back.png"),
    "modern": ("coc-character-sheet-modern-front.png", "coc-character-sheet-modern-back.png"),
}
FONT_FILES = {
    0: ASSET_ROOT / "fonts/ZCOOLXiaoWei-Regular.ttf",
    1: ASSET_ROOT / "fonts/ZhiMangXing-Regular.ttf",
    2: ASSET_ROOT / "fonts/MaShanZheng-Regular.ttf",
}

MODERN_SKILL_ROWS = {
    "博物学": (0, 0), "操纵": (0, 1), "操作重型机械": (0, 2), "导航": (0, 3),
    "电气维修": (0, 4), "电子学": (0, 5), "法律": (0, 6), "斗殴": (0, 7),
    "格斗:斗殴": (0, 7), "估价": (0, 10), "话术": (0, 11), "机械维修": (0, 12),
    "急救": (0, 13), "计算机使用": (0, 14),
    "精神分析": (1, 0), "考古学": (1, 1), "科学": (1, 2), "克苏鲁神话": (1, 5),
    "恐吓": (1, 6), "会计": (1, 7), "历史": (1, 8), "聆听": (1, 9),
    "妙手": (1, 10), "攀爬": (1, 11), "汽车驾驶": (1, 12), "潜行": (1, 13),
    "乔装": (1, 14),
    "取悦": (2, 0), "人类学": (2, 1), "闪避": (2, 2), "射击:手枪": (2, 3),
    "射击:弓": (2, 5), "弓箭": (2, 5), "神秘学": (2, 6), "生存": (2, 7),
    "说服": (2, 8), "锁匠": (2, 9), "跳跃": (2, 10), "投掷": (2, 11),
    "图书馆使用": (2, 12), "心理学": (2, 13), "信用评级": (2, 14),
    "医学": (3, 0), "艺术和手艺": (3, 1), "游泳": (3, 4), "语言": (3, 5),
    "母语": (3, 6), "侦查": (3, 7), "追踪": (3, 8),
}
MODERN_CUSTOM_SKILL_CELLS = [
    (0, 8), (0, 9), (3, 2), (3, 3), (3, 9), (3, 10), (3, 11), (3, 12), (3, 13), (3, 14),
]
SKILL_ROWS_1920S = {
    "博物学": (0, 0), "操纵": (0, 1), "操作重型机械": (0, 2), "导航": (0, 3),
    "电气维修": (0, 4), "法律": (0, 5), "斗殴": (0, 6), "格斗:斗殴": (0, 6),
    "估价": (0, 9), "话术": (0, 10), "机械维修": (0, 11), "急救": (0, 12),
    "精神分析": (0, 13), "考古学": (0, 14),
    "科学": (1, 0), "克苏鲁神话": (1, 3), "恐吓": (1, 4), "会计": (1, 5),
    "历史": (1, 6), "聆听": (1, 7), "妙手": (1, 8), "攀爬": (1, 9),
    "骑术": (1, 10), "汽车驾驶": (1, 11), "潜行": (1, 12), "乔装": (1, 13), "取悦": (1, 14),
    "人类学": (2, 0), "闪避": (2, 1), "射击:手枪": (2, 2), "射击:弓": (2, 6),
    "弓箭": (2, 6), "神秘学": (2, 4), "生存": (2, 5), "说服": (2, 7),
    "锁匠": (2, 8), "跳跃": (2, 9), "投掷": (2, 10), "图书馆使用": (2, 11),
    "心理学": (2, 12), "信用评级": (2, 13), "医学": (2, 14),
    "艺术和手艺": (3, 0), "游泳": (3, 3), "语言": (3, 4), "母语": (3, 6),
    "侦查": (3, 7), "追踪": (3, 8),
}
CUSTOM_SKILL_CELLS_1920S = [
    (0, 7), (0, 8), (1, 1), (1, 2), (3, 1), (3, 2), (3, 5),
    (3, 9), (3, 10), (3, 11), (3, 12), (3, 13), (3, 14),
]
SKILL_VALUE_X = (157.3, 279.2, 401.2, 523.2)
SKILL_LABEL_X = (79.5, 201.5, 323.5, 445.5)


def value(data: dict[str, Any] | None, name: str, default: Any = None) -> Any:
    return default if not data else data.get(name, default)


def text(data: dict[str, Any] | None, name: str) -> str:
    item = value(data, name, "")
    return "" if item is None else str(item)


def normalized_skill(name: str) -> str:
    return name.strip().replace("：", ":")


class Renderer:
    def __init__(self, card: dict[str, Any], font_path: Path, sheet_era: str = "modern"):
        self.card = card
        self.character = value(card, "character", {})
        self.skills = value(card, "skills", []) or []
        self.weapons = value(card, "weapons", []) or []
        self.profile = value(card, "profile", {})
        self.sheet_era = sheet_era
        pdfmetrics.registerFont(TTFont("Handwriting", str(font_path)))

    @staticmethod
    def fit(raw: str, max_width: float, size: float) -> str:
        if pdfmetrics.stringWidth(raw, "Handwriting", size) <= max_width:
            return raw
        ellipsis = "…"
        while raw and pdfmetrics.stringWidth(raw + ellipsis, "Handwriting", size) > max_width:
            raw = raw[:-1]
        return raw + ellipsis

    def centered(self, c: canvas.Canvas, raw: Any, cx: float, baseline: float, size: float,
                 max_width: float, bold: bool = False) -> None:
        if raw is None or raw == "":
            return
        shown = self.fit(str(raw), max_width, size)
        width = pdfmetrics.stringWidth(shown, "Handwriting", size)
        x = cx - width / 2
        c.setFont("Handwriting", size)
        c.drawString(x, baseline, shown)
        if bold:
            c.drawString(x + 0.16, baseline, shown)

    def left(self, c: canvas.Canvas, raw: Any, x: float, baseline: float, size: float,
             max_width: float, bold: bool = False) -> None:
        if raw is None or raw == "":
            return
        shown = self.fit(str(raw), max_width, size)
        c.setFont("Handwriting", size)
        c.drawString(x, baseline, shown)
        if bold:
            c.drawString(x + 0.16, baseline, shown)

    @staticmethod
    def ellipse(c: canvas.Canvas, cx: float, cy: float, rx: float, ry: float) -> None:
        c.ellipse(cx - rx, cy - ry, cx + rx, cy + ry)

    @staticmethod
    def check(c: canvas.Canvas, cx: float, cy: float) -> None:
        path = c.beginPath()
        path.moveTo(cx - 5.0, cy)
        path.lineTo(cx - 1.4, cy - 3.5)
        path.lineTo(cx + 5.5, cy + 4.7)
        c.drawPath(path, stroke=1, fill=0)

    def portrait(self, c: canvas.Canvas) -> None:
        source = text(self.character, "image")
        if not source:
            return
        try:
            if source.startswith("data:"):
                payload = base64.b64decode(source.split(",", 1)[1])
            elif source.startswith(("http://", "https://")):
                with urlopen(source, timeout=5) as response:
                    payload = response.read()
            else:
                payload = Path(source).expanduser().read_bytes()
            with Image.open(BytesIO(payload)) as original:
                image = ImageOps.fit(original.convert("RGB"), (460, 575), Image.Resampling.LANCZOS)
                buffer = BytesIO()
                image.save(buffer, format="PNG")
                buffer.seek(0)
                c.drawImage(ImageReader(buffer), 461, 623, 92, 115, preserveAspectRatio=False, mask="auto")
        except (OSError, ValueError):
            pass

    def page_one(self, c: canvas.Canvas) -> None:
        ch = self.character
        c.setFillColorRGB(0.04, 0.04, 0.04)
        c.setStrokeColorRGB(0.03, 0.03, 0.03)

        self.portrait(c)
        basics = [
            ("name", 133.2, 710.5, 10.3, 75), ("playerName", 133.2, 695.4, 9.6, 75),
            ("occupation", 133.2, 680.1, 9.6, 75), ("age", 104.2, 664.4, 9.6, 28),
            ("sex", 164.9, 664.4, 9.6, 31), ("residence", 133.2, 648.4, 9.6, 75),
            ("birthplace", 133.2, 632.2, 9.6, 75),
        ]
        for field, cx, baseline, size, width in basics:
            self.centered(c, value(ch, field), cx, baseline, size, width, bold=True)

        attr_rows = {"str": (240, 263, 698.35, 705.94, 693.12),
                     "con": (240, 263, 667.30, 674.89, 662.07),
                     "siz": (240, 263, 637.15, 644.66, 631.12),
                     "dex": (324, 347, 698.35, 705.94, 693.12),
                     "app": (324, 347, 667.30, 674.89, 662.07),
                     "edu": (324, 347, 637.15, 644.66, 631.12),
                     "intValue": (412, 435, 698.35, 705.94, 693.12),
                     "pow": (412, 435, 667.30, 674.89, 662.07)}
        for field, (main_x, sub_x, main_y, half_y, fifth_y) in attr_rows.items():
            raw = value(ch, field)
            if raw is None:
                continue
            number = int(raw)
            self.centered(c, number, main_x, main_y, 10.2, 28, bold=True)
            self.centered(c, number // 2, sub_x, half_y, 6.8, 16)
            self.centered(c, number // 5, sub_x, fifth_y, 6.8, 16)
        self.centered(c, value(ch, "mov"), 413.5, 636.5, 11, 28, bold=True)

        self.centered(c, value(ch, "hpCurrent"), 130.7, 609.0, 8.7, 24, bold=True)
        self.centered(c, value(ch, "sanCurrent"), 318.8, 609.0, 8.7, 26, bold=True)
        self.centered(c, value(ch, "sanMax"), 364.7, 609.0, 8.7, 26, bold=True)
        self.centered(c, value(ch, "mpCurrent"), 483.5, 551.4, 8.7, 24, bold=True)

        self.draw_states(c)
        self.draw_skills(c)
        self.draw_weapons(c)
        self.centered(c, value(ch, "damageBonus"), 523.8, 124.6, 9.2, 38, bold=True)
        self.centered(c, value(ch, "build"), 523.8, 94.7, 10, 28, bold=True)
        dodge = self.skill_value("闪避", default=(int(value(ch, "dex", 0)) // 2))
        self.skill_triplet(c, dodge, 511.9, 67.8, 9.2, 536.0, 72.9, 59.6)

    def draw_states(self, c: canvas.Canvas) -> None:
        ch = self.character
        c.setLineWidth(1.05)
        hp = value(ch, "hpCurrent")
        if hp is not None and 0 <= int(hp) <= 20:
            cx, cy = self.hp_track_position(int(hp))
            self.ellipse(c, cx, cy, 8.5, 5.25)
        san = value(ch, "sanCurrent")
        if san is not None and 0 <= int(san) <= 99:
            cx, cy = self.san_track_position(int(san))
            self.ellipse(c, cx, cy, 8.5, 5.25)
        mp = value(ch, "mpCurrent")
        if mp is not None and 0 <= int(mp) <= 24:
            cx, cy = self.mp_track_position(int(mp))
            self.ellipse(c, cx, cy, 8.5, 5.25)
        if value(ch, "dying", False):
            self.ellipse(c, 98.4, 596.4, 14.0, 5.2)
        if value(ch, "unconscious", False):
            self.ellipse(c, 98.4, 585.2, 14.0, 5.2)
        c.setLineWidth(1.6)
        c.setLineCap(1)
        c.setLineJoin(1)
        if value(ch, "majorWound", False):
            self.check(c, 91.6, 613.2)
        if value(ch, "temporaryInsanity", False):
            self.check(c, 216.4, 613.2)

    @staticmethod
    def hp_track_position(number: int) -> tuple[float, float]:
        if number <= 2:
            return 128.8 + number * 19.2, 597.2
        if number <= 5:
            return 128.8 + (number - 3) * 19.2, 585.4
        row, column = divmod(number - 6, 5)
        return 90.4 + column * 19.2, 573.6 - row * 11.6

    @staticmethod
    def san_track_position(number: int) -> tuple[float, float]:
        if number <= 30:
            return 79.6 + number * 14.4, 605.6
        if number <= 53:
            return 214.0 + (number - 31) * 14.4, 593.6
        if number <= 76:
            return 214.0 + (number - 54) * 14.4, 581.6
        return 214.0 + (number - 77) * 14.4, 569.6

    @staticmethod
    def mp_track_position(number: int) -> tuple[float, float]:
        row, column = divmod(number, 5)
        return 450.0 + column * 18.4, 539.0 - row * 11.3

    def skill_value(self, name: str, default: int = 0) -> int:
        aliases = {normalized_skill(name), name}
        for skill in self.skills:
            if normalized_skill(text(skill, "displayName")) in aliases:
                return int(value(skill, "value", default))
        return default

    def skill_triplet(self, c: canvas.Canvas, score: int, main_x: float, main_y: float,
                      main_size: float, sub_x: float, half_y: float, fifth_y: float) -> None:
        self.centered(c, score, main_x, main_y, main_size, 23, bold=True)
        self.centered(c, score // 2, sub_x, half_y, 5.9, 14)
        self.centered(c, score // 5, sub_x, fifth_y, 5.9, 14)

    def draw_skills(self, c: canvas.Canvas) -> None:
        skill_rows = SKILL_ROWS_1920S if self.sheet_era == "1920s" else MODERN_SKILL_ROWS
        custom_cells = CUSTOM_SKILL_CELLS_1920S if self.sheet_era == "1920s" else MODERN_CUSTOM_SKILL_CELLS
        occupied: set[tuple[int, int]] = set()
        custom_index = 0
        for skill in self.skills:
            name = normalized_skill(text(skill, "displayName"))
            cell = skill_rows.get(name)
            custom = False
            if cell is None or cell in occupied:
                while custom_index < len(custom_cells) and custom_cells[custom_index] in occupied:
                    custom_index += 1
                if custom_index >= len(custom_cells):
                    continue
                cell = custom_cells[custom_index]
                custom_index += 1
                custom = True
            occupied.add(cell)
            column, row = cell
            top = 333.0 + row * 19.35
            main_y = PAGE_HEIGHT - top - 7.13
            half_y = PAGE_HEIGHT - (top - 3.63) - 5.19
            fifth_y = PAGE_HEIGHT - (top + 5.82) - 5.19
            score = int(value(skill, "value", 0))
            self.skill_triplet(c, score, SKILL_VALUE_X[column], main_y, 8.1,
                               SKILL_VALUE_X[column] + 16.4, half_y, fifth_y)
            if custom or ((":" in name or name == "弓箭") and name != "格斗:斗殴"):
                self.left(c, name, SKILL_LABEL_X[column], main_y + 0.2, 7.2, 66)

    def draw_weapons(self, c: canvas.Canvas) -> None:
        skill_scores = {normalized_skill(text(skill, "displayName")): int(value(skill, "value", 0))
                        for skill in self.skills}
        for row, weapon in enumerate(self.weapons[:10]):
            baseline = 133.8 - row * 13.5
            name = text(weapon, "name")
            skill_name = normalized_skill(text(weapon, "skillName") or name)
            score = skill_scores.get(skill_name, skill_scores.get(name, 0))
            if row != 0 or name not in {"斗殴", "徒手战斗"}:
                self.left(c, name, 73.5, baseline, 7.8, 70)
            self.centered(c, score, 164.0, baseline, 7.8, 24)
            self.centered(c, score // 2, 196.0, baseline, 7.8, 24)
            self.centered(c, score // 5, 228.0, baseline, 7.8, 24)
            if row != 0 or name not in {"斗殴", "徒手战斗"}:
                self.centered(c, value(weapon, "damage"), 274.0, baseline, 6.8, 55)
            self.centered(c, value(weapon, "range"), 315.0, baseline, 7.2, 30)
            self.centered(c, value(weapon, "attacksPerRound"), 353.0, baseline, 7.2, 30)
            self.centered(c, value(weapon, "ammoCapacity"), 393.0, baseline, 7.2, 30)
            self.centered(c, value(weapon, "malfunction"), 434.0, baseline, 7.2, 31)

    def page_two(self, c: canvas.Canvas) -> None:
        p = self.profile
        fields = [
            ("appearance", 133, 699.0, 10.2, 166), ("traits", 359, 699.0, 10.2, 103),
            ("ideology", 146, 641.9, 10.2, 153), ("injuriesAndScars", 406, 641.9, 10.2, 137),
            ("significantPeople", 133, 584.7, 10.2, 166), ("phobiasAndManias", 418, 584.7, 10.2, 125),
            ("meaningfulLocations", 158, 527.6, 10.2, 141),
            ("treasuredPossessions", 133, 470.4, 10.2, 166),
        ]
        for field, x, baseline, size, width in fields:
            self.left(c, value(p, field), x, baseline, size, width)

        equipment = text(p, "equipmentText").splitlines()
        left_count = min(10, (len(equipment) + 1) // 2)
        for index, line in enumerate(equipment[:20]):
            if index < left_count:
                column, row = 0, index
            else:
                column, row = 1, index - left_count
            self.left(c, line, 90 + column * 137, 352.7 - row * 14.4, 9.8, 116)
        self.left(c, value(p, "spendingLevel"), 417, 352.7, 9.8, 85)
        self.left(c, value(p, "cash"), 397, 338.3, 9.8, 105)
        assets = text(p, "assetsText").splitlines()
        for index, line in enumerate(assets[:8]):
            self.left(c, line, 397, 323.9 - index * 14.4, 9.8, 105)

    def render(self, output_pdf: Path, backgrounds: tuple[Path, Path]) -> None:
        output_pdf.parent.mkdir(parents=True, exist_ok=True)
        c = canvas.Canvas(str(output_pdf), pagesize=(PAGE_WIDTH, PAGE_HEIGHT), pageCompression=1)
        c.setTitle(f"{text(self.character, 'name') or '调查员'}人物卡")
        c.setAuthor("galchat")
        c.drawImage(str(backgrounds[0]), 0, 0, PAGE_WIDTH, PAGE_HEIGHT, preserveAspectRatio=False)
        self.page_one(c)
        c.showPage()
        c.drawImage(str(backgrounds[1]), 0, 0, PAGE_WIDTH, PAGE_HEIGHT, preserveAspectRatio=False)
        self.page_two(c)
        c.showPage()
        c.save()


def resolve_font(font_index: int) -> Path:
    path = FONT_FILES[font_index]
    if not path.is_file():
        raise FileNotFoundError(f"Font {font_index} not found: {path}")
    return path


def resolve_backgrounds(template_dir: Path, sheet_era: str) -> tuple[Path, Path]:
    backgrounds = tuple(template_dir / name for name in SHEET_BACKGROUNDS[sheet_era])
    missing = [path for path in backgrounds if not path.is_file()]
    if missing:
        raise FileNotFoundError(f"Character sheet background not found: {', '.join(map(str, missing))}")
    return backgrounds


def render(input_json: Path, output_pdf: Path, font: Path, sheet_era: str,
           template_dir: Path = DEFAULT_TEMPLATE_DIR) -> None:
    card = json.loads(input_json.read_text(encoding="utf-8"))
    Renderer(card, font, sheet_era).render(output_pdf, resolve_backgrounds(template_dir, sheet_era))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="CharacterCardVO JSON file")
    parser.add_argument("output", type=Path, help="output PDF file")
    parser.add_argument("--background", choices=("1920s", "modern"), required=True,
                        help="required character sheet background pair")
    parser.add_argument("--template-dir", type=Path, default=DEFAULT_TEMPLATE_DIR,
                        help="directory containing the four bundled sheet background images")
    parser.add_argument("--font-index", type=int, choices=FONT_FILES, required=True,
                        help="required font number: 0=XiaoWei, 1=ZhiMangXing, 2=MaShanZheng")
    args = parser.parse_args()
    render(args.input, args.output, resolve_font(args.font_index), args.background, args.template_dir)


if __name__ == "__main__":
    main()
