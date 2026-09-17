
package com.atakmap.android.zenoh.plugin;

import atakmap.commoncommo.protobuf.v1.ContactOuterClass.Contact;
import atakmap.commoncommo.protobuf.v1.Cotevent.CotEvent;
import atakmap.commoncommo.protobuf.v1.DetailOuterClass.Detail;
import atakmap.commoncommo.protobuf.v1.GroupOuterClass.Group;
import atakmap.commoncommo.protobuf.v1.Precisionlocation.PrecisionLocation;
import atakmap.commoncommo.protobuf.v1.Takmessage.TakMessage;
import atakmap.commoncommo.protobuf.v1.TakvOuterClass.Takv;
import atakmap.commoncommo.protobuf.v1.TrackOuterClass.Track;

import com.google.protobuf.InvalidProtocolBufferException;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Decodes "TAK Protocol" (protobuf CoT) mesh messages into the CoT XML
 * string {@code com.atakmap.coremap.cot.event.CotEvent.parse(String)}
 * expects, so both wire formats share one ingest path downstream.
 *
 * Wire format is the "mesh" framing from the SDK's {@code docs/takproto.zip}
 * ({@code takproto/README.txt}): a single {@code 0xbf} magic byte, the
 * protocol version as a protobuf varint, another {@code 0xbf}, then one
 * serialized {@code atakmap.commoncommo.protobuf.v1.TakMessage}. Only
 * version 1 (the only version defined) is understood.
 *
 * {@code <detail>} reconstruction follows detail.proto's documented
 * receiver rules: each strongly-typed sub-message becomes its XML element,
 * and {@code xmlDetail} (already-serialized leftover XML, sans the
 * enclosing {@code <detail>} tags) is appended verbatim alongside them.
 */
final class TakProtoCotConverter {

    private static final int MAGIC = 0xbf;

    private TakProtoCotConverter() {
    }

    /** True if {@code payload} opens with the TAK Protocol mesh framing's magic byte. */
    static boolean looksLikeTakProto(byte[] payload) {
        return payload.length > 0 && (payload[0] & 0xFF) == MAGIC;
    }

    /**
     * Strips the mesh framing and decodes the TakMessage payload into CoT
     * XML. Returns null if the framing is malformed, the protobuf doesn't
     * parse, or the message carries no CotEvent (e.g. a bare TakControl).
     */
    static String decode(byte[] payload) {
        int offset = 0;
        if (offset >= payload.length || (payload[offset++] & 0xFF) != MAGIC)
            return null;

        long[] version = new long[1];
        offset = readVarint(payload, offset, version);
        if (offset < 0 || version[0] != 1)
            return null;

        if (offset >= payload.length || (payload[offset++] & 0xFF) != MAGIC)
            return null;

        TakMessage message;
        try {
            message = TakMessage.parseFrom(Arrays.copyOfRange(payload, offset, payload.length));
        } catch (InvalidProtocolBufferException e) {
            return null;
        }

        if (!message.hasCotEvent())
            return null;

        return toXml(message.getCotEvent());
    }

    private static int readVarint(byte[] data, int offset, long[] outValue) {
        long result = 0;
        int shift = 0;
        while (true) {
            if (offset >= data.length || shift >= 64)
                return -1;
            byte b = data[offset++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0)
                break;
            shift += 7;
        }
        outValue[0] = result;
        return offset;
    }

    private static String toXml(CotEvent event) {
        StringBuilder xml = new StringBuilder();
        xml.append("<event version=\"2.0\"");
        attr(xml, "uid", event.getUid());
        attr(xml, "type", event.getType());
        attr(xml, "how", event.getHow());
        attrIfPresent(xml, "access", event.getAccess());
        attrIfPresent(xml, "qos", event.getQos());
        attrIfPresent(xml, "opex", event.getOpex());
        attrIfPresent(xml, "caveat", event.getCaveat());
        attrIfPresent(xml, "releasableTo", event.getReleasableTo());
        attr(xml, "time", formatTime(event.getSendTime()));
        attr(xml, "start", formatTime(event.getStartTime()));
        attr(xml, "stale", formatTime(event.getStaleTime()));
        xml.append(">");

        xml.append("<point")
                .append(" lat=\"").append(event.getLat()).append('"')
                .append(" lon=\"").append(event.getLon()).append('"')
                .append(" hae=\"").append(event.getHae()).append('"')
                .append(" ce=\"").append(event.getCe()).append('"')
                .append(" le=\"").append(event.getLe()).append('"')
                .append("/>");

        if (event.hasDetail())
            appendDetail(xml, event.getDetail());

        xml.append("</event>");
        return xml.toString();
    }

    private static void appendDetail(StringBuilder xml, Detail detail) {
        boolean hasTyped = detail.hasContact() || detail.hasGroup()
                || detail.hasPrecisionLocation() || detail.hasStatus()
                || detail.hasTakv() || detail.hasTrack();
        String xmlDetail = detail.getXmlDetail();
        if (!hasTyped && xmlDetail.isEmpty())
            return;

        xml.append("<detail>");

        if (detail.hasContact()) {
            Contact c = detail.getContact();
            xml.append("<contact");
            attrIfPresent(xml, "callsign", c.getCallsign());
            attrIfPresent(xml, "endpoint", c.getEndpoint());
            xml.append("/>");
        }
        if (detail.hasGroup()) {
            Group g = detail.getGroup();
            xml.append("<__group");
            attr(xml, "name", g.getName());
            attr(xml, "role", g.getRole());
            xml.append("/>");
        }
        if (detail.hasPrecisionLocation()) {
            PrecisionLocation pl = detail.getPrecisionLocation();
            xml.append("<precisionlocation");
            attr(xml, "geopointsrc", pl.getGeopointsrc());
            attr(xml, "altsrc", pl.getAltsrc());
            xml.append("/>");
        }
        if (detail.hasStatus())
            xml.append("<status battery=\"").append(detail.getStatus().getBattery()).append("\"/>");
        if (detail.hasTakv()) {
            Takv takv = detail.getTakv();
            xml.append("<takv");
            attr(xml, "device", takv.getDevice());
            attr(xml, "platform", takv.getPlatform());
            attr(xml, "os", takv.getOs());
            attr(xml, "version", takv.getVersion());
            xml.append("/>");
        }
        if (detail.hasTrack()) {
            Track track = detail.getTrack();
            xml.append("<track speed=\"").append(track.getSpeed())
                    .append("\" course=\"").append(track.getCourse()).append("\"/>");
        }

        // Already-serialized leftover XML per detail.proto's receiver rules
        // -- not escaped, it's markup, not a single attribute value.
        xml.append(xmlDetail);

        xml.append("</detail>");
    }

    private static void attr(StringBuilder xml, String name, String value) {
        xml.append(' ').append(name).append("=\"").append(escapeXmlAttr(value)).append('"');
    }

    private static void attrIfPresent(StringBuilder xml, String name, String value) {
        if (!value.isEmpty())
            attr(xml, name, value);
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
