
package com.atakmap.android.zenoh.plugin;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Decodes the {@code PATCH/tracks/v1/<uid>} JSON mirror of TAK-protobuf CoT
 * seen on the EFDI mesh (see docs/zenoh-mesh-investigation.md and
 * docs/zenoh-additional-traffic-survey.md) into the CoT XML string
 * {@code com.atakmap.coremap.cot.event.CotEvent.parse(String)} expects, so
 * both wire wrappings of the same underlying TAK-protobuf schema share one
 * ingest path downstream.
 *
 * A node calling itself {@code tak-zenoh-bridge} republishes real TAK
 * protocol (protobuf) CoT it receives as this JSON, tagged
 * {@code "encoding":"tak-protobuf"} -- field names (uid/type/callsign/team/
 * role/platform/version/...) map directly onto the same underlying schema
 * {@link TakProtoCotConverter} decodes from the raw wire form, just
 * JSON-serialized instead of protobuf-serialized. Unlike the raw protobuf
 * form, this JSON carries no {@code stale} field of its own, so one is
 * synthesized from {@code seen} + {@link #VALIDITY_MS}.
 */
final class TakJsonCotConverter {

    /** JSON carries no expiry of its own; this is how long a sample is considered current. */
    private static final long VALIDITY_MS = 60_000L;

    private TakJsonCotConverter() {
    }

    /**
     * Cheap pre-check before the full JSON parse in {@link #decode}: avoids
     * parsing every non-matching JSON payload on a broad, mixed-mesh
     * subscription (heartbeats, roster JSON, other vendors' schemas, ...).
     * Deliberately keyed on the specific {@code "encoding":"tak-protobuf"}
     * tag rather than just "starts with {" plus generic field names, to
     * avoid ever misreading an unrelated JSON schema that happens to share
     * a "uid" or "type" key.
     */
    static boolean looksLikeTakJson(byte[] payload) {
        int i = 0;
        while (i < payload.length && isJsonWhitespace(payload[i]))
            i++;
        if (i >= payload.length || payload[i] != '{')
            return false;
        String text = new String(payload, StandardCharsets.UTF_8);
        return text.contains("\"encoding\"") && text.contains("tak-protobuf");
    }

    private static boolean isJsonWhitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\r' || b == '\n';
    }

    /**
     * Parses the JSON and builds CoT XML. Returns null if the JSON is
     * malformed or missing the fields a CoT event can't do without
     * (uid, type, a position).
     */
    static String decode(byte[] payload) {
        JSONObject obj;
        try {
            obj = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            return null;
        }

        String uid = optNullableString(obj, "uid");
        String type = optNullableString(obj, "type");
        if (uid == null || type == null || !obj.has("lat") || !obj.has("lon"))
            return null;

        double lat = obj.optDouble("lat", 0.0);
        double lon = obj.optDouble("lon", 0.0);
        if (Double.isNaN(lat) || Double.isNaN(lon))
            return null;

        long seenMillis = obj.has("seen") && !obj.isNull("seen")
                ? Math.round(obj.optDouble("seen") * 1000.0)
                : System.currentTimeMillis();
        String how = orDefault(optNullableString(obj, "how"), "m-g");

        StringBuilder xml = new StringBuilder();
        xml.append("<event version=\"2.0\"");
        attr(xml, "uid", uid);
        attr(xml, "type", type);
        attr(xml, "how", how);
        attr(xml, "time", formatTime(seenMillis));
        attr(xml, "start", formatTime(seenMillis));
        attr(xml, "stale", formatTime(seenMillis + VALIDITY_MS));
        xml.append(">");

        xml.append("<point")
                .append(" lat=\"").append(lat).append('"')
                .append(" lon=\"").append(lon).append('"')
                .append(" hae=\"").append(orUnknown(obj, "hae")).append('"')
                .append(" ce=\"").append(orUnknown(obj, "ce")).append('"')
                .append(" le=\"").append(orUnknown(obj, "le")).append('"')
                .append("/>");

        appendDetail(xml, obj);

        xml.append("</event>");
        return xml.toString();
    }

    private static void appendDetail(StringBuilder xml, JSONObject obj) {
        String callsign = optNullableString(obj, "callsign");
        String team = optNullableString(obj, "team");
        String role = optNullableString(obj, "role");
        String platform = optNullableString(obj, "platform");
        String version = optNullableString(obj, "version");
        boolean hasTrack = obj.has("course") && !obj.isNull("course")
                || obj.has("speed") && !obj.isNull("speed");
        boolean hasStatus = obj.has("battery") && !obj.isNull("battery");

        if (callsign == null && team == null && platform == null && version == null
                && !hasTrack && !hasStatus)
            return;

        xml.append("<detail>");

        if (callsign != null) {
            xml.append("<contact");
            attr(xml, "callsign", callsign);
            xml.append("/>");
        }
        if (team != null) {
            xml.append("<__group");
            attr(xml, "name", team);
            attr(xml, "role", orDefault(role, "Team Member"));
            xml.append("/>");
        }
        if (hasTrack) {
            xml.append("<track")
                    .append(" course=\"").append(obj.optDouble("course", 0.0)).append('"')
                    .append(" speed=\"").append(obj.optDouble("speed", 0.0)).append('"')
                    .append("/>");
        }
        if (hasStatus) {
            xml.append("<status battery=\"").append(obj.optInt("battery")).append("\"/>");
        }
        if (platform != null || version != null) {
            xml.append("<takv");
            if (platform != null)
                attr(xml, "platform", platform);
            if (version != null)
                attr(xml, "version", version);
            xml.append("/>");
        }

        xml.append("</detail>");
    }

    /**
     * {@code JSONObject.optString(key, null)} does NOT treat a JSON
     * {@code null} value as absent -- it stringifies {@code JSONObject.NULL}
     * to the literal text {@code "null"} instead, which would otherwise leak
     * into the CoT XML as a bogus attribute value (confirmed live: a real
     * PATCH/tracks sample with {@code "platform": null} produced
     * {@code <takv platform="null" .../>}). This treats both "missing" and
     * "explicitly JSON null" as absent.
     */
    private static String optNullableString(JSONObject obj, String key) {
        if (!obj.has(key) || obj.isNull(key))
            return null;
        return obj.optString(key, null);
    }

    /** CoT's conventional sentinel for "unknown" hae/ce/le, matching real ATAK-produced CoT. */
    private static double orUnknown(JSONObject obj, String field) {
        if (!obj.has(field) || obj.isNull(field))
            return 9999999.0;
        double v = obj.optDouble(field);
        return Double.isNaN(v) ? 9999999.0 : v;
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isEmpty()) ? fallback : value;
    }

    private static void attr(StringBuilder xml, String name, String value) {
        xml.append(' ').append(name).append("=\"").append(escapeXmlAttr(value)).append('"');
    }

    private static String escapeXmlAttr(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String formatTime(long epochMillis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(epochMillis));
    }
}
