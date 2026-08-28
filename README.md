# FreeFCC Lito X1 Lab patch

Target: **FreeFCC v1.5.5** (upstream commit/release current on 2026-08-27).

This package adds a conservative research/diagnostic page for **DJI RC 2 + Lito X1** without adding root, bootloader modification, or an arbitrary raw-DUML console.

## What it adds

- **DUML port scan** — connects to the known localhost proxy ports and reports which are reachable. No DUML command is written.
- **Passive telemetry capture (5 s)** — reads unsolicited proxy traffic, parses direct DUML frames and `55 CC 30 75` wrapped frames, checks CRC-8/CRC-16, and displays command metadata + a short payload preview. No DUML command is written.
- **FCC profile audit** — reads the exact installed `profiles/fcc.json` and summarizes frame count, rounds, delays, destination, command set/ID, and payload definitions. No socket is opened.
- **Apply FCC + ACK count** — active diagnostic that reuses **the exact upstream `fcc.json` frames unchanged**, but sends each via upstream `DumlTransport.sendAndReceive()` to count structurally valid matching DUML responses. This is the only active RF-region action added by the patch.
- **Share diagnostic report** — Android text sharing, no storage permission needed. The displayed aircraft serial in the header is masked.
- **GitHub Actions workflow** — can build a debug APK in a fork without requiring a local Android SDK.

## Important interpretation note

A valid matching DUML response means the proxy/device returned a response matching CRC, sequence, routing, command set and command ID. It **does not independently prove actual RF output power or final CE/FCC regulatory state**. A spectrum/RF measurement or a known reliable read-back parameter would be needed for that.

## Apply the patch

1. Clone upstream FreeFCC and check out v1.5.5 (or the corresponding upstream commit):

   ```bash
   git clone https://github.com/doesthings/FreeFCC.git
   cd FreeFCC
   git checkout v1.5.5
   ```

2. Run the patcher from this package:

   ```bash
   python3 /path/to/FreeFCC-LitoX1-Lab-patch/apply_lito_lab.py /path/to/FreeFCC
   ```

3. Build a debug APK:

   ```bash
   cd /path/to/FreeFCC
   ./gradlew assembleDebug
   ```

   Output:

   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```

## Build with GitHub Actions instead

After applying the patch, push the modified source to a branch named `lito-x1-lab` in your own fork. The included workflow **Build Lito X1 Lab APK** will run automatically for that branch, or you can run it manually with `workflow_dispatch`. Download the `FreeFCC-LitoX1-Lab-debug` artifact from the workflow run.

## Suggested first test on RC 2

1. Install the debug APK alongside/over your test setup as appropriate for its signing key.
2. Power on and link the Lito X1.
3. Open **Lab → Scan DUML ports**.
4. Run **Audit installed FCC profile**.
5. Run **Capture passive telemetry — 5 s** once with keepalive off and once with it on; compare reports.
6. Only if you intentionally want to apply FCC, run **Apply FCC + count ACKs**.

Do not perform first tests while airborne. Make a backup of any setup you rely on before replacing an installed APK signed with a different key.

## Signing caveat

A debug APK is signed with the local/CI debug key. Android will not install it as an update over an APK with a different signing certificate. If the existing FreeFCC installation is signed differently, uninstalling it may be required, which can remove its app-local settings. Alternatively, build with the same signing key you already use.

## License

FreeFCC is licensed under **AGPL-3.0**. This modification is intended to remain under the same license; keep the upstream license and source available when redistributing modified binaries.
