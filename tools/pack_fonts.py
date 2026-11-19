#!/usr/bin/env python3
"""Cloud build only: unlock the Thmanyah font (key = FONT_KEY secret) and put it scrambled into the app.
The font licence allows using it inside apps but not sharing the font files, so:
 - the repository holds only the locked copy (fonts-locked/*.enc, AES-256, same as Sahl TV);
 - the app gets it scrambled (assets/f/*.bin) and unpacks it into its private folder (see Fonts.java).
Without FONT_KEY nothing happens and the app uses the phone's normal font."""
import os, subprocess, sys

KEY = b"Sahl-Sync/thmanyah-font/scramble-v1"   # must match Fonts.KEY
secret = os.environ.get("FONT_KEY", "")
if not secret:
    print("FONT_KEY not set: building with the phone's normal font")
    sys.exit(0)
target = "app/src/main/assets/f"
os.makedirs(target, exist_ok=True)
for name in ["r", "b"]:
    data = subprocess.run(["openssl", "enc", "-d", "-aes-256-cbc", "-pbkdf2", "-iter", "200000", "-pass", "env:FONT_KEY",
                           "-in", f"fonts-locked/{name}.enc"], check=True, capture_output=True).stdout
    assert data[:4] in (b"OTTO", b"\x00\x01\x00\x00"), "not a font: wrong FONT_KEY?"
    open(f"{target}/{name}.bin", "wb").write(bytes(b ^ KEY[i % len(KEY)] for i, b in enumerate(data)))
    print(f"font {name}: {len(data)} bytes packed")
