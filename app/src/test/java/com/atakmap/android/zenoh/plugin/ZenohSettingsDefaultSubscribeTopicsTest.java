package com.atakmap.android.zenoh.plugin;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link ZenohSettings#getSubscribeTopics()} itself needs an Android
 * Context/AtakPreferences it can't get in a plain JVM test, but its parsing
 * (split on newline, trim, drop blanks) is plain string logic -- this
 * exercises {@link ZenohSettings#DEFAULT_SUBSCRIBE_TOPICS} through that same
 * logic, duplicated here, to lock in that the default actually parses into
 * the four curated prefixes discussed in
 * docs/zenoh-mesh-investigation.md/docs/zenoh-additional-traffic-survey.md
 * -- not the old broad {@code **} (a real, live problem: an unrelated
 * video stream on the mesh made a bare {@code **} subscription expensive to
 * receive and discard).
 */
public class ZenohSettingsDefaultSubscribeTopicsTest {

    /** Mirrors ZenohSettings.getSubscribeTopics()'s parsing exactly. */
    private static List<String> parse(String raw) {
        List<String> topics = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            String topic = line.trim();
            if (!topic.isEmpty())
                topics.add(topic);
        }
        return topics;
    }

    @Test
    public void parsesIntoTheFourCuratedCotPrefixes() {
        assertEquals(Arrays.asList(
                "**/ITA-EFDI/**",
                "**/tak/cot/v1/**",
                "**/PATCH/tracks/v1/**",
                "**/tactiql/**"
        ), parse(ZenohSettings.DEFAULT_SUBSCRIBE_TOPICS));
    }

    @Test
    public void everyDefaultPrefixHasALeadingWildcardForTheAnchorSegment() {
        // The anchor (first key segment) is a different, unpredictable slot
        // id per publisher -- a prefix without a leading "**" would only
        // ever match this plugin's own anchor.
        for (String topic : parse(ZenohSettings.DEFAULT_SUBSCRIBE_TOPICS)) {
            assertEquals("**", topic.split("/")[0]);
        }
    }
}
