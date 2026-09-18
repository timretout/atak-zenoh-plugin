package com.atakmap.android.zenoh.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps docs/asyncapi.yaml's outbound channel address in step with
 * {@link CotBridgeService#buildPublishKey}. Deliberately only a regex over
 * the one {@code address:} line -- the unit-test classpath has no YAML
 * parser, and full structural validity is checked with
 * {@code npx @asyncapi/cli validate docs/asyncapi.yaml}.
 */
public class AsyncApiSpecTest {

    @Test
    public void channelAddressMatchesBuildPublishKey() throws IOException {
        Matcher m = Pattern.compile("(?m)^\\s+address:\\s*'([^']+)'\\s*$")
                .matcher(readSpec());
        assertEquals("expected exactly one channel address in the spec", true, m.find());
        String address = m.group(1);
        assertEquals("spec should declare exactly one channel", false, m.find());

        String rendered = address.replace("{prefix}", "tak/cot/v1").replace("{uid}", "UID-1");
        assertEquals(rendered, CotBridgeService.buildPublishKey("tak/cot/v1", "UID-1"));
    }

    private static String readSpec() throws IOException {
        // Gradle runs unit tests with the module dir (app/) as working dir;
        // an IDE may use the repo root.
        for (String path : new String[] {"../docs/asyncapi.yaml", "docs/asyncapi.yaml"}) {
            File f = new File(path);
            if (f.isFile())
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        assertNotNull("docs/asyncapi.yaml not found from " + new File(".").getAbsolutePath(), null);
        return null;
    }
}
