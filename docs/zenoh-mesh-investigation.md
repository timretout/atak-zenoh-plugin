# EFDI mesh live-traffic investigation — handoff

**Status as of 2026-09-18, ~09:35 BST — RESOLVED.** Resumed this morning with
more developers connected to the mesh, this time capturing directly from the
workstation (Netbird VPN + the onboarding mTLS credentials in
`/home/tim/efdi/onboarding/`) instead of via the phone. Real CoT XML is
confirmed flowing on the mesh; TAK protobuf is not. See "2026-09-18 update"
below for the full result. Original 2026-09-17 write-up kept below for
context.

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

## 2026-09-18 update: resolved

Re-ran the live survey this morning with more participants online, this time
subscribing directly from the workstation over Netbird (not via the phone --
see `/home/tim/efdi/onboarding/first-subscriber.py` for the mTLS connection
pattern this reused) with a broad `**` subscription for 5 minutes.

**Result: 127,602 samples total.**

- **790 genuine CoT XML samples**, from two real producers:
  - `ITA-EFDI/{DRONE-01,DRONE-02,RADAR-01}` -- bare CoT XML (no `<?xml?>`
    declaration), custom `<__radar>` detail element, friendly (`a-f-...`)
    types.
  - `tak/cot/v1/EDGEAI.edge-ai-01.*` -- fully declared CoT XML, standard ATAK
    detail blocks (`contact`, `status`, `precisionlocation`, `track`,
    `link`, `remarks`), ML-tagged hostile UAV detections (`a-h-A-M-F-Q`,
    confidence in `remarks`, e.g. `p=0.73`).
- **Zero genuine TAK protobuf (`0xbf`-framed) CoT.** One `0xbf`-prefixed and
  two `<`-prefixed samples were seen on `catalyst/v1/talsys/radio/0001`, but
  that topic is a chunked raw WAV/telemetry stream (confirmed via a `RIFF...
  WAVEfmt` header in one chunk) -- the magic-byte matches were coincidental
  (~1-in-256 odds per sample, over ~10,000 samples on that one topic alone).
  **The TAK-protobuf ingest path remains unverified against real traffic**
  even with a much busier mesh than yesterday -- this now looks more like it
  may genuinely be unused by current vendors than a timing artifact.
- `goat.geo.track.v1` JSON (dominant in yesterday's low-traffic capture): zero
  samples this time -- real CoT XML traffic replaced it as the dominant
  CoT-relevant format once real participants were online. **Open question
  from yesterday (extend ingest to this JSON schema) is likely moot now.**
- Other traffic seen, not CoT-shaped: `pcap/parrot-anafi-package-1-raw`
  (102,720 samples -- by far the largest single topic, a drone packet-capture
  replay), `synthetic/asterix-raw`, `sensor/v1`, `iff/{track,fleet,station,
  heartbeat}`, `consilium/sa-event`, `orion/{synthetic,operations}`,
  `Elbit/{DJFI,Nevo}`, several vendor `*/alpha-fob`/`*/bravo-fob` topics.

Full raw capture (JSONL, one line per sample) saved on this machine at
`/tmp/mesh_capture.jsonl` -- not committed (127k lines, includes other
vendors' raw traffic).

**Replay verification against the plugin's actual ingest code:**
`CotBridgeService.decode()` and `TakProtoCotConverter` were exercised
directly against the real captured bytes (radar sample, EDGEAI sample, and
the two `catalyst/v1/talsys/radio` false-positive chunks above) in a new JVM
unit test, [`MeshCaptureReplayTest`](../app/src/test/java/com/atakmap/android/zenoh/plugin/MeshCaptureReplayTest.java):

- Both real CoT XML samples are recognized and passed through unchanged.
- The `0xbf`-coincidence sample is correctly rejected (`TakProtoCotConverter.decode`
  returns `null` on malformed protobuf rather than fabricating a bogus event).
- The `<`-coincidence sample is passed through as "recognized text" by
  `decode()` (which only sniffs the leading byte) -- same as production
  behavior; the actual safety net against that specific false positive is
  `CotEvent.parse()`/`isValid()` further downstream in
  `CotBridgeService.onZenohSampleReceived`, which this JVM test can't
  exercise (see below).
- This also surfaced that the module's `testImplementation` for JUnit was
  missing entirely -- even the pre-existing placeholder `ExampleTest` failed
  to compile before this. Fixed alongside (`app/build.gradle`), plus
  `testOptions.unitTests.returnDefaultValues = true` so Android-stub calls
  (e.g. `Log.v`) don't throw in plain JVM tests.

**Full parse+dispatch pipeline, confirmed on-device (2026-09-18 09:53 BST):**
`com.atakmap.coremap.cot.event.CotEvent.parse()` depends on Android's real
XML parser, which isn't available under the plain-JVM unit test runner even
with `returnDefaultValues = true` (it silently returns an empty
default-valued event instead of throwing, so `isValid()`/`getUID()`/etc.
couldn't be asserted on there -- see `MeshCaptureReplayTest`'s doc comment).
So this was instead confirmed for real against live traffic on the connected
Pixel 8: 45 seconds of `CotBridgeService` logcat produced **235 "RECOGNIZED
CoT-shaped payload" hits, zero "Discarding unparseable"/"Failed to process"**
-- full round trip through `decode()` -> `CotEvent.parse()` -> `isValid()`
-> `CotMapComponent` dispatch, on real mesh traffic, cleanly. Recognized
UIDs included the same `ITA-EFDI-{DRONE-01,RADAR-01}` seen in the direct
capture above, plus a new source not seen there: `asterix-cat048-0-1-*` and
`RDR-<hash>-asterix-cat048-0-1-*` -- a live bridge translating
`synthetic/asterix-raw` (ASTERIX CAT048 air-surveillance) into real CoT XML,
type `a-u-A`, with mode-3A squawk and altitude in `<remarks>`. **This closes
out the CoT XML side of the investigation: real-world CoT XML from multiple
independent producers parses and dispatches correctly, end to end, with the
plugin's current code.**

TAK protobuf remains the one open item -- still zero genuine `0xbf`-framed
hits directly in either capture. Recommend treating `TakProtoCotConverter`
as verified-by-construction (compiles against the SDK's official schemas,
reasoned through the wire format) rather than verified-by-traffic, unless a
vendor confirms on the portal/Slack that they actually publish it.

**Update, same day ~10:06 BST:** a longer follow-up capture (see
`docs/zenoh-additional-traffic-survey.md`) found `PATCH/tracks/v1/<uid>`
JSON carrying `"encoding":"tak-protobuf"` and `"source":"tak-zenoh-bridge"`
-- strong indirect evidence that real TAK-protobuf CoT *does* flow
somewhere on this fabric, just not on a topic we've subscribed to directly;
a node named `tak-zenoh-bridge` is apparently receiving it and
re-publishing a JSON mirror. Worth asking on the portal where that bridge's
protobuf-side input topic is, rather than concluding the format is unused.

**Update, same day ~11:50 BST -- resolved, real TAK protobuf confirmed.**
`TakJsonCotConverter` now decodes that `PATCH/tracks/v1` JSON mirror, and
live traffic includes a genuine hit from a different real person: a
teammate's actual ATAK-CIV instance (`platform: "ATAK-CIV"`,
`version: "5.6.0.12 ..."`, callsign "GAM", team Cyan), relayed through
`tak-zenoh-bridge`. So TAK protobuf is real and in active use on this
fabric -- the raw `0xbf`-framed wire form (`TakProtoCotConverter`) still
hasn't been seen directly, but the underlying protocol clearly is flowing;
`tak-zenoh-bridge` is just decoding it before we ever see the raw bytes.
The `// TEMPORARY` diagnostic logging in `CotBridgeService.java` can now be
removed -- both wire-format questions this doc set out to answer are
settled.

## Cleanup once this is resolved

`CotBridgeService.java` has two blocks explicitly marked
`// TEMPORARY ... remove`:
- The `Log.v(TAG, "Unrecognized payload preview: " + previewPayload(payload))` call and the `previewPayload()` helper method.
- The `Log.i(TAG, "RECOGNIZED CoT-shaped payload (...)")` call.

Remove both (and this doc, or trim it to a changelog note) once the
TAK-protobuf path is confirmed against real traffic and any decision on
`goat.geo.track.v1` is made and, if yes, implemented.
