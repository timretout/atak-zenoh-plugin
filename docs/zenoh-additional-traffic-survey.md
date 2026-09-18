# Survey: what else on the mesh could become map data?

**Status: analysis only, no code changes.** Follow-up to
`docs/zenoh-mesh-investigation.md` (which resolved CoT XML/protobuf ingest).
This mines two captures -- the original 5-minute one
(`/tmp/mesh_capture.jsonl`, 127,602 samples, 2026-09-18 ~09:29 BST) and a
longer targeted one started ~10:06 BST the same day
(`/tmp/mesh_capture_long.jsonl`, running up to 50 min, watching for topics
that hadn't fired yet) -- for other structured, geo-bearing schemas that
aren't CoT XML/protobuf, to see what else the plugin could reasonably decode
and put on the map. Nothing here is implemented -- these are candidates for
a decision, same as the `goat.geo.track.v1` question raised (and made moot
by real traffic) in the original investigation.

## 2026-09-18 ~10:06 update: two much stronger candidates found

The longer capture caught topics that hadn't appeared in the first 5-minute
window (mesh traffic composition varies -- different demo scenarios come
and go). Two of them reshape the ranking below:

### 0a. `PATCH/tracks/v1/<uid>` JSON -- this answers the open TAK-protobuf question

```json
{"uid":"PATCH20252600182.3","type":"a-f-G-U","callsign":"PATCH-3",
 "lat":50.68067932128906,"lon":-2.252140998840332,"hae":null,
 "ce":999999.0,"le":999999.0,"how":"m-g","team":"Cyan","role":"Team Member",
 "course":0.4,"speed":1.0,"battery":null,"platform":null,"version":null,
 "encoding":"tak-protobuf","seen":1789722254.9426475,
 "source":"tak-zenoh-bridge"}
```

This is not a new schema to map onto CoT -- **it already *is* CoT**, field
for field (`uid`, `type` is a real CoT type code, `callsign`, `lat`/`lon`/
`hae`/`ce`/`le`, `how`, `team`, `role`, `course`, `speed`), just serialized
as JSON instead of XML or the TAK-protocol wire format. The
`"encoding":"tak-protobuf"` and `"source":"tak-zenoh-bridge"` fields are the
real finding: **somewhere upstream, a node called `tak-zenoh-bridge` is
receiving genuine TAK-protobuf CoT and re-publishing it as this JSON
mirror.** Two capture sessions never caught a raw `0xbf`-framed payload
directly, but this is solid evidence the format is genuinely in use on this
fabric -- we just haven't been subscribed to wherever the bridge's *input*
side lives. Worth asking on the portal where `tak-zenoh-bridge` sources its
protobuf feed from.

The key shape here (`<anchor>/PATCH/tracks/v1/<uid>`) is also a correct
Pattern-B example, version-then-uid, same as `tak/cot/v1/EDGEAI.*`.

**This is the single easiest, lowest-risk decode of everything in this
survey** -- there's no affiliation/type-code ambiguity to resolve at all,
because the source already picked the CoT type. A decoder is close to a
direct JSON-to-XML field remap (`event/@uid,@type,@how,@time`, `point/
@lat,@lon,@hae,@ce,@le`, `detail/contact/@callsign`, `detail/track/
@course,@speed`, `detail/__group/@name=team`), not a schema-mapping
judgment call. Only real question is whether it's worth adding a *third*
wire format when the two things it would decode (a real CoT event) are
presumably *also* reaching us as XML or protobuf directly from whatever
`tak-zenoh-bridge` bridges from -- i.e. does skipping this mean we're
missing traffic, or double-counting it? Worth checking before building.

### 0b. `lattice/entity/track/v1/<...>` JSON -- cleanest disposition mapping seen

```json
{"entityId": "efdi-fabric-bridge-rpi-4gb:hostile-uav-01", "isLive": true,
 "createdTime": "2026-09-18T08:28:27.877Z",
 "expiryTime": "2026-09-18T09:05:15.310Z",
 "location": {"position": {"latitudeDegrees": 60.54, "longitudeDegrees": 25.83,
   "altitudeHaeMeters": 200}},
 "aliases": {"alternateIds": [{"id": "hostile-uav-01", "type": "ALT_ID_TYPE_CALLSIGN"}],
   "name": "EFDI -- hostile-uav (hostile-uav-01)"},
 "milView": {"disposition": "DISPOSITION_HOSTILE", "environment": "ENVIRONMENT_AIR"},
 "ontology": {"platformType": "HOSTILE-UAV", "template": "TEMPLATE_TRACK"},
 "provenance": {...}}
```

This is Anduril's published **Lattice entity schema** (an industry format,
not EFDI-specific) -- confirms the earlier `sensor/v1` JSON guess from the
first capture (same `milView`/`ontology`/`producedBy` shape) was in fact
Lattice-flavored. Unlike every other candidate here, **this one has an
explicit disposition field** (`DISPOSITION_HOSTILE` -- presumably also
`DISPOSITION_FRIENDLY`/`_NEUTRAL`/`_UNKNOWN`, only hostile observed so far)
*and* an explicit dimension (`ENVIRONMENT_AIR` -- presumably
`_GROUND`/`_SURFACE`/`_SUBSURFACE`/`_SPACE`), which is exactly the
information the other candidates (`orion.exercise.track.v1`, `iff/track`)
were missing to build an unambiguous CoT type code. `expiryTime` maps
directly to CoT `stale`; `isLive` is an extra explicit liveness flag CoT
doesn't otherwise have. The main follow-up needed before building this is
enumerating the full `disposition`/`environment` value sets (only one value
of each seen so far) -- likely to be in Anduril's public Lattice API docs
rather than something to guess from samples alone.

**Revised overall ranking: `PATCH/tracks/v1` first (already CoT, no mapping
judgment at all), `lattice/entity` second (only candidate with a real
disposition field), then the original three below.**

### Other things seen in the longer capture, not CoT-relevant

`silent-sentinel/events/v1` (edge camera detection events --
`classification: "car"`, `severity: "high"` -- but no lat/lon in the
payloads seen; the device/site presumably has a fixed known position looked
up elsewhere, not carried in-event), `bc2a/nvg_2_0_2` (NATO NVG-format
tactical overlay graphics using real APP-6 SIDCs, e.g.
`"symbol":"app6a:SHAP------*****"`, inside a group literally named "B61
EnemyAndUnknown" -- real symbology, but mapping APP-6 SIDCs to CoT 2525
type codes needs a proper lookup table, not a quick decode; flagging as a
real but bigger follow-up project), `neura/effector/status/v1/<id>` (fires
very frequently, looks like length-delimited protobuf by its byte pattern,
but undocumented -- would need the vendor's schema), `soc-bx/unified/raw`
(also binary/undocumented). `ITA-EFDI/RADAR-TRACK-01/*` and `tactiql/v1`
are already-real CoT XML (the `tactiql` vendor is the ASTERIX-to-CoT bridge
confirmed live on-device earlier) -- already covered by existing ingest,
not new gaps.

## Ranked candidates (original 5-minute-capture findings)

### 1. `orion.exercise.track.v1` JSON -- strongest candidate

Seen on `orion/synthetic` and `catalyst/v1`. Self-describing (`"schema"`
field), clean and unambiguous:

```json
{"schema": "orion.exercise.track.v1", "synthetic": true,
 "track_id": "heathrow-circle-01", "lat": 51.483, "lon": -0.463,
 "altitude_m": 120.0, "altitude_reference": "AGL", "heading_deg": 71.7,
 "speed_mps": 30.0, "observed_at": "2026-09-18T08:28:40.339267Z"}
```

Every field maps cleanly to a CoT `<event>`/`<point>`/`<track>`: `track_id`
-> uid, `lat`/`lon`/`altitude_m` -> point, `heading_deg`/`speed_mps` ->
`<track>`, `observed_at` -> time. The **open question** is CoT `type` --
this schema has no disposition/affiliation field at all (no
friend/hostile/unknown), so a decoder would have to default every track to
something generic (`a-u-A` "unknown air") or omit type-specific styling.
Also worth checking whether this is meant to be consumer-visible at all --
every sample seen had `"synthetic": true`.

### 2. `iff/track` + `iff/fleet` JSON -- clean affiliation, but sparse position

```json
{"id":3,"classification":"friend","status":"pass","lat":50.680927,
 "lon":-2.25271,"alt_m":null,"sats":6,"age_s":1, ...}
```

`classification` maps directly to CoT affiliation (`friend` -> `a-f-...`).
Only `friend` was observed in this capture (no `hostile`/`unknown`/`foe`
seen) -- worth confirming the full value set before hard-coding a mapping.
`lat`/`lon` are frequently `null` (station hasn't got a GPS-quality fix on
that IFF interrogation yet -- see `status`/`age_s`); a decoder would need to
skip position-less samples rather than plotting `(0,0)`. `iff/station`
gives the interrogator's own fixed position, separately.

### 3. `consilium/sa-event` JSON -- richest single-platform schema

```json
{"ci_name":"Virtual UGV 1","ci_uuid":"b84a8f37-...","timestamp":"...",
 "location":{"latitude":"51.45404328","longitude":"0.23545907"},
 "device":{"category":"Vehicle","type":"Vehicle","make":"Espanaro","model":"Virtual UGV"},
 "parameters":[{"name":"status","value":"DEPLOYED"},{"name":"speed",...}]}
```

Note `latitude`/`longitude` are **strings**, not numbers -- a real parsing
gotcha if this is ever decoded. `device.category` gives a platform type
(`Vehicle` here) but no friend/hostile affiliation at all (this looks like
*our own* asset telemetry, from `consilium/command`'s `DEPLOY_UGV` sitting
right next to it -- i.e. probably always friendly-by-construction, but
that's an assumption, not something in the payload).

### Not worth decoding -- already covered indirectly

- **`sensor/v1`** (`{"seq","lat","lon","alt","heading","speed_kmh","phase","source":"hostile-uav-01","type":"hostile-uav"}`) and **`edge/ai`** (adds `cot_type`, `conf`, `bbox_xywh`) are the *upstream* raw feed and the ML detection stage for the exact same hostile-uav tracks that already arrive as proper CoT XML on `tak/cot/v1/EDGEAI.edge-ai-01.*` (confirmed live and parsing correctly -- see the mesh investigation doc). Decoding these ourselves would just re-derive what's already on the map via the correct downstream path, with more ambiguity (raw `speed_kmh`/`heading` vs. CoT's own conventions) for no new information.
- **`opensky/json`** -- real OpenSky Network ADS-B state vectors (global air traffic, standard documented array schema: `icao24, callsign, origin_country, ..., lon, lat, baro_altitude, ...`). Almost certainly the upstream source already bridged into the `asterix-cat048-*` CoT XML confirmed live on-device (`RDR-<hash>-asterix-cat048-*` uids, CAT048 being a radar/ADS-B-derived surveillance format). Redecoding it ourselves risks double-plotting the same aircraft under two different uid schemes.
- **`imagery/v1`** -- camera frame + pose + base64 JPEG. The pose's `platform_uid` (e.g. `EFDI.uav.recon-1`) could in principle become a CoT marker for the camera platform, but there was no separate non-imagery telemetry topic for that uid in this capture, and shipping a decoder whose only output is "plot the camera, discard the image" feels like a stretch for the payload size involved. Low priority.

### Not CoT-relevant at all

`schema/*` (goat tooling's own topic/role registry -- same as the
`display_name`/`publishes`/`subscribes` roster JSON flagged in the original
investigation), `*/heartbeat` (liveness pings for ~15 different systems,
`{"system","status","version","sent_at","seq"}` shape), `acoustics/v1`
(sensor readings, no lat/lon in-payload), `keys/pub` (crypto key
distribution), `consilium/command` (a command/ack exchange, not telemetry),
`soc-bx/health` (an unrelated app's health check), `test/*`.

### Seen in the topic registry but not captured this session

Several `schema/*` entries advertise `publishes` for effector event topics
-- `aurochs/charlie-fob/launched/v1`, `basilisk/alpha-fob/deployed/v1`,
`wolverine/alpha-fob/engagements/v1`, `ibex/alpha-fob/launched/v1`,
`peregrine/alpha-fob/orders/v1`, `condor/theater/orders/v1`,
`falcon/bravo-fob/orders/v1`, `mongoose/alpha-fob/status/v1`,
`narwhal/alpha-fob/events/v1` -- but none of these actually fired during
this 5-minute window (they're presumably event-driven, not periodic). These
could be the most operationally interesting things on the whole mesh
(weapon/effector launch and engagement events), but there's nothing to
analyze yet -- worth a longer or event-triggered capture.

## Recommendation

Don't implement any of these yet -- this is a decision for you, not
something to ship while you're away, since it changes what appears on a
live map. But unlike the first pass, there are now two genuinely low-risk
candidates worth prioritizing over the rest:

1. **`PATCH/tracks/v1/<uid>`** -- effectively free: it's already CoT field-
   for-field, just JSON-shaped. The only real question is upstream (does
   decoding it double-count traffic we already get another way from
   whatever `tak-zenoh-bridge` bridges from) -- worth a quick portal/vendor
   check, not a design question.
2. **`lattice/entity/track/v1/*`** -- the only schema surveyed with a real
   disposition field, so no guessing at friend/hostile. Needs the full
   `disposition`/`environment` enum values confirmed (Anduril's public
   Lattice docs, not guesswork) before building the CoT-type mapping.

`orion.exercise.track.v1` and `iff/track` remain clean but still lack any
disposition/affiliation field, so building those still means either
defaulting every track to unknown or asking the vendor. `consilium/sa-event`
has the string-typed lat/lon gotcha to watch for. `bc2a/nvg_2_0_2` (APP-6
SIDCs) is real and interesting but a bigger project (a SIDC-to-CoT-type
lookup table, not a quick decode). The effector event topics
(`*/launched`, `*/deployed`, `*/engagements`, `*/orders`) still haven't
fired in either capture -- still nothing concrete to design against.
