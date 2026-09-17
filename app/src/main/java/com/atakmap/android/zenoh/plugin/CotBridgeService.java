
package com.atakmap.android.zenoh.plugin;

import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.comms.CotServiceRemote;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

import java.util.List;

/**
 * Owns the live Zenoh <-> CoT bridge: opens/closes the {@link ZenohBridge}
 * session and taps ATAK's CoT traffic via {@link CotServiceRemote} so that
 * outbound CoT gets republished to the configured Zenoh publish topic.
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

    private final Context pluginContext;
    private final ZenohBridge bridge = new ZenohBridge();

    private CotServiceRemote cotServiceRemote;
    private boolean running = false;

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
            String publishTopic = settings.getPublishTopic();

            bridge.start(settings.buildConfigJson5(), subscribeTopics, publishTopic,
                    this::onZenohCotReceived);

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

            running = true;
            Log.i(TAG, "Zenoh bridge started: endpoint=" + settings.getRouterEndpoint()
                    + " subscribe=" + subscribeTopics + " publish=" + publishTopic);
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
        bridge.stop();
        running = false;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    /** Called back by {@link ZenohBridge} on a Zenoh-internal thread for every received sample. */
    private void onZenohCotReceived(final String xml) {
        final MapView mapView = MapView.getMapView();
        if (mapView == null)
            return;
        mapView.post(new Runnable() {
            @Override
            public void run() {
                try {
                    CotEvent event = CotEvent.parse(xml);
                    if (event == null || !event.isValid()) {
                        Log.w(TAG, "Discarding invalid CoT received from Zenoh");
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

    /** {@link CotServiceRemote.CotEventListener} -- taps all CoT traffic flowing through ATAK. */
    @Override
    public void onCotEvent(CotEvent event, Bundle extra) {
        if (event == null)
            return;
        if (extra != null && extra.getBoolean(EXTRA_FROM_ZENOH, false))
            return;
        try {
            bridge.publish(event.toString());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to publish CoT event to Zenoh", t);
        }
    }
}
