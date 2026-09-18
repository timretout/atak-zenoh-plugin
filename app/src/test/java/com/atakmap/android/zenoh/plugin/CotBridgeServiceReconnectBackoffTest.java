package com.atakmap.android.zenoh.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The reconnect flow itself (Handler/Looper/CotServiceRemote/MapView) can't
 * be meaningfully unit-tested in a plain JVM test -- same Android-stub
 * constraint as everywhere else in this module (see
 * MeshCaptureReplayTest's doc comment). This covers the one piece of that
 * logic that's pure: the backoff ladder {@link CotBridgeService#backoffDelayMs}
 * uses to decide how long to wait before each retry, exercised live via a
 * genuinely flaky mesh (see docs/zenoh-mesh-investigation.md).
 */
public class CotBridgeServiceReconnectBackoffTest {

    @Test
    public void climbsTheLadderInOrder() {
        assertEquals(2_000L, CotBridgeService.backoffDelayMs(0));
        assertEquals(5_000L, CotBridgeService.backoffDelayMs(1));
        assertEquals(15_000L, CotBridgeService.backoffDelayMs(2));
        assertEquals(30_000L, CotBridgeService.backoffDelayMs(3));
        assertEquals(60_000L, CotBridgeService.backoffDelayMs(4));
    }

    @Test
    public void holdsAtTheLastRungForAProlongedOutage() {
        long lastRung = CotBridgeService.backoffDelayMs(4);
        assertEquals(lastRung, CotBridgeService.backoffDelayMs(5));
        assertEquals(lastRung, CotBridgeService.backoffDelayMs(100));
        assertEquals(lastRung, CotBridgeService.backoffDelayMs(Integer.MAX_VALUE));
    }

    @Test
    public void neverGoesNegativeOrThrowsForABadAttemptNumber() {
        assertTrue(CotBridgeService.backoffDelayMs(-1) > 0);
        assertTrue(CotBridgeService.backoffDelayMs(Integer.MIN_VALUE) > 0);
    }

    @Test
    public void isMonotonicallyNonDecreasing() {
        long previous = 0;
        for (int attempt = 0; attempt <= 6; attempt++) {
            long current = CotBridgeService.backoffDelayMs(attempt);
            assertTrue("backoff should never shrink as attempts climb", current >= previous);
            previous = current;
        }
    }
}
