package ro.ca2datavision.pdfua.postprocess;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Writes the XMP packet of the document. Two entries matter for PDF/UA: the title
 * ({@code dc:title}; ISO 14289-1, 7.1 and ISO 14289-2, 8.11.1) and the PDF/UA identification
 * (clause 5 of both parts), which is what makes the file claim conformance: {@code pdfuaid:part},
 * and for PDF/UA-2 also {@code pdfuaid:rev}, the year of the standard.
 */
final class XmpMetadata {

    int part = 1;
    String revision;
    String title;
    String author;
    String subject;
    String language;
    String producer;
    String creatorTool;
    OffsetDateTime created;
    OffsetDateTime modified;

    byte[] toBytes() {
        StringBuilder xmp = new StringBuilder();
        xmp.append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n");
        xmp.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n");
        xmp.append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n");
        xmp.append("  <rdf:Description rdf:about=\"\"\n");
        xmp.append("    xmlns:dc=\"http://purl.org/dc/elements/1.1/\"\n");
        xmp.append("    xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\"\n");
        xmp.append("    xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"\n");
        xmp.append("    xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\">\n");
        xmp.append("   <pdfuaid:part>").append(part).append("</pdfuaid:part>\n");
        if (revision != null) {
            xmp.append("   <pdfuaid:rev>").append(escape(revision)).append("</pdfuaid:rev>\n");
        }
        xmp.append("   <dc:format>application/pdf</dc:format>\n");
        xmp.append("   <dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(escape(title)).append("</rdf:li></rdf:Alt></dc:title>\n");
        if (author != null) {
            xmp.append("   <dc:creator><rdf:Seq><rdf:li>").append(escape(author)).append("</rdf:li></rdf:Seq></dc:creator>\n");
        }
        if (subject != null) {
            xmp.append("   <dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(escape(subject)).append("</rdf:li></rdf:Alt></dc:description>\n");
        }
        if (language != null) {
            xmp.append("   <dc:language><rdf:Bag><rdf:li>").append(escape(language)).append("</rdf:li></rdf:Bag></dc:language>\n");
        }
        if (producer != null) {
            xmp.append("   <pdf:Producer>").append(escape(producer)).append("</pdf:Producer>\n");
        }
        if (creatorTool != null) {
            xmp.append("   <xmp:CreatorTool>").append(escape(creatorTool)).append("</xmp:CreatorTool>\n");
        }
        if (created != null) {
            xmp.append("   <xmp:CreateDate>").append(format(created)).append("</xmp:CreateDate>\n");
        }
        if (modified != null) {
            xmp.append("   <xmp:ModifyDate>").append(format(modified)).append("</xmp:ModifyDate>\n");
        }
        xmp.append("  </rdf:Description>\n");
        xmp.append(" </rdf:RDF>\n");
        xmp.append("</x:xmpmeta>\n");
        xmp.append("<?xpacket end=\"w\"?>");
        return xmp.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String format(OffsetDateTime time) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time.truncatedTo(ChronoUnit.SECONDS));
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
