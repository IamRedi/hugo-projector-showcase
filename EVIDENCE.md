# Evidence and limits — 28 October 2026

## Runtime tested before publication

Version **2.3.1-simple / code 7** was installed on the owner's Android 14 projector with its existing signing identity. The owner confirmed that Sofa Spirit worked very well, then confirmed the simplified photo/video UI worked. These are functional owner reports; they are not quantitative optical measurements or a claim of every acceptance criterion being tested.

| Area | Observed evidence |
|---|---|
| Media / UI | Real upload of original synthetic PNG and MP4; move/resize without reloading the media; save/reopen into a different prior position; portrait 390×844 and landscape 844×390 browser emulation; no horizontal overflow or page errors. |
| Projection geometry | Android framebuffer photo bounds `[281,126,1048,557]` and video bounds `[268,136,1036,568]` matched the phone's normalized regions exactly. Blackout contained only black pixels. |
| Restart and auth | Region persisted after application restart; wrong editor session was rejected; Stop remained stopped and Resume worked. |
| Portable Mode | Direct projector-hotspot control of video/placement/pause/resume/Stop while the laptop's ADB daemon was stopped. |
| Existing data | Existing scene content, media hashes, Canvas draft, paired token store and FX calibration file matched the pre-change snapshot. Synthetic test uploads/scenes were removed. Private inventories/backups are not in this repository. |
| FX geometry | Cold Play and calibration restored the saved profile after application restart, without rewriting its calibration file. |

The inspected installed APK had SHA-256 `d849bc27bf3710a9936ae72a0ffa57628635afa7a234412e5ce3006014bbeedf`. That development APK and its signing key are not distributed here; evaluator builds use their own signing identity.

## FX performance sample

The same FX renderer source measured 29.96 draw callbacks/s over about 92 seconds, mean interval 33.38 ms, p95 35 ms, eight intervals above 50 ms and maximum 137 ms. Android's separate 60 Hz deadline accounting reported 645/2811 janky frames (22.95%). These figures do not prove a zero-jank animation or optical wall latency. Sampled process PSS was 47,985–49,445 KiB and process CPU averaged about 61.2% of one core in that window. They are observations, not maximum resource guarantees. Video frame rate, optical brightness, temperature and every firmware combination were not measured.

Each targeted functional test was under three minutes; there was no new two-hour/24-hour soak run. Hardware evidence predates publication and is not represented as a GitHub Actions device test. The public export is additionally checked for source-manifest integrity, excluded private material and a clean Android build. Its CI checks those publication/build boundaries, not a physical projector.

## Images

`phone-scene.png` and `phone-fx.png` are browser-emulation screenshots of the device-served controller. `sofa-spirit-frame.png` and `video-region-frame.png` are native framebuffer captures. They are not room/sofa photographs. No reference-video frames, personal photos or social-media overlays are included. Original sprite provenance is in [ASSET_PROVENANCE.md](docs/ASSET_PROVENANCE.md).
