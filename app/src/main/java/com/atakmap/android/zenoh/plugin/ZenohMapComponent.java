
package com.atakmap.android.zenoh.plugin;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.atakmap.android.maps.AbstractMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.coremap.log.Log;

/**
 * Lifecycle owner for the Zenoh plugin: registers the Tool Preferences
 * screen once, and opens/closes the live {@link CotBridgeService} on the
 * plugin's start/stop hooks (i.e. whenever the plugin is enabled/disabled
 * from the Plugin Manager), restarting it whenever a Zenoh setting changes.
 */
public class ZenohMapComponent extends AbstractMapComponent {

    private static final String TAG = "ZenohMapComponent";
    private static final String TOOL_PREFERENCE_KEY = "zenohPreference";

    private ZenohSettings settings;
    private CotBridgeService bridgeService;
    private AtakPreferences prefs;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                        String key) {
                    if (key != null && key.startsWith("zenoh")) {
                        Log.d(TAG, "Zenoh setting changed (" + key + "); restarting bridge");
                        bridgeService.stop();
                        bridgeService.start(settings);
                    }
                }
            };

    @Override
    public void onCreate(Context context, Intent intent, MapView view) {
        settings = new ZenohSettings(context);
        bridgeService = new CotBridgeService(context);
        prefs = AtakPreferences.getInstance(context);

        ToolsPreferenceFragment.register(
                new ToolsPreferenceFragment.ToolPreference(
                        "Zenoh Preferences",
                        "Configure the Zenoh mesh connection and CoT topics",
                        TOOL_PREFERENCE_KEY,
                        context.getResources().getDrawable(R.drawable.ic_launcher, null),
                        new ZenohPreferenceFragment(context)));

        prefs.registerListener(prefListener);
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        prefs.unregisterListener(prefListener);
        ToolsPreferenceFragment.unregister(TOOL_PREFERENCE_KEY);
        bridgeService.stop();
    }

    @Override
    public void onStart(Context context, MapView view) {
        bridgeService.start(settings);
    }

    @Override
    public void onStop(Context context, MapView view) {
        bridgeService.stop();
    }
}
