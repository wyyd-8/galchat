#!/usr/bin/env python3
"""Optional CoC character-card PDF service. Run this file to listen on port 8083.

Dependencies: pip install Pillow reportlab fastapi uvicorn
Assets: character_card/fonts and character_card/templates beside this file.
"""

from __future__ import annotations

import argparse
import base64
from io import BytesIO
import logging
import os
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Lock
from typing import Any, Literal
from urllib.request import urlopen

import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, Field, field_validator
from PIL import Image, ImageOps
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas


PAGE_WIDTH = 612
PAGE_HEIGHT = 792
ROOT = Path(__file__).resolve().parent
ASSET_ROOT = ROOT / "character_card"
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
    "射击:步枪/霰弹枪": (2, 4), "神秘学": (2, 6),
    "说服": (2, 8), "锁匠": (2, 9), "跳跃": (2, 10), "投掷": (2, 11),
    "图书馆使用": (2, 12), "心理学": (2, 13), "信用评级": (2, 14),
    "医学": (3, 0), "游泳": (3, 4),
    "母语": (3, 7), "侦查": (3, 8), "追踪": (3, 9),
}
SKILL_ROWS_1920S = {
    "博物学": (0, 0), "操纵": (0, 1), "操作重型机械": (0, 2), "导航": (0, 3),
    "电气维修": (0, 4), "法律": (0, 5), "斗殴": (0, 6), "格斗:斗殴": (0, 6),
    "估价": (0, 9), "话术": (0, 10), "机械维修": (0, 11), "急救": (0, 12),
    "精神分析": (0, 13), "考古学": (0, 14),
    "科学": (1, 0), "克苏鲁神话": (1, 3), "恐吓": (1, 4), "会计": (1, 5),
    "历史": (1, 6), "聆听": (1, 7), "妙手": (1, 8), "攀爬": (1, 9),
    "骑术": (1, 10), "汽车驾驶": (1, 11), "潜行": (1, 12), "乔装": (1, 13), "取悦": (1, 14),
    "人类学": (2, 0), "闪避": (2, 1), "射击:手枪": (2, 2),
    "射击:步枪/霰弹枪": (2, 3), "神秘学": (2, 5), "说服": (2, 7),
    "锁匠": (2, 8), "跳跃": (2, 9), "投掷": (2, 10), "图书馆使用": (2, 11),
    "心理学": (2, 12), "信用评级": (2, 13), "医学": (2, 14),
    "游泳": (3, 3), "母语": (3, 7),
    "侦查": (3, 8), "追踪": (3, 9),
}
# Category headings have a writable line beneath them. Only the five bottom-right
# rows are general-purpose overflow; other blank lines belong to their category.
SKILL_CATEGORY_CELLS = {
    "1920s": {
        "操纵": [(0, 1)], "格斗": [(0, 7), (0, 8)],
        "科学": [(1, 0), (1, 1), (1, 2)], "射击": [(2, 4)],
        "生存": [(2, 6)], "艺术和手艺": [(3, 0), (3, 1), (3, 2)],
        "语言": [(3, 4), (3, 5), (3, 6)],
    },
    "modern": {
        "操纵": [(0, 1)], "格斗": [(0, 8), (0, 9)],
        "科学": [(1, 2), (1, 3), (1, 4)], "射击": [(2, 5)],
        "生存": [(2, 7)], "艺术和手艺": [(3, 1), (3, 2), (3, 3)],
        "语言": [(3, 5), (3, 6)],
    },
}
SKILL_OVERFLOW_CELLS = [(3, row) for row in range(10, 15)]
# Fallback for older/imported cards without baseValue, matching the app's catalog.
SKILL_BASE_VALUES = {
    "会计": 5, "人类学": 1, "估价": 5, "考古学": 1, "取悦": 15,
    "攀爬": 20, "信用评级": 0, "克苏鲁神话": 0, "乔装": 5,
    "汽车驾驶": 20, "电气维修": 10, "话术": 5, "急救": 30,
    "历史": 5, "恐吓": 15, "跳跃": 20, "法律": 5, "图书馆使用": 20,
    "聆听": 20, "锁匠": 1, "机械维修": 10, "医学": 1, "博物学": 10,
    "导航": 10, "神秘学": 5, "操作重型机械": 1, "说服": 10,
    "精神分析": 1, "心理学": 10, "骑术": 5, "妙手": 10,
    "侦查": 25, "潜行": 20, "游泳": 20, "投掷": 20, "追踪": 10,
    "艺术和手艺": 5, "格斗:斧": 15, "斗殴": 25, "格斗:链锯": 10,
    "格斗:连枷": 10, "格斗:绞索": 15, "格斗:矛": 20,
    "格斗:刀剑": 20, "格斗:鞭": 5, "射击:弓": 15,
    "射击:火焰喷射器": 10, "射击:手枪": 20, "射击:重武器": 10,
    "射击:机枪": 10, "射击:步枪/霰弹枪": 25, "射击:冲锋枪": 15,
    "语言": 1, "科学": 1, "科学:数学": 10, "生存": 10, "操纵": 1,
    "计算机使用": 5, "电子学": 1, "动物驯养": 5, "爆破": 1,
    "潜水": 1, "催眠": 1, "读唇": 1, "学识": 1, "炮术": 1,
}
SKILL_VALUE_X = (157.3, 279.2, 401.2, 523.2)
SKILL_LABEL_X = (79.5, 201.5, 323.5, 445.5)


def value(data: dict[str, Any] | None, name: str, default: Any = None) -> Any:
    return default if not data else data.get(name, default)


def text(data: dict[str, Any] | None, name: str) -> str:
    item = value(data, name, "")
    return "" if item is None else str(item)


def normalized_skill(name: str) -> str:
    name = ":".join(part.strip() for part in name.replace("：", ":").split(":"))
    return {"格斗:斗殴": "斗殴", "弓箭": "射击:弓", "艺术/手艺": "艺术和手艺",
            "射击:步枪/散弹枪": "射击:步枪/霰弹枪"}.get(name, name)


def skill_name(skill: dict[str, Any]) -> str:
    name = normalized_skill(text(skill, "displayName"))
    specialization = text(skill, "specialization").strip()
    category = normalized_skill(text(skill, "category"))
    if ":" not in name and category in SKILL_CATEGORY_CELLS["modern"] and name != category and name != "母语":
        name = category + ":" + (specialization or name)
    elif specialization and ":" not in name and name in {*SKILL_CATEGORY_CELLS["modern"], "母语"}:
        name += ":" + specialization
    return normalized_skill(name)


class Renderer:
    def __init__(self, card: dict[str, Any], font_path: Path, sheet_era: str = "modern"):
        self.card = card
        self.character = value(card, "character", {})
        self.skills = value(card, "skills", []) or []
        self.weapons = value(card, "weapons", []) or []
        self.profile = value(card, "profile", {})
        self.sheet_era = sheet_era
        # ReportLab ignores a second TTFont registered with an existing name.
        # Keep registration and all measurements bound to the selected font.
        self.font_name = f"CharacterCard-{font_path.stem}"
        if self.font_name not in pdfmetrics.getRegisteredFontNames():
            pdfmetrics.registerFont(TTFont(self.font_name, str(font_path)))

    def font_runs(self, raw: str):
        # The bundled Chinese fonts omit Latin punctuation such as the middle dot.
        # PDF's standard Helvetica supplies those glyphs without another asset.
        glyphs = pdfmetrics.getFont(self.font_name).face.charToGlyph
        runs = []
        for char in raw:
            font = self.font_name if ord(char) in glyphs else "Helvetica"
            if runs and runs[-1][0] == font:
                runs[-1] = (font, runs[-1][1] + char)
            else:
                runs.append((font, char))
        return runs

    def width(self, raw: str, size: float) -> float:
        return sum(pdfmetrics.stringWidth(part, font, size) for font, part in self.font_runs(raw))

    def fit(self, raw: str, max_width: float, size: float) -> str:
        if self.width(raw, size) <= max_width:
            return raw
        ellipsis = "…"
        while raw and self.width(raw + ellipsis, size) > max_width:
            raw = raw[:-1]
        return raw + ellipsis

    def write(self, c: canvas.Canvas, raw: str, x: float, y: float, size: float) -> None:
        obj = c.beginText(x, y)
        for font, part in self.font_runs(raw):
            obj.setFont(font, size)
            obj.textOut(part)
        c.drawText(obj)

    def fitted(self, raw: Any, max_width: float, size: float) -> tuple[str, float]:
        raw = str(raw).replace("\n", " ")
        width = self.width(raw, size)
        if width > max_width:
            size = max(size * 0.65, size * max_width / width)
        shown = self.fit(raw, max_width, size)
        return shown, size

    def centered(self, c: canvas.Canvas, raw: Any, cx: float, baseline: float, size: float,
                 max_width: float, bold: bool = False) -> None:
        if raw is None or raw == "":
            return
        shown, size = self.fitted(raw, max_width, size)
        width = self.width(shown, size)
        x = cx - width / 2
        self.write(c, shown, x, baseline, size)
        if bold:
            self.write(c, shown, x + 0.16, baseline, size)

    def left(self, c: canvas.Canvas, raw: Any, x: float, baseline: float, size: float,
             max_width: float, bold: bool = False) -> None:
        if raw is None or raw == "":
            return
        shown, size = self.fitted(raw, max_width, size)
        self.write(c, shown, x, baseline, size)
        if bold:
            self.write(c, shown, x + 0.16, baseline, size)

    def wrapped(self, raw: str, widths: list[float], size: float) -> list[str]:
        lines, line = [], ""
        for char in raw.replace("\r\n", "\n").replace("\r", "\n"):
            width = widths[min(len(lines), len(widths) - 1)]
            if char == "\n":
                lines.append(line)
                line = ""
            elif line and self.width(line + char, size) > width:
                carry = ""
                if len(line) > 1 and (char in "，。！？；：、）》】”’…,.!?;:)" or line[-1] in "（《【“‘("):
                    line, carry = line[:-1], line[-1]
                lines.append(line)
                line = carry + char
            else:
                line += char
        if line:
            lines.append(line)
        return lines

    def paragraph(self, c: canvas.Canvas, raw: str, first_x: float,
                  x: float, right: float, y: float, rows: int, size: float = 9) -> None:
        if not raw:
            return
        lines = self.wrapped(raw, [right - first_x, right - x], size)
        if len(lines) > rows:
            lines = lines[:rows]
            lines[-1] = self.fit(lines[-1] + "…", right - (first_x if rows == 1 else x), size)
        for row, line in enumerate(lines):
            self.write(c, line, first_x if row == 0 else x, y - row * 14.4, size)

    def state_heading(self, c: canvas.Canvas, label: str, number: Any,
                      x: float, y: float, width: float) -> None:
        c.saveState()
        c.setFillColorRGB(1, 1, 1)
        c.rect(x, y - 1, width, 11, stroke=0, fill=1)
        c.setFillColorRGB(.04, .04, .04)
        self.write(c, label, x + 1, y + 2, 5.4)
        self.centered(c, number, x + width - 7, y, 8.7, 13)
        c.restoreState()

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

        self.state_heading(c, "HP上限", value(ch, "hpMax"), 113, 609, 36)
        # The app stores current SAN, not historical starting SAN; label it honestly.
        self.state_heading(c, "当前", value(ch, "sanCurrent"), 301, 609, 36)
        self.state_heading(c, "上限", value(ch, "sanMax"), 345, 609, 38)
        self.state_heading(c, "MP上限", value(ch, "mpMax"), 466, 552, 35)

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
        luck = value(ch, "luckCurrent")
        if luck is not None and 0 <= int(luck) <= 99:
            n = int(luck)
            if n <= 7:
                cx, cy = (268.0 if n == 0 else 310.7 + n * 14.07), 530.1
            else:
                row, column = divmod(n - 8, 23)
                cx, cy = 85.4 + column * 14.07, 520.0 - row * 10.1
            self.ellipse(c, cx, cy, 7.0, 4.6)
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
            return 128.6 + number * 20.9, 598.8
        if number <= 5:
            return 128.6 + (number - 3) * 20.9, 585.9
        row, column = divmod(number - 6, 5)
        return 86.8 + column * 20.9, 573.0 - row * 12.9

    @staticmethod
    def san_track_position(number: int) -> tuple[float, float]:
        if number <= 7:
            return (414.5 if number == 0 else 441.5 + (number - 1) * 14.07), 611.1
        row, column = divmod(number - 8, 23)
        return 216.8 + column * 14.07, 600.6 - row * 10.1

    @staticmethod
    def mp_track_position(number: int) -> tuple[float, float]:
        row, column = divmod(number, 5)
        return 443.3 + column * 20.5, 541.5 - row * 12.9

    def skill_value(self, name: str, default: int = 0) -> int:
        aliases = {normalized_skill(name), name}
        for skill in self.skills:
            if skill_name(skill) in aliases:
                return int(value(skill, "value", default))
        return default

    def skill_triplet(self, c: canvas.Canvas, score: int, main_x: float, main_y: float,
                      main_size: float, sub_x: float, half_y: float, fifth_y: float) -> None:
        self.centered(c, score, main_x, main_y, main_size, 23, bold=True)
        self.centered(c, score // 2, sub_x, half_y, 5.9, 14)
        self.centered(c, score // 5, sub_x, fifth_y, 5.9, 14)

    def draw_skills(self, c: canvas.Canvas) -> None:
        skill_rows = SKILL_ROWS_1920S if self.sheet_era == "1920s" else MODERN_SKILL_ROWS
        categories = SKILL_CATEGORY_CELLS[self.sheet_era]
        changed = {}
        for skill in self.skills:
            name = skill_name(skill)
            if not name or value(skill, "value") is None:
                continue
            base = value(skill, "baseValue")
            if base is None:
                if name == "闪避":
                    base = int(value(self.character, "dex", 0) or 0) // 2
                elif name == "母语" or name.startswith("母语:"):
                    base = int(value(self.character, "edu", 0) or 0)
                else:
                    base = SKILL_BASE_VALUES.get(name, SKILL_BASE_VALUES.get(name.split(":")[0], 0))
            score = int(skill["value"])
            if score != int(base):
                changed[name] = score

        # Reserve printed skills first so input ordering cannot steal their cells.
        placements = []
        occupied = set()
        pending = []
        for name, score in changed.items():
            group = name.split(":")[0]
            cell = skill_rows.get("母语" if name.startswith("母语:") else name)
            if cell is not None and group not in categories:
                occupied.add(cell)
                placements.append((name, score, cell, name if name.startswith("母语:") else ""))
            elif cell is not None and name in {"射击:手枪", "射击:步枪/霰弹枪"}:
                occupied.add(cell)
                placements.append((name, score, cell, ""))
            else:
                pending.append((name, score))
        overflow = []
        for name, score in pending:
            group, _, specialization = name.partition(":")
            cell = next((cell for cell in categories.get(group, []) if cell not in occupied), None)
            if cell is None:
                overflow.append((name, score))
                continue
            occupied.add(cell)
            placements.append((name, score, cell, name if specialization else ""))
        for index, (name, score) in enumerate(overflow):
            if index >= len(SKILL_OVERFLOW_CELLS):
                continue
            placements.append((name, score, SKILL_OVERFLOW_CELLS[index], name))
        for name, score, cell, label in placements:
            column, row = cell
            top = 333.0 + row * 19.35
            main_y = PAGE_HEIGHT - top - 7.13
            half_y = PAGE_HEIGHT - (top - 3.63) - 5.19
            fifth_y = PAGE_HEIGHT - (top + 5.82) - 5.19
            self.skill_triplet(c, score, SKILL_VALUE_X[column], main_y, 8.1,
                               SKILL_VALUE_X[column] + 16.4, half_y, fifth_y)
            if label:
                self.left(c, label, SKILL_LABEL_X[column], main_y - 0.5, 7.2, 64)

    def draw_weapons(self, c: canvas.Canvas) -> None:
        skill_scores = {skill_name(skill): int(value(skill, "value", 0))
                        for skill in self.skills}
        unarmed = next((w for w in self.weapons if text(w, "name") in {"斗殴", "徒手战斗"}), None)
        weapons = [{"name": "徒手战斗", "skillName": "斗殴", "damage": "1D3+DB",
                    "range": "-", "attacksPerRound": "1", **(unarmed or {})}]
        weapons.extend(w for w in self.weapons if w is not unarmed)
        for row, weapon in enumerate(weapons[:6]):
            baseline = 133.8 - row * 14.2
            name = text(weapon, "name")
            weapon_skill = normalized_skill(text(weapon, "skillName") or name)
            score = skill_scores.get(weapon_skill, SKILL_BASE_VALUES.get(weapon_skill))
            if row != 0:
                self.left(c, name, 73.5, baseline, 7.8, 70)
            offset = 2.2 if self.sheet_era == "modern" else 0
            self.centered(c, score, 162.8 + offset, baseline, 7.8, 24)
            self.centered(c, score // 2 if score is not None else None, 193.4 + offset, baseline, 7.8, 24)
            self.centered(c, score // 5 if score is not None else None, 224.6 + offset, baseline, 7.8, 24)
            if row == 0:
                c.saveState()
                c.setFillColorRGB(1, 1, 1)
                c.rect(244 + offset, 131.3, 203, 12.5, fill=1, stroke=0)
                c.restoreState()
            self.centered(c, value(weapon, "damage"), 266.6 + offset, baseline, 6.8, 44)
            self.centered(c, value(weapon, "range"), 312.6 + offset, baseline, 7.2, 30)
            self.centered(c, value(weapon, "attacksPerRound"), 350.6 + offset, baseline, 7.2, 28)
            self.centered(c, value(weapon, "ammoCapacity"), 390.4 + offset, baseline, 7.2, 30)
            self.centered(c, value(weapon, "malfunction"), 430.1 + offset, baseline, 7.2, 28)

    def page_two(self, c: canvas.Canvas) -> None:
        p = self.profile
        fields = [
            ("appearance", 116, 70, 299, 699.0),
            ("traits", 333, 312, 464, 699.0),
            ("ideology", 127, 70, 299, 641.9),
            ("injuriesAndScars", 368, 312, 542, 641.9),
            ("significantPeople", 117, 70, 299, 584.7),
            ("phobiasAndManias", 391, 312, 542, 584.7),
            ("meaningfulLocations", 137, 70, 299, 527.6),
            ("treasuredPossessions", 117, 70, 299, 470.4),
        ]
        for field, first_x, x, right, y in fields:
            self.paragraph(c, text(p, field), first_x, x, right, y, 4)

        equipment = self.wrapped(text(p, "equipmentText"), [122], 9)
        if len(equipment) > 20:
            equipment = equipment[:20]
            equipment[-1] = self.fit(equipment[-1] + "…", 122, 9)
        for index, line in enumerate(equipment):
            column, row = divmod(index, 10)
            self.write(c, line, 76 + column * 137, 352.7 - row * 14.4, 9)
        self.left(c, value(p, "spendingLevel"), 417, 352.7, 9.8, 85)
        self.left(c, value(p, "cash"), 397, 338.3, 9.8, 105)
        self.paragraph(c, text(p, "assetsText"), 382, 356, 542, 323.9, 8)

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


logger = logging.getLogger(__name__)
app = FastAPI(title="Character-card PDF", docs_url=None, redoc_url=None)
app.add_middleware(
    CORSMiddleware,
    allow_origins=[origin.strip() for origin in os.getenv(
        "CHARACTER_CARD_ALLOWED_ORIGINS",
        "http://localhost:5173,http://127.0.0.1:5173",
    ).split(",") if origin.strip()],
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type"],
)
# Serialize rendering and registration, since ReportLab's font registry is shared.
render_lock = Lock()


class CardPayload(BaseModel):
    character: dict[str, Any]
    skills: list[dict[str, Any]] = Field(default_factory=list, max_length=300)
    weapons: list[dict[str, Any]] = Field(default_factory=list, max_length=100)
    profile: dict[str, Any] | None = None

    @field_validator("character")
    @classmethod
    def validate_character(cls, character):
        if not isinstance(character.get("name"), str) or not character["name"].strip():
            raise ValueError("人物卡缺少姓名")
        source = character.get("image")
        # HTTP clients must never instruct the renderer to read server files or URLs.
        if source and (not isinstance(source, str) or not source.startswith(
                ("data:image/png;base64,", "data:image/jpeg;base64,", "data:image/webp;base64,"))):
            raise ValueError("头像仅支持内嵌 PNG、JPEG 或 WebP 图片")
        if source and len(source) > 4_000_000:
            raise ValueError("头像过大")
        return character


class ExportRequest(BaseModel):
    card: CardPayload
    background: Literal["1920s", "modern"]
    fontIndex: Literal[0, 1, 2]


@app.get("/health")
def health():
    try:
        for font_index in range(3):
            resolve_font(font_index)
        for era in ("1920s", "modern"):
            resolve_backgrounds(DEFAULT_TEMPLATE_DIR, era)
    except OSError:
        return JSONResponse({"service": "character-card-pdf", "available": False}, status_code=503,
                            headers={"Cache-Control": "no-store"})
    return JSONResponse({"service": "character-card-pdf", "available": True},
                        headers={"Cache-Control": "no-store"})


@app.post("/export")
def export_pdf(request: ExportRequest):
    try:
        font = resolve_font(request.fontIndex)
        backgrounds = resolve_backgrounds(DEFAULT_TEMPLATE_DIR, request.background)
    except OSError as exc:
        raise HTTPException(503, "人物卡模板或字体不可用，请检查导出服务") from exc
    try:
        with TemporaryDirectory(prefix="galchat-card-") as directory, render_lock:
            output = Path(directory) / "character-card.pdf"
            Renderer(request.card.model_dump(), font, request.background).render(output, backgrounds)
            pdf = output.read_bytes()
    except (ValueError, TypeError, KeyError) as exc:
        raise HTTPException(422, "人物卡数据无法生成 PDF，请检查人物卡内容") from exc
    except Exception as exc:
        logger.exception("Character-card PDF generation failed")
        raise HTTPException(500, "PDF 生成失败，请重试") from exc
    return Response(pdf, media_type="application/pdf", headers={
        "Content-Disposition": 'attachment; filename="character-card.pdf"',
        "Cache-Control": "no-store",
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8083)
    args = parser.parse_args()
    uvicorn.run(app, host=args.host, port=args.port)
