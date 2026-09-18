
package com.atakmap.android.zenoh.plugin;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 *
 * <p>Auto-reconnect: seen live against a genuinely flaky mesh -- the
 * router's TCP port stays open while the zenoh session itself intermittently
 * refuses new connections or goes quiet, and separately Android's Doze mode
 * can drop {@link CotServiceRemote}'s own connection to ATAK's comms
 * service. Neither self-heals on its own, so three independent signals each
 * trigger a full {@link #teardown()} + backoff-scheduled {@link #attemptStart()}:
 * the initial {@link ZenohBridge#start} call throwing, {@link
 * CotServiceRemote.ConnectionListener#onCotServiceDisconnected()} firing, and
 * a publish (including the {@link #SELF_POSITION_INTERVAL_MS} self-position
 * beacon, which doubles as a session-health canary) throwing. {@link #stop()}
 * is the only way to actually stop retrying -- while {@link #activeSettings}
 * is non-null, this keeps trying to recover indefinitely.
 *
 * <p>{@link #attemptStart()} always runs on {@link #connectExecutor}, a
 * dedicated background thread, never inline on the caller's thread and never
 * via the main-thread {@link #reconnectHandler} directly (that only posts
 * the hand-off to the executor). Confirmed live why this matters: the very
 * first version of this retry logic scheduled {@code attemptStart()} itself
 * via a main-thread {@code Handler}, and {@link ZenohBridge#start} blocks
 * synchronously on the actual TLS connect -- against a router that was
 * timing out, that produced a real on-device ANR ("Input dispatching timed
 * out ... Waited 5005ms for MotionEvent"), and would have repeated it on
 * every retry for as long as the outage lasted.
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

    /** Reconnect backoff ladder; holds at the last value for a prolonged outage. */
    private static final long[] RECONNECT_BACKOFF_MS = {2_000L, 5_000L, 15_000L, 30_000L, 60_000L};

    private final Context pluginContext;
    private final ZenohBridge bridge = new ZenohBridge();
    private final Timer selfPositionTimer = new Timer("ZenohSelfPosition", true);
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    /** Dedicated thread for the (possibly slow/hanging) connect -- see class doc. */
    private final ExecutorService connectExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ZenohConnect");
        t.setDaemon(true);
        return t;
    });
    private final Runnable reconnectRunnable = () -> connectExecutor.execute(this::attemptStart);

    private CotServiceRemote cotServiceRemote;
    private MapEventDispatcher.MapEventDispatchListener outboundMapEventListener;
    private TimerTask selfPositionTask;
    private boolean running = false;
    private volatile String publishTopicPrefix;

    /** Non-null while the bridge is meant to be up -- start()'s settings, kept for reconnects. */
    private ZenohSettings activeSettings;
    private int reconnectAttempt = 0;

    public CotBridgeService(Context pluginContext) {
        this.pluginContext = pluginContext;
    }

    public synchronized void start(ZenohSettings settings) {
        stop();

        if (!settings.isConfigured()) {
            Log.d(TAG, "Zenoh bridge is disabled or unconfigured; not starting");
            return;
        }

        activeSettings = settings;
        reconnectAttempt = 0;
        connectExecutor.execute(this::attemptStart);
    }

    /**
     * Does the actual connect work {@link #start} used to do inline. Also
     * the reconnect entry point; always runs on {@link #connectExecutor},
     * never on the caller's thread (see class doc for why).
     *
     * The lock is deliberately released for the actual {@link ZenohBridge#start}
     * call: it can block for seconds against a slow/hanging router, and
     * {@link #stop()} (called from the plugin's main-thread lifecycle hooks)
     * must never be stuck waiting on that. After the call returns (or
     * throws), {@link #activeSettings} is re-checked under the lock in case
     * a {@link #stop()} or newer {@link #start} happened while this attempt
     * was in flight -- if so, this attempt is stale and its bridge session
     * (if it opened one) is torn down without being wired up.
     */
    private void attemptStart() {
        final ZenohSettings settings;
        synchronized (this) {
            settings = activeSettings;
        }
        if (settings == null)
            return;

        List<String> subscribeTopics;
        String prefix;
        try {
            subscribeTopics = settings.getSubscribeTopics();
            prefix = settings.getPublishTopicPrefix();
            bridge.start(settings.buildConfigJson5(), subscribeTopics, this::onZenohSampleReceived);
        } catch (final Throwable t) {
            synchronized (this) {
                if (activeSettings != settings)
                    return;
                Log.e(TAG, "Failed to start Zenoh bridge (attempt " + (reconnectAttempt + 1) + ")", t);
                teardown();
                if (reconnectAttempt == 0)
                    showToast("Zenoh bridge failed to start: " + t.getMessage() + " -- will keep retrying");
                scheduleReconnect();
            }
            return;
        }

        synchronized (this) {
            if (activeSettings != settings) {
                bridge.stop();
                return;
            }

            publishTopicPrefix = prefix;

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
                    synchronized (CotBridgeService.this) {
                        if (activeSettings == null)
                            return;
                        teardown();
                        scheduleReconnect();
                    }
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

            boolean recovered = reconnectAttempt > 0;
            running = true;
            reconnectAttempt = 0;
            Log.i(TAG, "Zenoh bridge started: endpoint=" + settings.getRouterEndpoint()
                    + " subscribe=" + subscribeTopics + " publishPrefix=" + publishTopicPrefix);
            if (recovered)
                showToast("Zenoh bridge reconnected");
        }
    }

    /** Schedules the next {@link #attemptStart()}, replacing any pending one. No-op once stopped. */
    private synchronized void scheduleReconnect() {
        if (activeSettings == null)
            return;
        reconnectHandler.removeCallbacks(reconnectRunnable);
        reconnectHandler.postDelayed(reconnectRunnable, backoffDelayMs(reconnectAttempt));
        reconnectAttempt++;
    }

    /** Delay before the Nth (0-indexed) reconnect attempt; holds at the ladder's last rung. */
    static long backoffDelayMs(int attempt) {
        int index = Math.max(0, Math.min(attempt, RECONNECT_BACKOFF_MS.length - 1));
        return RECONNECT_BACKOFF_MS[index];
    }

    private void showToast(final String message) {
        final MapView mapView = MapView.getMapView();
        if (mapView == null)
            return;
        mapView.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(pluginContext, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Deliberate shutdown: cancels any pending reconnect and stops retrying for good. */
    public synchronized void stop() {
        reconnectHandler.removeCallbacks(reconnectRunnable);
        activeSettings = null;
        reconnectAttempt = 0;
        teardown();
    }

    /** The actual resource cleanup, shared by {@link #stop()} and every reconnect path. */
    private synchronized void teardown() {
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
        if (xml == null)
            return;

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

    /**
     * Shared by every outbound path: builds the per-entity key and
     * publishes. A publish failure most likely means the underlying zenoh
     * session has gone bad (seen live: the router accepts the TCP
     * connection but the session silently stops working) -- there's no
     * separate health check, so this doubles as one and triggers the same
     * teardown+reconnect as a start failure or a CotServiceRemote drop.
     */
    private void publishCotEvent(CotEvent event) {
        String prefix = publishTopicPrefix;
        if (prefix == null || prefix.isEmpty())
            return;
        try {
            bridge.publish(buildPublishKey(prefix, event.getUID()), event.toString());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to publish CoT event to Zenoh; scheduling reconnect", t);
            synchronized (this) {
                if (activeSettings == null)
                    return;
                teardown();
                scheduleReconnect();
            }
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
