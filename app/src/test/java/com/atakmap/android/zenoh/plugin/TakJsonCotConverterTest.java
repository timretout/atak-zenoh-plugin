package com.atakmap.android.zenoh.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Real samples captured from PATCH/tracks/v1/<uid> on the EFDI mesh
 * (2026-09-18, see docs/zenoh-mesh-investigation.md), from tak-zenoh-bridge
 * republishing real TAK-protobuf CoT as JSON.
 */
public class TakJsonCotConverterTest {

    // A real teammate's ATAK-CIV self-position, relayed via tak-zenoh-bridge.
    private static final String REAL_ANDROID_SAMPLE =
            "{\"uid\":\"ANDROID-1d6ca917b7be669e\",\"type\":\"a-f-G-U-C\",\"callsign\":\"GAM\","
                    + "\"lat\":50.680908203125,\"lon\":-2.2524940967559814,\"hae\":73.0,\"ce\":3.8,\"le\":9999999.0,"
                    + "\"how\":\"m-g\",\"team\":\"Cyan\",\"role\":\"Team Member\",\"course\":206.2,\"speed\":null,"
                    + "\"battery\":null,\"platform\":\"ATAK-CIV\",\"version\":\"5.6.0.12 (9c9a5897)[playstore].1769863102-CIV\","
                    + "\"encoding\":\"tak-protobuf\",\"seen\":1789728016.4295812,\"source\":\"tak-zenoh-bridge\"}";

    // A synthetic PATCH device from the same topic family.
    private static final String PATCH_SAMPLE =
            "{\"uid\":\"PATCH20252600182.3\",\"type\":\"a-f-G-U\",\"callsign\":\"PATCH-3\","
                    + "\"lat\":50.68067932128906,\"lon\":-2.252140998840332,\"hae\":null,\"ce\":999999.0,\"le\":999999.0,"
                    + "\"how\":\"m-g\",\"team\":\"Cyan\",\"role\":\"Team Member\",\"course\":0.4,\"speed\":1.0,"
                    + "\"battery\":null,\"platform\":null,\"version\":null,\"encoding\":\"tak-protobuf\","
                    + "\"seen\":1789722254.9426475,\"source\":\"tak-zenoh-bridge\"}";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void recognizesRealPatchTracksJson() {
        assertTrue(TakJsonCotConverter.looksLikeTakJson(bytes(REAL_ANDROID_SAMPLE)));
        assertTrue(TakJsonCotConverter.looksLikeTakJson(bytes(PATCH_SAMPLE)));
    }

    @Test
    public void doesNotRecognizeUnrelatedJsonWithOverlappingFieldNames() {
        // Real mesh sample (sensor/v1) that also has "type" but no "uid" and
        // no "encoding":"tak-protobuf" tag -- must not be misread as this format.
        String hostileUavFeed = "{\"seq\": 852, \"ts_ms\": 1789720074976, \"lat\": 60.44, \"lon\": 25.60,"
                + " \"alt\": 200, \"heading\": 247.4, \"speed_kmh\": 250.0, \"phase\": \"ingress\","
                + " \"reset\": false, \"source\": \"hostile-uav-01\", \"type\": \"hostile-uav\"}";
        assertFalse(TakJsonCotConverter.looksLikeTakJson(bytes(hostileUavFeed)));
        assertNull(TakJsonCotConverter.decode(bytes(hostileUavFeed)));
    }

    @Test
    public void decodesRealAndroidSampleToValidCotShape() {
        String xml = TakJsonCotConverter.decode(bytes(REAL_ANDROID_SAMPLE));
        assertNotNull(xml);
        assertTrue(xml.startsWith("<event version=\"2.0\""));
        assertTrue(xml.contains("uid=\"ANDROID-1d6ca917b7be669e\""));
        assertTrue(xml.contains("type=\"a-f-G-U-C\""));
        assertTrue(xml.contains("how=\"m-g\""));
        assertTrue(xml.contains("lat=\"50.680908203125\""));
        assertTrue(xml.contains("lon=\"-2.2524940967559814\""));
        assertTrue(xml.contains("hae=\"73.0\""));
        assertTrue(xml.contains("ce=\"3.8\""));
        assertTrue(xml.contains("le=\"9999999.0\""));
        assertTrue(xml.contains("<contact callsign=\"GAM\"/>"));
        assertTrue(xml.contains("<__group name=\"Cyan\" role=\"Team Member\"/>"));
        assertTrue(xml.contains("course=\"206.2\""));
        assertTrue(xml.contains("<takv platform=\"ATAK-CIV\" version=\"5.6.0.12 (9c9a5897)[playstore].1769863102-CIV\"/>"));
        assertTrue(xml.endsWith("</event>"));
    }

    @Test
    public void jsonNullOptionalFieldsAreOmittedNotStringifiedAsLiteralNull() {
        // Confirmed live on real traffic: org.json's optString(key, null)
        // stringifies a JSON null value to the text "null" instead of
        // treating it as absent -- this sample's "platform"/"version" are
        // JSON null and must not produce <takv platform="null" .../>.
        String xml = TakJsonCotConverter.decode(bytes(PATCH_SAMPLE));
        assertNotNull(xml);
        assertFalse(xml.contains("\"null\""));
        assertFalse(xml.contains("<takv"));
    }

    @Test
    public void substitutesUnknownSentinelForNullOptionalPositionFields() {
        String xml = TakJsonCotConverter.decode(bytes(PATCH_SAMPLE));
        assertNotNull(xml);
        // hae/ce/le are null or the source's own 999999.0 in this sample;
        // hae specifically is JSON null and must fall back to CoT's real
        // "unknown" sentinel (9999999.0), not 0.0 or the JSON null itself.
        assertTrue(xml.contains("hae=\"9999999.0\""));
    }

    @Test
    public void staleIsAfterTimeByTheValidityWindow() {
        String xml = TakJsonCotConverter.decode(bytes(REAL_ANDROID_SAMPLE));
        assertNotNull(xml);
        String time = extractAttr(xml, "time");
        String stale = extractAttr(xml, "stale");
        assertNotNull(time);
        assertNotNull(stale);
        assertTrue("stale (" + stale + ") must be after time (" + time + ")", stale.compareTo(time) > 0);
        assertEquals("2026-09-18T10:40:16.430Z", time);
        assertEquals("2026-09-18T10:41:16.430Z", stale);
    }

    @Test
    public void missingRequiredFieldsFailsClosed() {
        assertNull(TakJsonCotConverter.decode(bytes("{\"type\":\"a-f-G-U\",\"encoding\":\"tak-protobuf\"}")));
        assertNull(TakJsonCotConverter.decode(bytes("{\"uid\":\"x\",\"encoding\":\"tak-protobuf\"}")));
        assertNull(TakJsonCotConverter.decode(bytes("not json at all")));
    }

    private static String extractAttr(String xml, String name) {
        String marker = name + "=\"";
        int start = xml.indexOf(marker);
        if (start < 0)
            return null;
        start += marker.length();
        int end = xml.indexOf('"', start);
        return xml.substring(start, end);
    }
}
