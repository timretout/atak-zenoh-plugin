package com.atakmap.android.zenoh.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Ownship's outbound {@code type} and {@code stale}. ATAK's generic
 * converter gives the self marker {@code type='self'} (a marker type, not a
 * CoT type code) and a one-year stale; {@code publishSelfPosition()} repairs
 * both, deciding via these pure functions because {@code CotEvent} itself
 * can't be built under the plain JVM unit-test runner (see
 * {@link MeshCaptureReplayTest}).
 */
public class CotBridgeServiceOwnshipTest {

    @Test
    public void selfBecomesTheConfiguredUnitType() {
        assertEquals("a-f-G-U-C-I", CotBridgeService.outboundOwnshipType("self", "a-f-G-U-C-I"));
    }

    @Test
    public void selfWithDefaultUnitTypeIsFriendlyGroundUnit() {
        assertEquals("a-f-G-U-C",
                CotBridgeService.outboundOwnshipType("self", CotBridgeService.DEFAULT_UNIT_TYPE));
    }

    @Test
    public void defaultUnitTypeIsATAKsOwnDefault() {
        // From ATAK 5.8.0.5's default_cot_type string resource.
        assertEquals("a-f-G-U-C", CotBridgeService.DEFAULT_UNIT_TYPE);
    }

    @Test
    public void selfFallsBackToDefaultWhenUnitTypeUnusable() {
        assertEquals("a-f-G-U-C", CotBridgeService.outboundOwnshipType("self", null));
        assertEquals("a-f-G-U-C", CotBridgeService.outboundOwnshipType("self", ""));
        assertEquals("a-f-G-U-C", CotBridgeService.outboundOwnshipType("self", "   "));
        assertEquals("a-f-G-U-C", CotBridgeService.outboundOwnshipType("self", "a-"));
        assertEquals("a-f-G-U-C", CotBridgeService.outboundOwnshipType("self", "self"));
        assertEquals("a-f-G-U-C", CotBridgeService.outboundOwnshipType("self", "b-m-p-s-p-i"));
    }

    @Test
    public void unitTypeIsTrimmed() {
        assertEquals("a-f-A-M-F", CotBridgeService.outboundOwnshipType("self", "  a-f-A-M-F\n"));
    }

    @Test
    public void nonSelfTypesAreLeftAlone() {
        assertEquals("a-f-G-I", CotBridgeService.outboundOwnshipType("a-f-G-I", "a-f-G-U-C"));
        assertEquals("a-f-G-U-C-I", CotBridgeService.outboundOwnshipType("a-f-G-U-C-I", null));
        assertNull(CotBridgeService.outboundOwnshipType(null, "a-f-G-U-C"));
    }

    @Test
    public void staleIsFiveBeatsAfterTime() {
        assertEquals(1_000_000L + 75_000L, CotBridgeService.ownshipStaleMillis(1_000_000L));
        assertEquals(75_000L, CotBridgeService.SELF_STALE_MS);
    }

    @Test
    public void staleOutlastsTheGapsSeenWhenThePhoneSleeps() {
        // Live capture: 30-40 s gaps between publishes while the bridge reconnects.
        assertTrue(CotBridgeService.SELF_STALE_MS > 40_000L);
        // ...but a consumer still learns ownship stopped within a couple of minutes.
        assertTrue(CotBridgeService.SELF_STALE_MS <= 120_000L);
    }
}
