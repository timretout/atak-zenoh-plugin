
package com.atakmap.android.zenoh.plugin;

import android.content.Context;

import com.atakmap.android.preference.AtakPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed read/write access to this plugin's Tool Preferences, and JSON5
 * Zenoh client config assembly from those preferences.
 */
public class ZenohSettings {

    public static final String PREF_ENABLED = "zenohEnabled";
    public static final String PREF_ROUTER_ENDPOINT = "zenohRouterEndpoint";
    public static final String PREF_TLS_ENABLED = "zenohTlsEnabled";
    public static final String PREF_CA_CERT_PATH = "zenohCaCertPath";
    public static final String PREF_CLIENT_CERT_PATH = "zenohClientCertPath";
    public static final String PREF_CLIENT_KEY_PATH = "zenohClientKeyPath";
    public static final String PREF_SUBSCRIBE_TOPICS = "zenohSubscribeTopics";
    public static final String PREF_PUBLISH_TOPIC = "zenohPublishTopic";

    /**
     * Default subscribe list for a fresh install: the key-expression
     * prefixes confirmed (docs/zenoh-mesh-investigation.md,
     * docs/zenoh-additional-traffic-survey.md) to actually carry CoT, each
     * with a leading {@code **} since the anchor (first segment) is a
     * different, unpredictable slot id per publisher. Deliberately narrower
     * than {@code **}: a broad subscription means receiving every other
     * vendor's traffic too, including large non-CoT payloads (imagery,
     * pcap replay, live video) that cost bandwidth/battery to receive and
     * discard even though decode() rejects them cheaply once received.
     * Trade-off: this list can't see CoT from a publisher on a prefix not
     * already cataloged here -- switch to {@code **} for a one-off full
     * audit of the mesh, same as the investigation did.
     */
    public static final String DEFAULT_SUBSCRIBE_TOPICS =
            "**/ITA-EFDI/**\n**/tak/cot/v1/**\n**/PATCH/tracks/v1/**\n**/tactiql/**";

    private final AtakPreferences prefs;

    public ZenohSettings(Context pluginContext) {
        this.prefs = AtakPreferences.getInstance(pluginContext);
    }

    public boolean isEnabled() {
        return prefs.get(PREF_ENABLED, false);
    }

    public String getRouterEndpoint() {
        return prefs.get(PREF_ROUTER_ENDPOINT, "");
    }

    public boolean isTlsEnabled() {
        return prefs.get(PREF_TLS_ENABLED, false);
    }

    public String getCaCertPath() {
        return prefs.get(PREF_CA_CERT_PATH, "");
    }

    public String getClientCertPath() {
        return prefs.get(PREF_CLIENT_CERT_PATH, "");
    }

    public String getClientKeyPath() {
        return prefs.get(PREF_CLIENT_KEY_PATH, "");
    }

    /**
     * The stable key-expression prefix outbound CoT publishes under -- each
     * event's own uid is appended as the last segment at publish time (see
     * {@link CotBridgeService#buildPublishKey}), per the fabric's
     * per-entity-state (Pattern B) guidance in
     * docs/zenoh-publish-key-design.md. Not a complete, publishable key on
     * its own.
     */
    public String getPublishTopicPrefix() {
        return prefs.get(PREF_PUBLISH_TOPIC, "");
    }

    public List<String> getSubscribeTopics() {
        String raw = prefs.get(PREF_SUBSCRIBE_TOPICS, DEFAULT_SUBSCRIBE_TOPICS);
        List<String> topics = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            String topic = line.trim();
            if (!topic.isEmpty())
                topics.add(topic);
        }
        return topics;
    }

    /** True if enabled and there's enough configuration to attempt a connection. */
    public boolean isConfigured() {
        return isEnabled() && !isBlank(getRouterEndpoint());
    }

    /**
     * Builds a client-mode JSON5 config for the zenoh-kotlin bindings from
     * the current preferences. TLS/mTLS fields are only included when TLS
     * is enabled; mTLS (client cert+key) is only enabled when both a client
     * certificate and key are configured.
     */
    public String buildConfigJson5() {
        String scheme = isTlsEnabled() ? "tls" : "tcp";
        StringBuilder sb = new StringBuilder();
        sb.append("{ mode: \"client\", connect: { endpoints: [")
                .append(jsonString(scheme + "/" + getRouterEndpoint()))
                .append("] }");

        if (isTlsEnabled()) {
            List<String> tlsFields = new ArrayList<>();
            if (!isBlank(getCaCertPath()))
                tlsFields.add("root_ca_certificate: " + jsonString(getCaCertPath()));

            boolean mtls = !isBlank(getClientCertPath()) && !isBlank(getClientKeyPath());
            if (mtls) {
                tlsFields.add("enable_mtls: true");
                tlsFields.add("connect_private_key: " + jsonString(getClientKeyPath()));
                tlsFields.add("connect_certificate: " + jsonString(getClientCertPath()));
            }

            sb.append(", transport: { link: { tls: { ")
                    .append(String.join(", ", tlsFields))
                    .append(" } } }");
        }

        sb.append(" }");
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
