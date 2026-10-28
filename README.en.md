# HUGO Projector

**A standalone Android projector with a phone controller.** Upload a photo or video, move and resize its projection region, draw on a canvas, save scenes, play original games and calibrate original projection illusions. The projector renders locally and hosts its own phone UI; normal use does not require a laptop.

[Deutsch](README.md) · [Phone guide in Albanian](docs/PHONE_USE.sq.md) · [Evidence](EVIDENCE.md) · [Architecture](docs/ARCHITECTURE.md) · [Security](SECURITY.md)

![Phone scene editor with an original synthetic test image](docs/assets/phone-scene.png)

## Features

- One photo or video in a movable/resizable rectangular region, aspect-ratio containment, save/reopen placement, video pause/resume and persistent blackout. Moving a region keeps the active decoder.
- Sofa Spirit: original transparent face and hands, three independently calibrated meshes, polygon masks, feathering and a 9.5-second timeline. It is a light illusion; fabric is not physically deformed.
- Native drawing/text canvas with eraser, Undo/Redo, mapping and animation; device-local scene storage.
- Local phone pairing, one editor plus viewers, Home Wi-Fi and projector-owned Portable Mode hotspot.
- Original native Tank Duel for solo or two paired phones. Other games and physical-controller support are future work.

Runtime **2.3.1-simple, versionCode 7**, 1280×720. The owner reported both Sofa Spirit and the simplified UI working on **28 October 2026**. Screenshots are browser emulation or the real Android framebuffer, not photographs of a physical sofa. Native tests, owner reports and optical limits are distinguished in [EVIDENCE.md](EVIDENCE.md).

## Build

Install JDK 17 or 21, Android SDK Platform 34 and Build Tools 34.0.0. Open `android/` in Android Studio or set `ANDROID_HOME` to your own SDK and run:

```bash
git clone https://github.com/IamRedi/hugo-projector-showcase.git
cd hugo-projector-showcase/android
./gradlew :app:assembleDebug
# Windows: gradlew.bat :app:assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`. Builds use your own debug key. Do not replace an existing differently signed installation; use a separate evaluation device. Declared minimum SDK is 26; hardware evidence is from Android 14, not every supported SDK/firmware combination.

```bash
python3 tools/check_public_export.py
python3 -m unittest discover -s tests -v
```

The phone UI currently uses Albanian with some older English tools. Connect through the address/QR on the projector, request pairing and approve it on the projector. Upload → Show → drag/resize → Save. The full phone guide is [here](docs/PHONE_USE.sq.md).

## Scope and limits

The current transport is unencrypted LOCAL LAB HTTP/WS on a trusted private network. TLS/WSS and Internet access are not implemented. The tested firmware may show its OEM launcher first after boot; Home opens HUGO. Focus/optical keystone/HDMI/lamp control are not implemented. Blackout renders black without switching off the lamp. HUGO Core integration is future work; this device operates independently.

This is a fresh public source snapshot with no private development history, device keys, pairing tokens, user media, saved scenes, calibrations or private reference videos. Source is provided for reading/evaluation under the [source-available license](LICENSE), not an unrestricted open-source license. See [third-party notices](THIRD_PARTY_NOTICES.md) and [original asset provenance](docs/ASSET_PROVENANCE.md).
