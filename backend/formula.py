"""LaTeX from the model -> PNG for the glasses HUD.

Rendered with matplotlib mathtext: a pure-Python math layout engine, NOT a TeX installation, so
there is no \\input / \\write18 / file access to abuse. The whitelist below is belt and braces.
HUD is additive (black = transparent), so glyphs are white on black.
"""

import base64
import io
import re

from matplotlib.figure import Figure
from PIL import Image

MAX_LATEX = 120
HUD_MAX_W, HUD_MAX_H = 560, 160

# Letters, digits, whitespace and plain math punctuation only; commands are checked separately.
_ALLOWED_CHARS = re.compile(r"^[A-Za-z0-9\s\\{}()\[\]^_+\-*/=<>.,;:!|'~&]*$")
_COMMAND = re.compile(r"\\([A-Za-z]+)")
_FORBIDDEN = {
    "input", "include", "write", "read", "openin", "openout", "immediate", "def", "edef", "gdef",
    "let", "newcommand", "renewcommand", "catcode", "csname", "endcsname", "expandafter", "url",
    "href", "special", "begin", "end", "text", "mbox", "hbox", "vbox", "usepackage", "documentclass",
}


def safe_latex(s: str) -> str:
    """Normalised LaTeX math, or "" if it is empty, too long or uses anything outside the whitelist."""
    t = str(s or "").strip().strip("$").strip()
    if not t or len(t) > MAX_LATEX or not _ALLOWED_CHARS.match(t):
        return ""
    if any(cmd in _FORBIDDEN for cmd in _COMMAND.findall(t)):
        return ""
    if t.count("{") != t.count("}"):
        return ""
    return " ".join(t.split())


def _render(latex: str, fontsize: float) -> bytes:
    fig = Figure(figsize=(0.01, 0.01), facecolor="black")
    fig.text(0, 0, f"${latex}$", fontsize=fontsize, color="white")
    buf = io.BytesIO()
    fig.savefig(buf, dpi=100, format="png", bbox_inches="tight", pad_inches=0.06, facecolor="black")
    return buf.getvalue()


def render_png(latex: str) -> str | None:
    """Base64 PNG that fits the HUD (≤ 560×160), or None if mathtext cannot parse it. Blocking."""
    if not latex:
        return None
    try:
        png = _render(latex, 40)
        w, h = Image.open(io.BytesIO(png)).size
        scale = min(1.0, HUD_MAX_W / w, HUD_MAX_H / h)
        if scale < 1.0:
            png = _render(latex, max(14.0, 40 * scale * 0.97))
    except (ValueError, RuntimeError):  # mathtext parse errors
        return None
    return base64.b64encode(png).decode("ascii")
