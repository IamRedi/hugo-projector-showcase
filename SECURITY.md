# Security and data boundary

This snapshot is a LOCAL LAB application for a trusted private network. HTTP and WebSocket traffic is not encrypted. Do not expose the controller port to the Internet. This repository does not claim production hardening or TLS/WSS.

Implemented boundaries include local approval of phone pairing, Android Keystore storage of tokens, one active editor, session-bound mutation, command expiry/deduplication, Host/Origin checks, bounded uploads and validated scene/calibration coordinates. Media and calibration persist in private application storage. The media loopback secret is generated at runtime, not embedded in this repository.

The public snapshot excludes signing keys, tokens, actual user media/scenes/calibrations, private logs, device serials, LAN inventories and internal documents. Build artifacts and local SDK configuration are ignored. Treat build output as a separate artifact requiring review before sharing.

The app does not root devices, remove OEM apps, flash firmware or reset devices. Android WebView scenes have no shell or privileged JavaScript bridge. The lack of a privileged bridge does not make arbitrary web content risk-free.

For a sensitive security report, contact the maintainer through https://github.com/IamRedi and request a private channel rather than placing credentials or personal data in a public issue.
