# wisper keys: your PC's voice typing as a phone keyboard (Android + iOS)

A dark, minimal phone keyboard that types normally, plus a mic key that streams your voice to **wisper** on your PC
(`D:\wisper`: Whisper large-v3 turbo on the RTX GPU, your vocabulary, your learned fixes) and types what comes back.
Same model and same cleanup as Ctrl+Shift on the PC, and phone dictations feed the same nightly learner.

```
phone mic --16 kHz PCM, streamed while you talk--> wisper (PC)  ws://<pc>:47822/v1/stream
   while you talk: wisper transcribes at each pause (same Session as Ctrl+Shift)
   on release: only the last moment is left  --> text --> typed at the cursor
```

Measured (real recordings streamed in real time): release -> text **5-13 ms on the PC**, plus the Wi-Fi round
trip (the emulator showed 2-7 ms). A 48 s dictation came back 13 ms after letting go.

## Pieces
| Path | What |
|---|---|
| `D:\wisper\service\remote.py` | the PC side (in the wisper repo): WebSocket stream on :47822, OpenAI-compatible API with token on :47823 |
| `D:\wisper\tools\pair.py` | shows the pairing QR code (addresses + token) |
| `D:\wisper\tools\allow_phone_access.ps1` | firewall: home Wi-Fi (Private) + Tailscale only; asks for admin once |
| `android/` | Kotlin keyboard (InputMethodService, custom-drawn keys, OkHttp). APK ~0.6 MB |
| `ios/` | Swift: container app (does the recording) + keyboard extension; XcodeGen project |
| `.github/workflows/ios.yml` | builds iOS on GitHub's Macs; optional TestFlight upload |
| `tools/fake_phone.py` | plays a recording into the PC exactly like the phone does and times it |

## Setup
**PC (once)**
1. `"remote": true` is already in `D:\wisper\config.local.json` (wisper restarted with it).
2. Run `powershell -File D:\wisper\tools\allow_phone_access.ps1` (admin prompt) so the phone can get through the firewall.
3. `python -I D:\wisper\tools\pair.py` opens the pairing page with a QR code.
4. That's it: **pair once, works anywhere.** At home the phone talks to the PC directly over Wi-Fi; on mobile data
   or any other Wi-Fi it goes through a Cloudflare tunnel wisper keeps open (encrypted, token required). When the
   PC restarts, its addresses change: the phone finds it again by itself (mDNS on Wi-Fi, a secret GitHub gist
   holding the tunnel's current address everywhere else). Measured through the tunnel: ~55 ms release -> text.

**Android** (download: https://github.com/manuvamp/wisper-keys/releases/latest/download/wisper-keys.apk)
1. `python -I D:\wisper\tools\pair.py --apk "D:\hackathn fire tv\wisper-keys\android\app\build\outputs\apk\release\app-release.apk"`
eleasepp-release.apk"`
   shows two QR codes: scan the first with the phone camera to download and install the app (15 minutes).
2. Open **wisper keys**: turn on the keyboard, switch to it, allow the mic, **Scan pairing code**. All four turn green.

**iPhone**
iOS keyboards are not allowed to use the microphone (still true on iOS 18/26), so it works like Wispr Flow: the app
holds a "voice session" (mic ready in the background, orange dot) and the keyboard's mic key tells it start/stop.
The first mic tap opens the app once; go back with ◀ (top-left) and dictate. The session ends after the idle time
you pick (5 min / 15 min / 1 h / never).
Building needs a Mac: push this folder to a GitHub repo and the `ios` workflow compiles it; tick "testflight" with
your App Store Connect API key in the repo secrets to get it onto your phone through TestFlight.
Add the keyboard in Settings › General › Keyboard › Keyboards, turn on **Allow Full Access**, pair by pointing the
iPhone camera at the QR code.

## The keyboard
- Gboard-style QWERTY: 10-column rows, middle row inset, wide Shift/Backspace, bottom row `?123 , 🎤 space . ⏎`.
- Long-press any letter for its number/symbol (shown in the corner) and accents; long-press `.` for punctuation.
- Swipe on space to move the cursor; swipe left from backspace to select and delete whole words.
- Auto-capitals, double-space for ". ", Shift double-tap = caps lock, Enter follows the app (search/send/go/done).
- Number and phone fields get a numpad. Emoji panel (with recents) from the strip or long-press comma (Android;
  on iPhone use the system emoji keyboard via 🌐).
- Mic: **tap** = hands-free (tap again or ✓ to finish), **hold** = push-to-talk. What's transcribed so far shows
  in the strip; **Undo** removes the last dictation. Mid-sentence dictation isn't capitalized ("and then we...").
- Password fields: the mic is off. If Wi-Fi drops mid-dictation, the audio is replayed when the link comes back.

## Build
Android (portable toolchain in `%USERPROFILE%\dev`: JDK 17, Android SDK 35, Gradle 8.11): `android\build.cmd`.

## Status
- PC side: tested with real recordings (streaming, token check, OpenAI endpoint, partial text), on Wi-Fi and
  through the tunnel; tunnel dies with wisper; gist is secret and readable without login.
- Rediscovery: tested on the emulator paired with only dead addresses: it found the PC through the gist.
- Android: built and tested on an Android 15 emulator: setup, pairing link, typing, long-press, symbols, emoji,
  swipe-delete, Enter actions, mic -> PC -> reply. Not yet on a physical phone (the emulator mic only hears silence).
- iOS: **compiles** (GitHub Actions, macOS runner, repo `manuvamp/wisper-keys`). App IDs `app.wisperkeys` and
  `app.wisperkeys.keyboard` are registered on team 9SM6R448K8 and the API key is in the repo secrets. TestFlight
  still needs, once, while signed in to Apple: the App Group `group.app.wisperkeys` (assigned to both IDs) and the
  App Store Connect app record "wisper keys" (bundle `app.wisperkeys`, SKU `wisperkeys`). Then run the `ios`
  workflow with "testflight" ticked.
