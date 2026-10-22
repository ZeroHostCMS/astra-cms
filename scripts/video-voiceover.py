#!/usr/bin/env python3
"""Add a voice-over to a HyperFrames video project, in Vietnamese or English.

Usage: video-voiceover.py <video-dir> [--lang vi|en] [--only scene-id,scene-id]

  vi (default)  narration.json     -> assets/voice/<scene>.mp3     -> index.html
                VieNeu-TTS v3 Turbo, voice "Xuân Vĩnh".   Requires: pip install vieneu soundfile
  en            narration.en.json  -> assets/voice-en/<scene>.mp3  -> index.en.html
                Kokoro-82M via `npx hyperframes tts`, voice am_michael.
                Requires: pip install kokoro-onnx soundfile (or HYPERFRAMES_PYTHON pointing at a venv with them)

Synthesizes one clip per scene ({scene-id: text}) and rewrites the <audio id="vo-*"> tags in the
index file so each clip starts 0.6 s into its scene. --only resynthesizes just those scenes and
reuses the other clips already on disk. Fails when a clip does not fit its scene, so the text can be
shortened instead of the audio being cut. Also requires ffmpeg.
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys

LEAD_IN = 0.6   # seconds of silence before the voice enters a scene
TAIL = 0.8      # seconds the scene must keep after the voice ends
BGM_VOLUME = 0.28
LANGS = {
    "vi": {"narration": "narration.json", "voice_dir": "voice", "index": "index.html",
           "credit": 'VieNeu-TTS v3 Turbo, voice "Xuân Vĩnh"; text in narration.json.'},
    "en": {"narration": "narration.en.json", "voice_dir": "voice-en", "index": "index.en.html",
           "credit": "Kokoro-82M (hyperframes tts), voice am_michael; text in narration.en.json."},
}

args = argparse.ArgumentParser()
args.add_argument("video")
args.add_argument("--lang", choices=LANGS, default="vi")
args.add_argument("--only", default="")
args = args.parse_args()
lang = LANGS[args.lang]
only = set(filter(None, args.only.split(",")))

video = pathlib.Path(args.video).resolve()
narration = json.loads((video / lang["narration"]).read_text())
index = video / lang["index"]
html = index.read_text()

scenes = {}
for m in re.finditer(r'<div[^>]*data-composition-src="compositions/[\w-]+/([\w-]+)\.html"[^>]*?data-start="([\d.]+)"[^>]*?data-duration="([\d.]+)"', html):
    scenes[m.group(1)] = (float(m.group(2)), float(m.group(3)))

missing = [s for s in narration if s not in scenes]
if missing:
    sys.exit(f"narration scenes not in {lang['index']}: {missing}")


def synthesize(text, wav):
    if args.lang == "vi":
        global tts
        if "tts" not in globals():
            from vieneu import Vieneu
            tts = Vieneu(mode="v3turbo")
        tts.save(tts.infer(text, voice="Xuân Vĩnh"), str(wav))
    else:
        subprocess.run(["npx", "--yes", "hyperframes", "tts", text, "-v", "am_michael", "-o", str(wav)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


out = video / "assets" / lang["voice_dir"]
out.mkdir(parents=True, exist_ok=True)

tags, problems = [], []
for scene, text in narration.items():
    start, dur = scenes[scene]
    mp3 = out / f"{scene}.mp3"
    if not only or scene in only or not mp3.exists():
        wav = out / f"{scene}.wav"
        synthesize(text, wav)
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
        f'      <audio id="vo-{scene}" src="assets/{lang["voice_dir"]}/{scene}.mp3" data-start="{start + LEAD_IN:g}" '
        f'data-duration="{min(length, dur - LEAD_IN):.2f}" data-track-index="2" data-volume="1"></audio>')

html = re.sub(r'\n[ ]*<!-- Voice-over:[^\n]*-->', "", html)
html = re.sub(r'\n[ ]*<audio id="vo-[^>]*></audio>', "", html)
block = f"\n      <!-- Voice-over: {lang['credit']} -->\n" + "\n".join(tags)
html = re.sub(r'(<audio[^>]*id="bgm"[^>]*></audio>)', lambda m: m.group(1) + block, html, count=1)
html = re.sub(r'(<audio[^>]*id="bgm"[^>]*data-volume=")[\d.]+(")', rf"\g<1>{BGM_VOLUME}\2", html, count=1)
index.write_text(html)
sys.exit(1 if problems else 0)
