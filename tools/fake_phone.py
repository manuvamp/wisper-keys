"""Plays a recording into wisper's phone endpoint exactly like the keyboard does (real-time 40 ms PCM frames),
then reports release -> text. Usage: python fake_phone.py <clip> [ws://host:47822] [token]"""
import json, sys, threading, time
import numpy as np
from faster_whisper import decode_audio
from websockets.sync.client import connect

clip = sys.argv[1]
url = sys.argv[2] if len(sys.argv) > 2 else "ws://127.0.0.1:47822"
token = sys.argv[3] if len(sys.argv) > 3 else open(r"D:\wisper\data\remote-token.txt").read().strip()
pcm = (np.clip(decode_audio(clip), -1, 1) * 32767).astype("<i2").tobytes()
FRAME = 640 * 2                                         # 40 ms at 16 kHz

t_conn = time.perf_counter()
with connect(url + "/v1/stream", additional_headers={"Authorization": "Bearer " + token}, compression=None) as ws:
    print("ready:", ws.recv(), f"(connect {1000 * (time.perf_counter() - t_conn):.0f} ms)")
    partials = []
    ws.send(json.dumps({"type": "start", "id": 1, "before": ""}))
    done = threading.Event()
    result = {}

    def reader():
        for msg in ws:
            m = json.loads(msg)
            if m["type"] == "partial":
                partials.append((time.perf_counter(), m["text"]))
            elif m["type"] == "final":
                result.update(m, t=time.perf_counter())
                done.set()
                return
    threading.Thread(target=reader, daemon=True).start()
    t_next = time.perf_counter()
    for i in range(0, len(pcm), FRAME):
        ws.send(pcm[i:i + FRAME])
        t_next += 0.04
        time.sleep(max(0, t_next - time.perf_counter()))
    time.sleep(0.15)                                    # people pause a moment before letting go
    t_release = time.perf_counter()
    ws.send(json.dumps({"type": "stop", "id": 1}))
    done.wait(30)
    print(f"audio {len(pcm) / 32000:.1f}s, partial updates {len(partials)}")
    print(f"release -> text: {1000 * (result['t'] - t_release):.0f} ms (PC side {result['ms']} ms)")
    print("text:", result["text"])
