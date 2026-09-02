#!/usr/bin/env python3
"""Renders the README artwork from real ggwave waveforms.

    tools/hero.py <dir with *.pcm from tools/dumpwave.cpp> <output dir>

Writes hero-{dark,light}.svg (one transmission, big) and protocols-{dark,light}.svg
(the same message in three protocol families). Spectrograms are drawn as run-length
merged <rect>s so the files stay small and render crisply as GitHub <img>s.
"""
import os
import sys

import numpy as np

FS, N, HOP = 48000, 1024, 512
HZ_PER_BIN = FS / N

THEMES = {
    "dark":  dict(ground="#0E0D0B", ink="#E7DFCC", muted="#8A8377", line="#E7DFCC", accent="#FF5A1F"),
    "light": dict(ground="#F4F1EA", ink="#15130F", muted="#7A7468", line="#15130F", accent="#E8480F"),
}
SANS = "-apple-system, 'Helvetica Neue', Helvetica, Arial, sans-serif"
MONO = "ui-monospace, 'SF Mono', Menlo, Consolas, monospace"


def spectrogram(path, max_cols=160):
    """dB magnitude per (frame, bin). Rectangular window on purpose: ggwave's tones sit
    exactly on FFT bin centres, so this gives one clean bin per tone instead of a halo."""
    x = np.fromfile(path, np.int16).astype(np.float32) / 32768
    hop = max(HOP, int(np.ceil((len(x) - N) / max_cols / HOP)) * HOP)
    frames = (len(x) - N) // hop + 1
    S = np.abs(np.array([np.fft.rfft(x[i * hop:i * hop + N]) for i in range(frames)]))
    return 20 * np.log10(S + 1e-9), len(x) / FS


def band(S, floor_db=30, pad=6):
    active = np.where((S > S.max() - floor_db).any(axis=0))[0]
    return max(0, active.min() - pad), min(S.shape[1], active.max() + pad + 1)


def cells(S, b0, b1, floor_db=30, levels=5):
    """(col_start, col_end, bin, level) with horizontal runs merged."""
    q = np.clip((S[:, b0:b1] - (S.max() - floor_db)) / floor_db, 0, 1)
    L = np.round(q * levels).astype(int)
    out = []
    for b in range(L.shape[1]):
        col, start, cur = L[:, b], 0, 0
        for t in range(len(col) + 1):
            v = col[t] if t < len(col) else -1
            if v != cur:
                if cur > 0:
                    out.append((start, t, b, cur))
                start, cur = t, v
    return out


def panel(S, x, y, w, h, th, levels=5):
    b0, b1 = band(S)
    cols = S.shape[0]
    cw, bh = w / cols, h / (b1 - b0)
    styles = {
        1: (th["ink"], 0.10), 2: (th["ink"], 0.24), 3: (th["ink"], 0.45),
        4: (th["ink"], 0.72), 5: (th["accent"], 1.0),
    }
    parts = [f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="none" stroke="{th["line"]}" stroke-opacity="0.22"/>']
    groups = {}
    for c0, c1, b, lv in cells(S, b0, b1, levels=levels):
        groups.setdefault(lv, []).append(
            f'<rect x="{x + c0 * cw:.1f}" y="{y + h - (b + 1) * bh:.1f}" width="{(c1 - c0) * cw:.1f}" height="{bh + 0.3:.1f}"/>'
        )
    for lv, rects in sorted(groups.items()):
        fill, op = styles[lv]
        parts.append(f'<g fill="{fill}" fill-opacity="{op}">{"".join(rects)}</g>')
    return "\n".join(parts), (b0 * HZ_PER_BIN, b1 * HZ_PER_BIN)


def khz(lo, hi):
    return f"{lo / 1000:.1f}–{hi / 1000:.1f} kHz"


def text(x, y, s, size, th_color, family=SANS, weight=400, tracking=0, anchor="start", extra=""):
    ls = f' letter-spacing="{tracking}"' if tracking else ""
    return (f'<text x="{x}" y="{y}" font-family="{family}" font-size="{size}" font-weight="{weight}" '
            f'fill="{th_color}"{ls} text-anchor="{anchor}" {extra}>{s}</text>')


def hero(pcm, th, label="Sotto Fast"):
    W, H = 1400, 520
    S, secs = spectrogram(pcm)
    px, py, pw, ph = 640, 96, 690, 340
    spec, (lo, hi) = panel(S, px, py, pw, ph, th)
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img" aria-label="sotto: the word hello, encoded as sound, shown as a spectrogram">
<rect width="{W}" height="{H}" fill="{th["ground"]}"/>
{text(72, 150, "DATA OVER SOUND · ANDROID · NO SERVER", 12, th["muted"], MONO, 500, "0.22em")}
{text(66, 300, "sotto", 176, th["ink"], SANS, 700, "-0.05em")}
{text(72, 352, "Type on one phone. The other one hears it.", 15, th["muted"], SANS, 400)}
<line x1="72" y1="392" x2="200" y2="392" stroke="{th["line"]}" stroke-opacity="0.35"/>
{text(72, 418, "One tone at a time, sixty-four to choose from.", 12, th["muted"], MONO, 500, "0.08em")}
{text(72, 438, "The other phone's microphone", 12, th["muted"], MONO, 500, "0.08em")}
{text(72, 458, "is the only receiver you need.", 12, th["muted"], MONO, 500, "0.08em")}
{text(px, 74, "ONE MESSAGE, AS THE RECEIVER HEARS IT", 11, th["muted"], MONO, 500, "0.22em")}
{spec}
{text(px, py + ph + 30, f"“hello” · {label} · {secs:.2f} s of audio", 12, th["ink"], MONO, 500, "0.04em")}
{text(px + pw, py + ph + 30, f"{khz(lo, hi)} · 48 kHz · 1024-sample frames", 12, th["muted"], MONO, 500, "0.04em", "end")}
{text(px + pw + 14, py + 8, f"{hi / 1000:.1f}k", 10, th["muted"], MONO, 500, extra='dominant-baseline="hanging"')}
{text(px + pw + 14, py + ph, f"{lo / 1000:.1f}k", 10, th["muted"], MONO, 500)}
</svg>
'''


def protocols(items, th):
    W, H = 1400, 420
    gap, x0, y0, pw, ph = 40, 72, 84, (1400 - 2 * 72 - 2 * 40) // 3, 240
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img" aria-label="the same message in three ggwave protocol families">',
             f'<rect width="{W}" height="{H}" fill="{th["ground"]}"/>',
             text(x0, 52, "THE SAME FIVE BYTES, THREE WAYS · SOTTO MODEM VS GGWAVE", 11, th["muted"], MONO, 500, "0.22em")]
    for i, (label, sub, pcm) in enumerate(items):
        S, secs = spectrogram(pcm)
        x = x0 + i * (pw + gap)
        spec, (lo, hi) = panel(S, x, y0, pw, ph, th)
        parts += [spec,
                  text(x, y0 + ph + 34, label, 15, th["ink"], SANS, 700, "-0.01em"),
                  text(x, y0 + ph + 56, f"{sub} · {khz(lo, hi)} · {secs:.2f} s", 11, th["muted"], MONO, 500, "0.06em")]
    parts.append("</svg>")
    return "\n".join(parts) + "\n"


def main(src, out):
    os.makedirs(out, exist_ok=True)
    items = [("Sotto Fast", "one tone, 6 bits, 21 ms symbols", f"{src}/hello_sotto_fast.pcm"),
             ("Sotto Robust", "one tone, 6 bits, 43 ms symbols", f"{src}/hello_sotto_robust.pcm"),
             ("ggwave Audible Fast", "six tones at once, 128 ms chunks", f"{src}/hello_fast.pcm")]
    for name, th in THEMES.items():
        with open(f"{out}/hero-{name}.svg", "w") as f:
            f.write(hero(f"{src}/hello_sotto_fast.pcm", th))
        with open(f"{out}/protocols-{name}.svg", "w") as f:
            f.write(protocols(items, th))
    for fn in sorted(os.listdir(out)):
        print(f"{fn:22s} {os.path.getsize(f'{out}/{fn}') / 1024:6.1f} KB")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
