# EFDI mesh live-traffic investigation — handoff

**Status as of 2026-09-17, ~23:45 BST.** Paused because too few developers are
connected to the mesh right now to see realistic traffic; resume tomorrow
morning when more participants are online. This doc is the state a fresh
session needs to pick this back up.

## Where things stand

1. **The SIGABRT crash is fixed and confirmed working.** See commits
   `07eec6c`, `da8167a` and `native/zenoh-flat-jni-patch/README.md`. The
   plugin has stayed up through thousands of live mesh messages, including
   ones with timestamps (the original trigger), with no crash.
2. **CoT ingest now accepts two wire formats**, added in commit `1c4d7f6`:
   - CoT XML (payload starts with `<`) — unchanged, existing path.
   - TAK Protocol / protobuf CoT (payload starts with `0xbf`, the mesh
     framing from the SDK's `docs/takproto.zip`) — new
     `TakProtoCotConverter`, decodes and reconstructs into the same CoT XML
     `CotEvent.parse()` expects. **Not yet exercised against real traffic**
     — no `0xbf`-prefixed payload has been observed on the mesh in ~10
     minutes of capture across two sessions today, so this path is only
     verified by (a) compiling against the SDK's official `.proto` schemas
     and (b) manual reasoning about the wire format, not by seeing it
     actually decode something real. **This is the main thing to verify
     tomorrow.**
3. **Live mesh survey** (broad `**` subscription, ~10-15 min combined
   capture, thousands of samples, zero recognized as CoT XML or TAK
   protobuf): four payload shapes seen, samples saved under
   `/tmp/mesh_samples/` on this machine (regenerate via the capture recipe
   below if that's gone by tomorrow):
   - `EFDIS1;=` + binary — EFDI-internal, unidentified, not CoT-shaped.
   - Small protobuf-ish blobs containing plaintext fragments like
     `"ibex-alpha"`, `"islanded"` — look like internal roster/telemetry from
     the "goat" tooling.
   - JSON presence/roster announcements, e.g.
     `{"display_name":"ibex-alpha","vendor":"ibex","site":"alpha-fob","publishes":[...],"subscribes":["**"],...}`.
   - **`goat.geo.track.v1` JSON** — the interesting one. Well-structured,
     clearly CoT-relevant:
     ```json
     {"schema":"goat.geo.track.v1","id":"synth:900","lat":50.071363,"lon":8.350876,
      "alt_m":2222.6,"course_deg":278.5,"speed_mps":19.61,"cot_type":null,
      "affiliation":"unknown","dimension":"air","how":"simulated",
      "observed_at":"2026-09-17T22:29:04.493Z", ...}
     ```
     Every sample seen so far is `"synthetic": true` from `efdi-prod`'s
     `synthetic-tracks` node — i.e. this looks like the exercise's own
     reference/test generator, not a real participant. `cot_type` is
     present as a field but always `null` in what we've seen — a real
     vendor might populate it directly.

## Open question for the user

Given XML/TAK-protobuf alone currently yields **nothing** from real mesh
traffic, whereas `goat.geo.track.v1` JSON is common and clearly
CoT-relevant: **should ingest be extended to decode that JSON schema too?**
Held off on building this without checking the portal/schema registry first
(only have four synthetic examples to infer field semantics from, e.g. what
`dimension`/`affiliation` values should map to which CoT type codes).

The user's own hypothesis (2026-09-17 23:4x): real TAK-formatted (XML or
protobuf) data *does* sometimes appear on the mesh, just not during the two
capture windows run today (both with few developers connected). Re-run the
capture with more participants online before concluding TAK protobuf
support is unexercised by choice rather than by bad timing.

## How to resume: re-running the capture

The plugin currently installed on the phone already has the diagnostic
logging described below (commit `1c4d7f6`). To repeat/extend the capture:

```bash
adb logcat -c
adb logcat -v time -s CotBridgeService:V > /tmp/capture.txt
```

Toggle the plugin off/on in ATAK (or restart ATAK) first if you've rebuilt
since it was last loaded — Android doesn't hot-swap a running app's already
loaded classes just because the APK on disk changed.

Then watch for:
- `RECOGNIZED CoT-shaped payload` — a positive hit: XML or TAK-protobuf CoT
  was actually decoded. This is the thing to look for.
- `Unrecognized payload preview: bytes(...)` / `text(...)` — everything
  else, format-sniffed and logged at `Log.v` so it doesn't spam at normal
  levels.

Quick queries once you have a capture:
```bash
grep -c "RECOGNIZED" /tmp/capture.txt
grep "Unrecognized payload preview" /tmp/capture.txt | grep -oE 'hex\[:64\]=bf[0-9a-f]*'   # any TAK-protocol-shaped (0xbf-prefixed) payloads?
grep -c '"schema": "goat.geo.track.v1"' /tmp/capture.txt
```

## Cleanup once this is resolved

`CotBridgeService.java` has two blocks explicitly marked
`// TEMPORARY ... remove`:
- The `Log.v(TAG, "Unrecognized payload preview: " + previewPayload(payload))` call and the `previewPayload()` helper method.
- The `Log.i(TAG, "RECOGNIZED CoT-shaped payload (...)")` call.

Remove both (and this doc, or trim it to a changelog note) once the
TAK-protobuf path is confirmed against real traffic and any decision on
`goat.geo.track.v1` is made and, if yes, implemented.
