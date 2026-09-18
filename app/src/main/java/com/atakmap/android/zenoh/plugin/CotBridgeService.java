
package com.atakmap.android.zenoh.plugin;

import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.MapView;
import com.atakmap.comms.CotServiceRemote;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Owns the live Zenoh <-> CoT bridge: opens/closes the {@link ZenohBridge}
 * session and republishes ATAK's own outbound CoT to the configured Zenoh
 * publish prefix.
 *
 * Outbound CoT is tapped two ways, per the SDK's own {@code commout-simplesocket}
 * sample (comms-engine-replacement pattern) rather than {@link CotServiceRemote}
 * alone: {@link CotServiceRemote.CotEventListener} only fires for CoT that
 * passes through ATAK's *internal* dispatcher (e.g. this plugin's own
 * Zenoh-inbound re-injection looping back) -- confirmed empirically that a
 * user manually sharing/broadcasting a marker does NOT invoke it, since that
 * goes out via a separate external send path with no generic Java-level
 * listener hook. The fix, matching the sample: listen for
 * {@link MapEvent#ITEM_SHARED} / {@link MapEvent#ITEM_PERSIST} on
 * {@link MapView#getMapEventDispatcher()}, and convert the shared/persisted
 * {@link MapItem} to a real {@link CotEvent} via {@link CotEventFactory}
 * (unlike the sample, which invents its own pipe-delimited wire format).
 * Self-position ("ownship") isn't covered by either of those either, so it's
 * republished on its own periodic timer, also per the sample.
 *
 * All start/stop calls are expected from the plugin's map-component
 * lifecycle hooks (onStart/onStop), not from arbitrary threads.
 */
public class CotBridgeService implements CotServiceRemote.CotEventListener {

    private static final String TAG = "CotBridgeService";

    /**
     * Bundle key used to mark a CoT event that this service itself just
     * injected from a Zenoh subscription, so the {@link CotServiceRemote}
     * tap below can recognize and skip it -- otherwise it would be
     * immediately republished back out to Zenoh, echoing forever.
     */
    private static final String EXTRA_FROM_ZENOH = "com.atakmap.android.zenoh.plugin.FROM_ZENOH";

    /** How often the self marker republishes, independent of any share/broadcast action. */
    private static final long SELF_POSITION_INTERVAL_MS = 15_000L;

    private final Context pluginContext;
    private final ZenohBridge bridge = new ZenohBridge();
    private final Timer selfPositionTimer = new Timer("ZenohSelfPosition", true);

    private CotServiceRemote cotServiceRemote;
    private MapEventDispatcher.MapEventDispatchListener outboundMapEventListener;
    private TimerTask selfPositionTask;
    private boolean running = false;
    private volatile String publishTopicPrefix;

    public CotBridgeService(Context pluginContext) {
        this.pluginContext = pluginContext;
    }

    public synchronized void start(ZenohSettings settings) {
        stop();

        if (!settings.isConfigured()) {
            Log.d(TAG, "Zenoh bridge is disabled or unconfigured; not starting");
            return;
        }

        try {
            List<String> subscribeTopics = settings.getSubscribeTopics();
            publishTopicPrefix = settings.getPublishTopicPrefix();

            bridge.start(settings.buildConfigJson5(), subscribeTopics, this::onZenohSampleReceived);

            cotServiceRemote = new CotServiceRemote();
            cotServiceRemote.setCotEventListener(this);
            cotServiceRemote.connect(new CotServiceRemote.ConnectionListener() {
                @Override
                public void onCotServiceConnected(Bundle fullServiceState) {
                    Log.d(TAG, "CotServiceRemote connected");
                }

                @Override
                public void onCotServiceDisconnected() {
                    Log.d(TAG, "CotServiceRemote disconnected");
                }
            });

            final MapView startMapView = MapView.getMapView();
            if (startMapView != null) {
                outboundMapEventListener = new MapEventDispatcher.MapEventDispatchListener() {
                    @Override
                    public void onMapEvent(MapEvent event) {
                        onOutboundMapEvent(event);
                    }
                };
                MapEventDispatcher dispatcher = startMapView.getMapEventDispatcher();
                dispatcher.addMapEventListener(MapEvent.ITEM_SHARED, outboundMapEventListener);
                dispatcher.addMapEventListener(MapEvent.ITEM_PERSIST, outboundMapEventListener);
            }

            selfPositionTask = new TimerTask() {
                @Override
                public void run() {
                    publishSelfPosition();
                }
            };
            selfPositionTimer.schedule(selfPositionTask, 0, SELF_POSITION_INTERVAL_MS);

            running = true;
            Log.i(TAG, "Zenoh bridge started: endpoint=" + settings.getRouterEndpoint()
                    + " subscribe=" + subscribeTopics + " publishPrefix=" + publishTopicPrefix);
        } catch (final Throwable t) {
            Log.e(TAG, "Failed to start Zenoh bridge", t);
            stop();

            final MapView mapView = MapView.getMapView();
            if (mapView != null) {
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(pluginContext,
                                "Zenoh bridge failed to start: " + t.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
    }

    public synchronized void stop() {
        if (cotServiceRemote != null) {
            try {
                cotServiceRemote.disconnect();
            } catch (Throwable t) {
                Log.e(TAG, "Error disconnecting CotServiceRemote", t);
            }
            cotServiceRemote = null;
        }
        if (outboundMapEventListener != null) {
            final MapView mapView = MapView.getMapView();
            if (mapView != null) {
                MapEventDispatcher dispatcher = mapView.getMapEventDispatcher();
                dispatcher.removeMapEventListener(MapEvent.ITEM_SHARED, outboundMapEventListener);
                dispatcher.removeMapEventListener(MapEvent.ITEM_PERSIST, outboundMapEventListener);
            }
            outboundMapEventListener = null;
        }
        if (selfPositionTask != null) {
            selfPositionTask.cancel();
            selfPositionTask = null;
        }
        bridge.stop();
        publishTopicPrefix = null;
        running = false;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    /**
     * Called back by {@link ZenohBridge} on a Zenoh-internal thread for
     * every received sample. This is a broad, mixed-mesh subscription --
     * other vendors' non-CoT traffic on the same topics is expected, not an
     * error, so anything that isn't recognizably CoT XML or TAK Protocol
     * (protobuf) CoT is dropped quietly rather than logged as a warning.
     *
     * Duplicate suppression: none beyond what CoT itself already gives us
     * for free -- {@link CotMapComponent#getInternalDispatcher()} upserts by
     * UID, so repeated/duplicate reports of the *same* UID update one
     * marker rather than piling up. Different vendors reporting the same
     * real-world entity under independently-chosen UIDs would need
     * content-level de-duplication, which this doesn't attempt.
     */
    private void onZenohSampleReceived(final byte[] payload) {
        final String xml = decode(payload);
        if (xml == null) {
            // TEMPORARY diagnostic for live-traffic investigation -- remove
            // before shipping; this format-sniffs a mixed-vendor mesh, so
            // "unrecognized" is the expected common case, not worth a
            // standing per-message log line.
            Log.v(TAG, "Unrecognized payload preview: " + previewPayload(payload));
            return;
        }
        // TEMPORARY: unmistakable positive signal for the live-traffic
        // investigation -- remove alongside the block above.
        Log.i(TAG, "RECOGNIZED CoT-shaped payload (" + payload.length + "B): " + xml);

        final MapView mapView = MapView.getMapView();
        if (mapView == null)
            return;
        mapView.post(new Runnable() {
            @Override
            public void run() {
                try {
                    CotEvent event = CotEvent.parse(xml);
                    if (event == null || !event.isValid()) {
                        Log.d(TAG, "Discarding unparseable CoT XML from Zenoh");
                        return;
                    }
                    Bundle extra = new Bundle();
                    extra.putBoolean(EXTRA_FROM_ZENOH, true);
                    CotMapComponent.getInternalDispatcher().dispatch(event, extra);
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to process CoT event received from Zenoh", t);
                }
            }
        });
    }

    /**
     * Recognizes CoT XML, TAK Protocol (protobuf) CoT, and the
     * {@code PATCH/tracks/v1} JSON mirror of TAK-protobuf CoT published by
     * {@code tak-zenoh-bridge}; anything else -> null.
     */
    private static String decode(byte[] payload) {
        int i = 0;
        while (i < payload.length && isXmlWhitespace(payload[i]))
            i++;
        if (i < payload.length && payload[i] == '<')
            return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        if (TakProtoCotConverter.looksLikeTakProto(payload))
            return TakProtoCotConverter.decode(payload);
        if (TakJsonCotConverter.looksLikeTakJson(payload))
            return TakJsonCotConverter.decode(payload);
        return null;
    }

    private static boolean isXmlWhitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\r' || b == '\n';
    }

    /** TEMPORARY: mirrors first-subscriber.py's render_payload() for comparable output. */
    private static String previewPayload(byte[] payload) {
        String text;
        try {
            java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            text = decoder.decode(java.nio.ByteBuffer.wrap(payload)).toString();
        } catch (Exception e) {
            text = null;
        }
        if (text != null) {
            boolean printable = true;
            for (int i = 0; i < text.length() && printable; i++) {
                char c = text.charAt(i);
                if (c == '\r' || c == '\n' || c == '\t')
                    continue;
                if (Character.isISOControl(c))
                    printable = false;
            }
            if (printable)
                return "text(" + payload.length + "B): " + text;
        }
        int previewLen = Math.min(64, payload.length);
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < previewLen; i++)
            hex.append(String.format(java.util.Locale.US, "%02x", payload[i]));
        return "bytes(" + payload.length + "B) hex[:64]=" + hex;
    }

    /**
     * {@link CotServiceRemote.CotEventListener} -- catches CoT that passes
     * through ATAK's *internal* dispatcher. In practice this is mostly this
     * plugin's own Zenoh-inbound events looping back (filtered out below);
     * see the class doc for why manual share/broadcast needs the separate
     * {@link #onOutboundMapEvent} path instead.
     */
    @Override
    public void onCotEvent(CotEvent event, Bundle extra) {
        if (event == null)
            return;
        if (extra != null && extra.getBoolean(EXTRA_FROM_ZENOH, false))
            return;
        publishCotEvent(event);
    }

    /**
     * {@link MapEventDispatcher.MapEventDispatchListener} callback for
     * {@link MapEvent#ITEM_SHARED} (always an explicit send) and
     * {@link MapEvent#ITEM_PERSIST} (only when not marked "internal" --
     * matches the SDK's {@code commout-simplesocket} sample's filter for
     * "this persist is actually meant to go out").
     */
    private void onOutboundMapEvent(MapEvent event) {
        MapItem item = event.getItem();
        if (item == null)
            return;
        if (MapEvent.ITEM_PERSIST.equals(event.getType())) {
            Bundle extras = event.getExtras();
            if (extras != null && extras.getBoolean("internal"))
                return;
        }
        CotEvent cotEvent = CotEventFactory.createCotEvent(item);
        if (cotEvent == null)
            return;
        publishCotEvent(cotEvent);
    }

    /** Republishes the self ("ownship") marker on {@link #SELF_POSITION_INTERVAL_MS}. */
    private void publishSelfPosition() {
        MapView mapView = MapView.getMapView();
        if (mapView == null)
            return;
        Marker self = mapView.getSelfMarker();
        if (self == null)
            return;
        CotEvent event = CotEventFactory.createCotEvent(self);
        if (event == null)
            return;
        publishCotEvent(event);
    }

    /** Shared by every outbound path: builds the per-entity key and publishes. */
    private void publishCotEvent(CotEvent event) {
        String prefix = publishTopicPrefix;
        if (prefix == null || prefix.isEmpty())
            return;
        try {
            bridge.publish(buildPublishKey(prefix, event.getUID()), event.toString());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to publish CoT event to Zenoh", t);
        }
    }

    /**
     * Builds the Zenoh key a CoT event publishes under: {@code prefix} with
     * the event's own uid appended as the last, selectable key segment.
     *
     * This follows the fabric's per-entity-state guidance (Pattern B, see
     * docs/zenoh-publish-key-design.md and
     * hello-galaxy-zenoh-patterns/docs/identity-spectrum.md, which maps
     * tracks and CoT to "Pattern B keyed by the track/CoT uid"): a single
     * fixed publish key for every event -- the prior behavior -- buries the
     * discriminator in the payload, so a latest-value storage watching that
     * key only ever retains whichever unit happened to publish most
     * recently, and other consumers can't select "just this unit" by key.
     */
    static String buildPublishKey(String prefix, String uid) {
        String trimmed = prefix;
        while (trimmed.endsWith("/"))
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed + "/" + sanitizeKeySegment(uid);
    }

    /**
     * Zenoh key-expression segments can't safely carry arbitrary CoT uid
     * content (some CoT types, e.g. GeoChat, use uids with spaces/dots
     * outside a plain track uid), and {@code *}/{@code $}/{@code #} have
     * wildcard/reserved meaning in a key expression. Anything outside the
     * conservative safe set becomes {@code _} rather than risking an
     * invalid key expression at publish time.
     */
    private static String sanitizeKeySegment(String raw) {
        if (raw == null || raw.isEmpty())
            return "_";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            sb.append(safe ? c : '_');
        }
        return sb.toString();
    }
}
