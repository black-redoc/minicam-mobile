#!/usr/bin/env python3
"""Synthesizes the detection notification sound: a pump-action reload ("chk-chk").

Stdlib only and seeded, so the output is reproducible and has no third-party license.
Usage: python3 android/tools/detection_sound.py [out.wav]
"""

import math
import random
import struct
import sys
import wave

RATE = 44100
DURATION = 0.75
DEFAULT_OUT = "android/app/src/main/res/raw/detection_reload.wav"


def clack(samples, start, rng, *, noise_ms, ring, thump_hz, gain):
    """Adds one metal-on-metal hit: a noise burst, a few damped metallic modes and a low thump."""
    first = int(start * RATE)
    length = int(0.18 * RATE)
    noise_tau = noise_ms / 1000 / 4
    for i in range(min(length, len(samples) - first)):
        t = i / RATE
        value = rng.uniform(-1, 1) * math.exp(-t / noise_tau) * 0.6
        for freq, tau, amp in ring:
            value += amp * math.sin(2 * math.pi * freq * t) * math.exp(-t / tau)
        value += 0.5 * math.sin(2 * math.pi * thump_hz * t) * math.exp(-t / 0.02)
        samples[first + i] += gain * value


def rattle(samples, start, end, rng):
    """Adds the slide's short scrape between the two hits."""
    first, last = int(start * RATE), int(end * RATE)
    previous = 0.0
    for i in range(first, last):
        envelope = math.sin(math.pi * (i - first) / (last - first))
        # One-pole low-pass keeps the scrape dull instead of hissy.
        previous = 0.7 * previous + 0.3 * rng.uniform(-1, 1)
        samples[i] += 0.12 * envelope * previous


def render():
    rng = random.Random(0x6D696E69)
    samples = [0.0] * int(DURATION * RATE)
    # Slide back: duller, a little longer.
    clack(samples, 0.02, rng, noise_ms=40, ring=[(1850, 0.03, 0.35), (3100, 0.02, 0.25)],
          thump_hz=140, gain=0.8)
    rattle(samples, 0.08, 0.27, rng)
    # Slide forward: the sharp lock-up.
    clack(samples, 0.30, rng, noise_ms=25, ring=[(2400, 0.05, 0.45), (4200, 0.03, 0.3),
          (6100, 0.015, 0.2)], thump_hz=180, gain=1.0)
    peak = max(abs(s) for s in samples)
    scale = 0.89 / peak
    fade = int(0.01 * RATE)
    for i in range(fade):
        samples[-1 - i] *= i / fade
    return [int(s * scale * 32767) for s in samples]


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_OUT
    with wave.open(out, "wb") as file:
        file.setnchannels(1)
        file.setsampwidth(2)
        file.setframerate(RATE)
        file.writeframes(b"".join(struct.pack("<h", s) for s in render()))


if __name__ == "__main__":
    main()
