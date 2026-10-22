#!/usr/bin/env python3
"""Add a Vietnamese voice-over (VieNeu-TTS, voice "Xuân Vĩnh") to a HyperFrames video project.

Usage: video-voiceover.py <video-dir> [--max-overrun 0]

Reads <video-dir>/narration.json ({scene-id: text}), synthesizes one clip per scene into
<video-dir>/assets/voice/<scene-id>.mp3, and rewrites the <audio id="vo-*"> tags in index.html
so each clip starts 0.6 s into its scene. Fails when a clip does not fit its scene, so the text
can be shortened instead of the audio being cut. Requires: pip install vieneu soundfile; ffmpeg.
"""
import json
import pathlib
import re
import subprocess
import sys

VOICE = "Xuân Vĩnh"
LEAD_IN = 0.6   # seconds of silence before the voice enters a scene
TAIL = 0.8      # seconds the scene must keep after the voice ends
BGM_VOLUME = 0.28

video = pathlib.Path(sys.argv[1]).resolve()
narration = json.loads((video / "narration.json").read_text())
index = video / "index.html"
html = index.read_text()

scenes = {}
for m in re.finditer(r'<div[^>]*data-composition-src="compositions/frames/([\w-]+)\.html"[^>]*?data-start="([\d.]+)"[^>]*?data-duration="([\d.]+)"', html):
    scenes[m.group(1)] = (float(m.group(2)), float(m.group(3)))

missing = [s for s in narration if s not in scenes]
if missing:
    sys.exit(f"narration scenes not in index.html: {missing}")

from vieneu import Vieneu  # noqa: E402

tts = Vieneu(mode="v3turbo")
out = video / "assets" / "voice"
out.mkdir(parents=True, exist_ok=True)

tags, problems = [], []
for scene, text in narration.items():
    start, dur = scenes[scene]
    wav = out / f"{scene}.wav"
    tts.save(tts.infer(text, voice=VOICE), str(wav))
    mp3 = out / f"{scene}.mp3"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(wav), "-ac", "1", "-b:a", "128k", str(mp3)], check=True)
    wav.unlink()
    length = float(subprocess.check_output(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(mp3)]))
    room = dur - LEAD_IN - TAIL
    flag = "" if length <= room else f"  <-- TOO LONG by {length - room:.1f}s"
    print(f"{scene:16s} voice {length:5.1f}s / room {room:5.1f}s{flag}")
    if flag:
        problems.append(scene)
    tags.append(
        f'      <audio id="vo-{scene}" src="assets/voice/{scene}.mp3" data-start="{start + LEAD_IN:g}" '
        f'data-duration="{min(length + 0.1, dur - LEAD_IN):.2f}" data-track-index="2" data-volume="1"></audio>')

html = re.sub(r'\n[ ]*<audio id="vo-[^>]*></audio>', "", html)
block = "\n      <!-- Voice-over: VieNeu-TTS v3 Turbo, voice \"Xuân Vĩnh\"; text in narration.json. -->\n" + "\n".join(tags)
html = re.sub(r'(<audio[^>]*id="bgm"[^>]*></audio>)', lambda m: m.group(1) + block, html, count=1)
html = re.sub(r'(<audio[^>]*id="bgm"[^>]*data-volume=")[\d.]+(")', rf"\g<1>{BGM_VOLUME}\2", html, count=1)
index.write_text(html)
sys.exit(1 if problems else 0)
