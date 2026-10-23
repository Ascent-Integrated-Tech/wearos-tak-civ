## Test setup

Record the exact build before starting. The rolling release replaces its APK on
every pipeline, so copy the version and SHA-256 from the release details above.

| Field | Value |
| --- | --- |
| WearTAK version (Settings > About) / pipeline / SHA-256 | |
| Watch model / Wear OS version | |
| Phone model / Android version / ATAK + WearTAK Companion versions | |
| TAK server used for testing | |
| Tester / date | |

Install as an update (`adb install -r` or the normal update path). **Do not
uninstall or clear app data**: the update must keep existing servers, certificates
and settings. Use a test TAK server and a second ATAK device. Run emergency tests
only on an isolated test network, never an operational network. Do not attach
credentials, certificates, or sensitive locations to this issue.

## Core acceptance checklist

Check an item only after it passes. For unavailable hardware or unsupported
features, leave it unchecked and record **N/A** with the reason in the results.

- [ ] **Update in place:** the new version installs over the previous one. Saved
  TAK servers, certificates, callsign and settings are still present.
- [ ] **No autostart:** reboot the watch. WearTAK does not start, connect, or keep
  the screen awake until you open it.
- [ ] **TAK servers:** open Settings > Network > TAK Servers. Add a server with the
  **+** next to the title, edit it, and switch between servers. Only the selected
  server stays connected.
- [ ] **Connect and recover:** connect to the test server, toggle Wi-Fi/LTE off
  and on, and move out of range and back. Connection status matches reality and
  WearTAK reconnects on its own without duplicate connections.
- [ ] **Certificates:** a server with a side-loaded `.p12` in the app's `certs`
  folder connects (the New Server screen shows the certificate when available);
  a server using username/password enrollment also connects. A wrong password
  shows an error, not a false "connected".
- [ ] **Position reporting:** your watch appears on the second ATAK device with
  the right callsign and moving position; the second device appears on the watch
  map.
- [ ] **Map and markers:** create, move and delete a point on the watch and on
  ATAK. Changes reach the other device without duplicates.
- [ ] **Chat:** send and receive direct and All Chat Rooms messages, including
  quick replies. Recipients are correct; private replies never go to everyone.
- [ ] **Channels:** with a server that has channels/groups, select channels and
  confirm traffic and contacts follow the selection.
- [ ] **Emergency:** on an isolated network, trigger and cancel an alert from the
  watch and receive one from ATAK. Each propagates once; cancel clears it.
- [ ] **Bloodhound and routes:** start Bloodhound on a point from the picker,
  switch targets, and stop. Follow a route sent from ATAK; distance and bearing
  update while walking.
- [ ] **Health readings:** heart rate and SpO2 values appear and alerts fire only
  for real out-of-range readings (no false resting-HR alerts).
- [ ] **Companion pairing:** pair with the WearTAK ATAK Companion plugin on the
  phone; settings sync and chat/markers relay through the phone.
- [ ] **Stop and battery:** exit or stop WearTAK. Services stop, the server
  disconnects and the screen no longer stays awake. Over a 30-60 minute session,
  record unusual heat or battery drain, and any crash.

## Results

**Overall:** PASS / FAIL / BLOCKED

**N/A items and reasons:**

**Failures:** for each failure, include steps, expected/actual behavior,
timestamp, and a screenshot or short recording. Attach sanitized logs where
available.

**Release blockers:** lost settings or certificates after update, wrong-recipient
chat, false connection status, incorrect emergency cancellation, autostart after
reboot, or crashes preventing use.
