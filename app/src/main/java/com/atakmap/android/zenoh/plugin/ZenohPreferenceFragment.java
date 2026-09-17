
package com.atakmap.android.zenoh.plugin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.preference.Preference;

import com.atakmap.android.gui.ImportFileBrowserDialog;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.preference.PluginPreferenceFragment;

import java.io.File;

/**
 * Tool Preferences screen for the Zenoh bridge: router/TLS connection
 * settings and the subscribe/publish topic lists. Reachable from
 * ATAK Settings &gt; Tool Preferences &gt; Zenoh Preferences.
 */
public class ZenohPreferenceFragment extends PluginPreferenceFragment {

    private static Context staticPluginContext;

    private static final String[] CERT_EXTENSIONS = {
            ".pem", ".crt", ".cer", ".key", ".der"
    };

    /** Required zero-arg constructor -- only used by the fragment framework after restore. */
    public ZenohPreferenceFragment() {
        super(staticPluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public ZenohPreferenceFragment(final Context pluginContext) {
        super(pluginContext, R.xml.preferences);
        staticPluginContext = pluginContext;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        wireFileBrowserPreference(ZenohSettings.PREF_CA_CERT_PATH, "Select CA Certificate");
        wireFileBrowserPreference(ZenohSettings.PREF_CLIENT_CERT_PATH, "Select Client Certificate");
        wireFileBrowserPreference(ZenohSettings.PREF_CLIENT_KEY_PATH, "Select Client Private Key");
    }

    private void wireFileBrowserPreference(final String key, final String dialogTitle) {
        final Preference pref = findPreference(key);
        if (pref == null)
            return;

        final AtakPreferences prefs = AtakPreferences.getInstance(staticPluginContext);
        String existing = prefs.get(key, "");
        if (!existing.isEmpty())
            pref.setSummary(existing);

        pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                ImportFileBrowserDialog.show(dialogTitle, CERT_EXTENSIONS,
                        new ImportFileBrowserDialog.DialogDismissed() {
                            @Override
                            public void onFileSelected(File file) {
                                if (file == null)
                                    return;
                                prefs.set(key, file.getAbsolutePath());
                                pref.setSummary(file.getAbsolutePath());
                            }

                            @Override
                            public void onDialogClosed() {
                                // no-op
                            }
                        }, getActivity());
                return true;
            }
        });
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", "Zenoh Preferences");
    }
}
