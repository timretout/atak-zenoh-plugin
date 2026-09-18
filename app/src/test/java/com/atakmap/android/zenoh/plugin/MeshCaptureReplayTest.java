package com.atakmap.android.zenoh.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Replays real payloads captured from a 5-minute live EFDI mesh subscription
 * (2026-09-18, see docs/zenoh-mesh-investigation.md) through
 * {@link CotBridgeService#decode} and {@link TakProtoCotConverter}, to
 * confirm real-world CoT XML is recognized and that non-CoT binary traffic
 * that coincidentally starts with a recognized format's magic byte fails
 * closed rather than misbehaving.
 *
 * This only exercises format sniffing/framing, not full CoT semantic
 * validity: {@code com.atakmap.coremap.cot.event.CotEvent.parse()} depends
 * on Android's real XML parser, which isn't available under the plain-JVM
 * unit test runner (it returns an empty default-valued event instead of
 * throwing, even with {@code unitTests.returnDefaultValues = true}). Full
 * parse+dispatch verification was instead done by capturing this exact live
 * traffic on the connected test device, where a real Android runtime is
 * available -- see the investigation doc for that result.
 */
public class MeshCaptureReplayTest {

    // Bare CoT XML (no XML declaration) as published by ITA-EFDI/RADAR-01/v1.
    private static final String RADAR_XML =
            "<event version=\"2.0\" uid=\"ITA-EFDI-RADAR-01\" type=\"a-f-G-U-C-F-T-R\" how=\"m-g\" time=\"2026-09-18T08:27:58.99Z\" start=\"2026-09-18T08:27:58.99Z\" stale=\"2026-09-18T08:28:08.99Z\" access=\"Undefined\"><point lat=\"50.600000\" lon=\"-2.420000\" hae=\"30.0\" ce=\"10\" le=\"10\" /><detail><contact callsign=\"ITA-EFDI-RADAR-01\" /><__radar range=\"80000\" rpm=\"15.0\" az=\"246.3\" /><remarks>Search radar - Dorset - R 80km</remarks></detail></event>";

    // Full CoT XML (with declaration) as published by tak/cot/v1/EDGEAI.edge-ai-01.hostile-uav-00.
    private static final String EDGEAI_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><event version=\"2.0\" uid=\"EDGEAI.edge-ai-01.hostile-uav-00\" type=\"a-h-A-M-F-Q\" how=\"m-c\" time=\"2026-09-18T08:28:09.523Z\" start=\"2026-09-18T08:28:09.523Z\" stale=\"2026-09-18T08:28:39.523Z\"><point lat=\"60.3158074\" lon=\"25.2952052\" hae=\"150.0\" ce=\"25.0\" le=\"9999999.0\"/><detail><contact callsign=\"EDGEAI.edge-ai-01.hostile-uav-00\"/><status readiness=\"true\"/><precisionlocation altsrc=\"CALC\" geopointsrc=\"CALC\"/><_flow-tags_ efdi-edge-ai=\"edge-ai-01/cue-classifier/1 2026-09-18T08:28:09.523Z\"/><track course=\"247.8\" speed=\"69.4\"/><link uid=\"EFDI.hostile-uav-00\" production_time=\"2026-09-18T08:28:09.523Z\" relation=\"p-p\" type=\"a-f-A-M-F-Q\"/><remarks>EDGE-AI uav p=0.73 | node=edge-ai-01 model=cue-classifier/1 | CE~25m | cue=hostile-uav-00</remarks></detail></event>";

    // 1400B chunk of a raw WAV/telemetry stream on catalyst/v1/talsys/radio/0001
    // that happened to start with 0x3C ('<') -- a coincidental false-positive
    // for the XML sniff, NOT real CoT. ~1-in-256 odds per sample; this exact
    // one was seen live.
    private static final String RADIO_LT_B64 =
            "PPwh/T3/g/kD9gb4PfqQ/UwARQMaBLwEoQYqB94FVwcuCUUHxwNmAgsDIwLDAmEHYwtcDMEKmAaHBSQFawMd/6f8WvzV+Fv4j/vFAEQEvgOzAnEBUQCA/i785fxM/TP9o/18/B39x/0f+uT3cfYR+KP7EAAXA/j/ffq5+D31A/gE/YL/twRqBHABXwPBA5kAhf+b/+D9u/wA+X753vra/YIDLAXZA0gDAgWAAu8BxP0Y+av2P/iq/7wHDwzaDkYPow+rDTsLHwNn/2oAHv9I/ef+z/9c/kn8Tf38/bz8ZfmF/ML8Gv7h/Gz8mPmc9kj26vwvAvcISAjBBTsGZAdlBEcCogQ6BFYGbwvvC88IsARlAmX/9f9//7YBngDn/nf/UP7P/U/48vfj+Tf/bAXuBh0Dm/6T/Sn7MPu8+qv45vYl+3D9Av6lAM8EIQZ4BY0ExALvAX8ATQBj+Un1OPUZ95D8VQF4BOMCkQNJAeMBXf2W90T79P8tAGL+j/36/T//cQABAoYDegPMBtsJgA26DoMJagU8BGQBPQILA+kGHApuBNr+dP/t/9z55PgL9n717fno/Mf/8v0F/j38F/3U/Mj7Q/kX9qv3nv1Q/bX3+vMT9NH5pgITB6YJ4AbxBVAIQAYUBtUDU/+g/KH7sfsB/aD+mQOdBfYErAEoAukCLP5e+o/3E/lu+Fj7rv1y/9EAhgN8AhECtQJg/Yr8aQA9BS4FwgSjB+EGkQSLArEADvyn9bH2pPxX/+3/Tf7r/DT2FfTv9kb5SwCcBsIGzAREA3AAafvm+Jf3uPjR+Zj+QAHJBiIHMglyDOUKfw6mDBAK4An4CPwF4P50+en6KP8PAmECV/6K+0b8gP+//cj6OfkZ+Dn30Phg+tn6/PrS/Fb/tfvn+D/5H/sX/f37wv4e/dn6Lv4XBPoJ0AxhDOcI1gd2Azr/h/7dAZ8EwwIDAxIC5APFAdMBEgGE/LX8jv8tA7sEfQJkAVr/9vq3+Ab7/gEQB5oJ5Qi8Bu0HfgYRBO0AdvyN+dX6kvoOAKIAWQBmBMQAh/0N/OD5Jflq+cv82Psl+CT2sffA/JYAGv5l/u/8evwg/Xv9nwGsA4kC0gJNBJgHSgj2CLULNghWBEoAJAEsBDoBigKKAUr9Lfpm/e8C2gKwAtMBRQF/Akf+dftQ/GL8MfvS+1X7h/0E/VEALQP4AfT9z/rX/nQC7wNPBygEVP9L+Sf1DfstAc4EGwgPB68E5gEL/4D6DPnJ+PP5kfkc/sb/VgDOAfgBSwOEBAkDCALJAV0FrgYpBkACaQCmBAUJqQqKCfYFgQA2/N76f/tk/v/+uP3O/FL8yPvL+/j9lQAe/j/4G/aj9tf0x/R49RX37PWH81P4N/66At8GoApqDasLZAe/BYEHOghICV4GrwF+/nj8ZAAJBJ4DZABIAF8D/wPtBOUDXQBO/oL7Iv0PASsExwbHBLICcf9r/CP4t/c7+07+cP12/j4AGwEZBPwHtAmqB48C7v1Z+S/5tvxC/yH+tviB9+v66f/tAMv+tP13/bv9J/wv/db9gvxi/H39PP+E/CH8CgKABHkC1gHrAzUEHQMnBgsIDggxApwApAG/ARsC6gTqBnAFpgJj/3b/9QBIABH/N/1G/FL7w/yN/if+h/s0+HT48/hY95b6JvzG/CT8sPx9/ooB/gWcCQoLcgciBdcAR/zk+kL8gP+t/X3+aP0k+2z+MwL9AbgAeQEeAtICJwdQCRQJsAM9/Y39UP3f/kcB+AGeAY8BaQAHAPQAAAGEAqQAiwIpA2MAcP+AAAwAu/tA+tb4hvQc9mT40vk2+UD2N/de+CT7WQAqA5oCYAJ5AcL+ZP3b/hABJAGuAk0EXQI=";

    // 1400B chunk of the same stream that started with 0xBF -- a coincidental
    // false-positive for the TAK-protocol magic byte sniff, NOT real TAK
    // protobuf CoT.
    private static final String RADIO_BF_B64 =
            "v+aH7NbvefWgAJcJ+gm18rrmE+qvApMo1jbFIYb20eBo5n36XAVPDa8HBPie6qzc0twW6v4I4Cu3L8IR5e1/3hXnR/Mk/Mr9cfwTAOL83+qP4jj7pR1qNNsfUQIA7BTsBwWsA6gBgPay/aj/avEc6lfuvgyJLSUnqwTj5lvaPu3XBQkM2wWL9v32ZPMu4ATqpgWNIqgnxwfE6K7VkeAHDekZSRF2Auj6W/9V8/bvaATTF+wonhz2Adrjy+Nw+xcbMSETBYjyF/ip9qrqbOMx/P0gHx9b/+Xi29tj8+QRRR0eHBIIQANIAFT2WvZEAjEh/ioEFQ3z2d0g5LH5SxOvFiEFVe9A56XYGtgT2wH1CyAxMmcTseFvyR/W2/3iGHYa+A1UC1/xX9oS4Dv7LyMQO6Y2kg3+5cfgZvi8DYYQwfc99Rn5mNzU2P7pLRG7MVUxcAuz6/Hc0O6MD30bjxnsADb/oPc27X/ySwzQKwE61SqMECL8Tu8R8V37E///9okElgcd8sviD/LMDK4edxhi/MHseNdc2JbcfeMa8yQGPRMSBpLyXebMAKodVTT3KaYF3vSz8UX4jwJcEBwTLhguByry+OL45lAFLygXJGv+q9sUycnOC+Pc6B3ztQE7B036g+EN3eX37RYoOK0riQ4t+Zf9MAurFf0Zux2YJG4XVATO8TnnTPZ3GdwroBTr/QnpouWp5dHfQ/7WBG8MGwSv5TfTTdXg8tsP0RuUFJMEZv6O/rL7XfdPBigfISDHBYrkEOCK9XEZiCxpJDwNA/Oo9f7lFODp7LT+KxToA3/misfyvkfbt/7uEIQIiAJk+pjsFuVq5Pz1Cx4vLuodRQLE6PzqrwiBHAIdUhCBDRMSjf8s517UnvclJkgyJiNmA4f1e/38D/EZzBPoBoX+b+5l4zfPCMxm6bAQ5CNGC4vvj/DBBO4T/CErGakXAhPH/GPjTNGT6o0XqS/1I80GOPQE+B78MAYeCm36wf7W/p3szda2zGvkwAaTDHwAJOSD3072bgFlDv8P4A0lFJ8Te/th7lP3lBYwL20rlBdsAWYHfwLH+Pb6SvX0BIIP7vq56YTX3d3y8r4AdPte6p3gqefx+CH0nvNJ/JEVfSGdGbQN9f6ODt4VzRonEWP9jP3DEO4Yag+cBPX7rQbbAOX2mO6h+a4Onwdu+h/mIdzN8IQH/AoVB2z05fHX/Lr3Tutv7gH5GApuEtcKVv4UATYXdSBPIu0KlPYZ/5sFOQMEAjL5Q/Vp+ULu6ulC7en29wvKFqsPP/v27HTfHuAM3RTf0+vT9Vn+QPIn5wvsvf8zIOYz6i2nIB4Lcv5QAncAzg0IH4wuADPpFpH3mezS9MULeB+rHCoKZu3O4trevNfa5O7qn/sfAAvqQdun3Arrmwv7Ip0YiRDWA6/97ANLAPUKeBlhHWsZK/uK5q7oJPwBDpEWRxM6BN3/mPdZ8n3o5eht8kb5n/1E6MTV5NQi6a8Loh++GekPAgnxAncF/v9+AqQTWhm1G/z9meL/5mP2URCrIzol/hIt9+rriOqQ5i7w9Ppw/+n5QOc31sXmfgLcGSgn9B2eFKkD2PBI7LvypPnMAtEQ0BWDB9fwlOpQ+KwSEChWJvIZ1QyjCAYCP++763n1jPsnB7wE3/bG6ODpbfmFC6wWdAwq+t3mePKg+wH5uvWC+2ADtAAc91b0yf+KDMgcrhzHDQj6+vLK98f/rwUWBrIFcf/j/Sjq8N7G7Xb9KwvEDKv7feiU537sCPRH7+HtkPtVAkwCe/IY50jyXAiELr1DnD/TM4cesxWVF24UVgs/AVYIdwtN9pnfg+Je5yb+0wxHD5EImvQT7HTwI/P/+jv7c/Dv5RLdc90=";

    /** {@link CotBridgeService#decode(byte[])} is private -- invoke via reflection. */
    private static String decode(byte[] payload) throws Exception {
        Method m = CotBridgeService.class.getDeclaredMethod("decode", byte[].class);
        m.setAccessible(true);
        return (String) m.invoke(null, (Object) payload);
    }

    @Test
    public void recognizesRealBareCotXmlFromMesh() throws Exception {
        String xml = decode(RADAR_XML.getBytes(StandardCharsets.UTF_8));
        assertEquals("bare ITA-EFDI CoT XML should be passed through unchanged", RADAR_XML, xml);
    }

    @Test
    public void recognizesRealDeclaredCotXmlFromMesh() throws Exception {
        String xml = decode(EDGEAI_XML.getBytes(StandardCharsets.UTF_8));
        assertEquals("declared EDGEAI CoT XML should be passed through unchanged", EDGEAI_XML, xml);
    }

    @Test
    public void doesNotChokeOnRawBinaryThatHappensToStartWithLessThan() throws Exception {
        byte[] payload = Base64.getDecoder().decode(RADIO_LT_B64);
        assertEquals((byte) '<', payload[0]);

        // decode() sniffs on the leading byte alone, so this real
        // false-positive from a raw WAV/telemetry stream on
        // catalyst/v1/talsys/radio does get passed through as "recognized"
        // text -- same as production behavior. The safety net that keeps
        // this from becoming a bogus map marker is
        // com.atakmap.coremap.cot.event.CotEvent.parse()/isValid() further
        // downstream in CotBridgeService, verified separately on-device.
        String xml = decode(payload);
        assertNotNull(xml);
    }

    @Test
    public void doesNotMisreadRawBinaryThatHappensToStartWith0xBF() throws Exception {
        byte[] payload = Base64.getDecoder().decode(RADIO_BF_B64);
        assertEquals((byte) 0xBF, payload[0]);
        assertTrue(TakProtoCotConverter.looksLikeTakProto(payload));

        // Real recorded false-positive: 0xBF is TakProtoCotConverter's magic
        // byte, so this raw WAV/telemetry chunk from catalyst/v1/talsys/radio
        // gets fed into the protobuf decoder. It must fail closed (null),
        // not throw and not fabricate a bogus CotEvent -- unlike the '<'
        // case above, nothing downstream would catch a bad protobuf parse
        // that happened to succeed.
        String xml = TakProtoCotConverter.decode(payload);
        assertNull("non-TAK-protocol binary that starts with 0xBF must decode to null", xml);

        // decode() as a whole must therefore also report "unrecognized".
        assertNull(decode(payload));
    }
}
