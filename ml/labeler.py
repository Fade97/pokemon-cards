# SPDX-License-Identifier: GPL-3.0-or-later
"""Independent labeler for real photos: a local vision-language model reads name, collector number and language.

It only sees the raw photo, never the pipeline's crop or prediction, so its errors are independent of the
pipeline's. The reading is matched against the manifest (language + number/total + name) to get a card key.

Model: Qwen3-VL-4B-Instruct, 4-bit NF4 via bitsandbytes (~3.5 GB VRAM).
"""
from __future__ import annotations

import json
import re
import unicodedata
from difflib import SequenceMatcher

import numpy as np

from common import Card, number_key

MODEL_ID = "Qwen/Qwen3-VL-4B-Instruct"

PROMPT = (
    "This photo shows one Pokemon trading card. Read it carefully and answer with JSON only, no prose:\n"
    '{"name": "<card name exactly as printed at the top, in the printed language, without HP>", '
    '"number": "<collector number as printed at the bottom left, e.g. 006/165, or empty if unreadable>", '
    '"language": "<language of the card text: en, de, ja, fr, it, es, pt, ko, zh or unknown>", '
    '"readable": <true if you can see a card clearly, else false>}'
)

LANGS = {"en", "de", "ja", "fr", "it", "es", "pt", "ko", "zh"}
NUM_RE = re.compile(r"([A-Za-z]{0,3}\d{1,3})\s*/\s*([A-Za-z]{0,3}\d{2,3})")


class VLMLabeler:
    def __init__(self, model_id: str = MODEL_ID, max_side: int = 1280):
        import torch
        from transformers import AutoModelForImageTextToText, AutoProcessor, BitsAndBytesConfig

        q = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                               bnb_4bit_compute_dtype=torch.bfloat16, bnb_4bit_use_double_quant=True)
        self.model = AutoModelForImageTextToText.from_pretrained(model_id, quantization_config=q,
                                                                 device_map="cuda:0", dtype=torch.bfloat16)
        self.model.eval()
        self.processor = AutoProcessor.from_pretrained(model_id)
        self.max_side = max_side
        self.torch = torch

    def read(self, bgr: np.ndarray) -> dict:
        import cv2
        from PIL import Image

        h, w = bgr.shape[:2]
        s = min(1.0, self.max_side / max(h, w))
        if s < 1:
            bgr = cv2.resize(bgr, (round(w * s), round(h * s)), interpolation=cv2.INTER_AREA)
        img = Image.fromarray(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB))
        messages = [{"role": "user", "content": [{"type": "image", "image": img}, {"type": "text", "text": PROMPT}]}]
        inputs = self.processor.apply_chat_template(messages, tokenize=True, add_generation_prompt=True,
                                                    return_dict=True, return_tensors="pt").to(self.model.device)
        with self.torch.no_grad():
            out = self.model.generate(**inputs, max_new_tokens=120, do_sample=False)
        text = self.processor.batch_decode(out[:, inputs["input_ids"].shape[1]:], skip_special_tokens=True)[0]
        return parse_reply(text)


def parse_reply(text: str) -> dict:
    m = re.search(r"\{.*\}", text, re.S)
    d = {}
    if m:
        try:
            d = json.loads(m.group(0))
        except json.JSONDecodeError:
            d = {}
    num = str(d.get("number", "") or "")
    nm = NUM_RE.search(num)
    lang = str(d.get("language", "") or "").strip().lower()[:2]
    return {
        "raw": text.strip()[:400],
        "name": str(d.get("name", "") or "").strip(),
        "number": number_key(nm.group(1)) if nm else (number_key(num) if num.strip().isdigit() else None),
        "total": nm.group(2).lstrip("0") if nm else None,
        "language": lang if lang in LANGS else "unknown",
        "readable": bool(d.get("readable", True)),
    }


def _norm_name(s: str) -> str:
    s = unicodedata.normalize("NFKC", s).casefold()
    s = "".join(ch for ch in unicodedata.normalize("NFKD", s) if not unicodedata.combining(ch))
    return re.sub(r"[^\w]", "", s)


def name_sim(a: str, b: str) -> float:
    a, b = _norm_name(a), _norm_name(b)
    if not a or not b:
        return 0.0
    return SequenceMatcher(None, a, b).ratio()


def match(reading: dict, cards: list[Card]) -> dict:
    """Map a VLM reading to a manifest card. Returns {"key": str|None, "how": str, "name_sim": float}."""
    lang = reading.get("language")
    if not reading.get("readable", True):
        return {"key": None, "how": "VLM says the card is not readable", "name_sim": 0.0}
    if lang not in {c.lang for c in cards}:
        return {"key": None, "how": f"language '{lang}' not in scope", "name_sim": 0.0}
    pool = [c for c in cards if c.lang == lang]
    num, total, name = reading.get("number"), reading.get("total"), reading.get("name", "")
    why = "no number read"
    if num:
        cands = [c for c in pool if number_key(c.local_id) == num]
        if total:
            with_total = [c for c in cands if str(c.official) == total]
            cands = with_total or cands
        if cands:
            scored = sorted(((name_sim(name, c.name), c) for c in cands), key=lambda x: -x[0])
            best_sim, best = scored[0]
            if len(scored) == 1 and (best_sim >= 0.5 or (total and str(best.official) == total and not name)):
                return {"key": best.key, "how": "number+total+name" if total else "number+name",
                        "name_sim": round(best_sim, 3)}
            if len(scored) > 1 and best_sim >= 0.6 and best_sim - scored[1][0] >= 0.15:
                return {"key": best.key, "how": "number+name (disambiguated)", "name_sim": round(best_sim, 3)}
            why = f"number {num} matches {scored[0][1].name}, read name '{name}'"
        else:
            why = f"number {num}/{total} not in set list"
    # number missing or inconsistent: accept only a name that is unique in that language
    if name:
        scored = sorted(((name_sim(name, c.name), c) for c in pool), key=lambda x: -x[0])
        hits = [c for s, c in scored if s >= 0.85]
        if len(hits) == 1:
            return {"key": hits[0].key, "how": f"name only (unique); {why}", "name_sim": round(scored[0][0], 3)}
        return {"key": None, "how": f"{why}; name matches {len(hits)} cards", "name_sim": round(scored[0][0], 3)}
    return {"key": None, "how": "nothing readable", "name_sim": 0.0}
