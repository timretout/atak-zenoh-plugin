package com.atakmap.android.zenoh.plugin;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link CotBridgeService#buildPublishKey} implements Pattern B (per-entity
 * state) from the fabric's own key-shaping guidance -- see
 * docs/zenoh-publish-key-design.md and
 * /home/tim/efdi/hello-galaxy-zenoh-patterns/docs/identity-spectrum.md
 * ("track ... Pattern B keyed by the track/CoT uid"). The prior behavior
 * published every event to one fixed key with the uid left in the payload,
 * which is exactly the "scan trap" anti-pattern that guidance warns against.
 */
public class CotBridgeServicePublishKeyTest {

    @Test
    public void appendsUidAsLastSegment() {
        assertEquals("acme/hello/cot/v1/ANDROID-1234",
                CotBridgeService.buildPublishKey("acme/hello/cot/v1", "ANDROID-1234"));
    }

    @Test
    public void tolerantOfTrailingSlashOnPrefix() {
        assertEquals("acme/hello/cot/v1/ANDROID-1234",
                CotBridgeService.buildPublishKey("acme/hello/cot/v1/", "ANDROID-1234"));
        assertEquals("acme/hello/cot/v1/ANDROID-1234",
                CotBridgeService.buildPublishKey("acme/hello/cot/v1//", "ANDROID-1234"));
    }

    @Test
    public void sanitizesCharactersWithKeyExpressionMeaning() {
        // Zenoh key expressions treat *, ** and $ specially; # and ? are
        // reserved. None of these may leak into a segment built from an
        // arbitrary CoT uid.
        assertEquals("prefix/a_b_c_d_e",
                CotBridgeService.buildPublishKey("prefix", "a*b#c?d$e"));
    }

    @Test
    public void sanitizesSpacesFromNonTrackUids() {
        // e.g. GeoChat CoT uids can contain spaces and dots outside a plain
        // track uid's usual [A-Za-z0-9-] shape.
        assertEquals("prefix/GeoChat.ANDROID-1234.All_Chat_Rooms.abcd-ef01",
                CotBridgeService.buildPublishKey("prefix", "GeoChat.ANDROID-1234.All Chat Rooms.abcd-ef01"));
    }

    @Test
    public void preservesDotsHyphensAndUnderscores() {
        assertEquals("prefix/a.b-c_d",
                CotBridgeService.buildPublishKey("prefix", "a.b-c_d"));
    }

    @Test
    public void fallsBackToPlaceholderForMissingUid() {
        assertEquals("prefix/_", CotBridgeService.buildPublishKey("prefix", ""));
        assertEquals("prefix/_", CotBridgeService.buildPublishKey("prefix", null));
    }
}
