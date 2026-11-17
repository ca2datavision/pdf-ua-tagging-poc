package ro.ca2datavision.pdfua.postprocess;

import java.util.ArrayList;
import java.util.List;

/** What the post-processor did, and what it could not do properly. */
public final class PostProcessingReport {

    private final PdfUaPart part;
    int pages;
    int taggedContent;
    int artifacts;
    int headings;
    int figures;
    int tables;
    int lists;
    int links;
    int signatureFields;
    int wordSpaces;
    int structureDestinations;
    private final List<String> warnings = new ArrayList<>();

    PostProcessingReport(PdfUaPart part) {
        this.part = part;
    }

    void warn(String message) {
        warnings.add(message);
    }

    /** The part of PDF/UA the document was made for. */
    public PdfUaPart part() {
        return part;
    }

    public int pages() {
        return pages;
    }

    /** Number of marked-content sequences that belong to a structure element. */
    public int taggedContent() {
        return taggedContent;
    }

    /** Number of marked-content sequences marked as artifact. */
    public int artifacts() {
        return artifacts;
    }

    public int headings() {
        return headings;
    }

    public int figures() {
        return figures;
    }

    public int tables() {
        return tables;
    }

    public int lists() {
        return lists;
    }

    public int links() {
        return links;
    }

    public int signatureFields() {
        return signatureFields;
    }

    /** Number of line ends where the space dropped by the report engine was put back. */
    public int wordSpaces() {
        return wordSpaces;
    }

    /** Number of bookmarks and links that got a structure destination (PDF/UA-2 only). */
    public int structureDestinations() {
        return structureDestinations;
    }

    /** Problems that did not stop the processing but need attention; empty when all went well. */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder()
                .append(part).append(": ")
                .append(pages).append(" pages, ")
                .append(taggedContent).append(" tagged content sequences, ")
                .append(artifacts).append(" artifacts, ")
                .append(headings).append(" headings, ")
                .append(figures).append(" figures, ")
                .append(tables).append(" tables, ")
                .append(lists).append(" lists, ")
                .append(links).append(" links, ")
                .append(signatureFields).append(" signature fields, ")
                .append(wordSpaces).append(" word spaces restored at line ends");
        if (part == PdfUaPart.PDF_UA_2) {
            text.append(", ").append(structureDestinations).append(" structure destinations");
        }
        warnings.forEach(warning -> text.append(System.lineSeparator()).append("WARNING: ").append(warning));
        return text.toString();
    }
}
