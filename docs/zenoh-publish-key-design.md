# Outbound publish key design

**Status: fixed 2026-09-18.** The plugin previously published every outbound
CoT event from ATAK to one fixed Zenoh key, with the CoT `uid` left in the
XML payload. That is the textbook "one key, discriminator in the payload"
anti-pattern -- see
`/home/tim/efdi/hello-galaxy-zenoh-patterns/docs/anti-patterns.md` #1 -- and
the fabric's own house guidance is explicit that CoT doesn't belong there:

> "a track is Pattern B keyed by its uid ... the fabric's own
> `pubsub-best-practices` guide ... maps vehicle tracks and CoT to Pattern B
> keyed by the track/CoT uid" -- `hello-galaxy-zenoh-patterns/docs/identity-spectrum.md`

Confirmed against real traffic too: the two real CoT publishers seen on the
EFDI mesh (see `docs/zenoh-mesh-investigation.md`) do this differently.
`tak/cot/v1/EDGEAI.edge-ai-01.hostile-uav-00` follows Pattern B correctly
(version in the stable prefix, uid last). `ITA-EFDI/DRONE-01/v1` does not --
it's the *inverted* shape the patterns repo's README calls out as the
motivating real case (`<anchor>/aircraft/<id>/v1`, entity in the middle,
version last), which still retrieves but blinds any inspection tool that
folds a tree view on the last segment.

The wire contract this design produces -- key shape, delivery settings and
the payload as actually observed from a device -- is written up as an
AsyncAPI document in [`asyncapi.yaml`](asyncapi.yaml). Validate it with
`npx @asyncapi/cli validate docs/asyncapi.yaml`;
`AsyncApiSpecTest` keeps its channel address in step with
`buildPublishKey`.

## What changed

`CotBridgeService.onCotEvent` used to call `bridge.publish(event.toString())`
against one `Publisher` declared once, up front, for the configured
"Publish Topic" preference. Now:

- The preference (`zenohPublishTopic`, still the same stored value) is
  reinterpreted as a **prefix** (`ZenohSettings.getPublishTopicPrefix()`),
  not a complete key.
- `CotBridgeService.buildPublishKey(prefix, uid)` appends the CoT event's own
  `uid` as the key's last segment (sanitized -- see below) --
  `<prefix>/<uid>`, matching Pattern B.
- `ZenohBridge` no longer pre-declares a single `Publisher` (which only
  makes sense for a fixed key); it calls `Session.put(keyExpr, xml)`
  per event, since the key now varies with the event's uid.

Tests: `CotBridgeServicePublishKeyTest` (key building/sanitization -- pure
JVM logic, no Android/Zenoh dependency).

## Why sanitize the uid

Zenoh key expressions give `*`, `**` and `$` wildcard/reserved meaning and
disallow `#`/`?`; a segment built from an arbitrary CoT `uid` could contain
any of those (or, for non-track CoT types like GeoChat, spaces and extra
dots). `buildPublishKey` maps anything outside `[A-Za-z0-9._-]` to `_` rather
than risking an invalid key expression at publish time -- see
`hello-galaxy-zenoh-patterns/docs/key-expression-design.md`'s "keep segments
opaque and URL-safe" rule.

## Cardinality (deliberately not a concern here)

`anti-patterns.md` #6 warns that unbounded per-message keys ("topic sprawl")
overload the router's shared key table -- but that's about **unbounded**
dimensions (frame numbers, per-message ids). A CoT `uid` is exactly the
**bounded, selectable dimension** the same doc says belongs in the key: one
key per unit ATAK is actually tracking/originating (self marker, locally
known friendlies, chat rooms), not one key per message. A single ATAK
instance publishing its own CoT is nowhere near the >100-distinct-topics
threshold the doc flags as needing platform-admin approval on EFDI.

## Not yet done

The plugin's ingest side (`decode()`/`TakProtoCotConverter`, see
`docs/zenoh-mesh-investigation.md`) was unaffected by this -- it already
subscribes broadly (`**` or user-configured key expressions) and doesn't
care how a publisher shaped their key. This change is outbound-only.
