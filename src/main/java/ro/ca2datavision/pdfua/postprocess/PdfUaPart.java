package ro.ca2datavision.pdfua.postprocess;

/**
 * The part of PDF/UA that a document is made to conform to.
 *
 * <p>Both parts are produced from the same layout and have the same content, the same reading
 * order and the same artifacts. They differ in how that is expressed in the file:</p>
 *
 * <table>
 *   <caption>Differences between the two outputs</caption>
 *   <tr><th></th><th>PDF/UA-1</th><th>PDF/UA-2</th></tr>
 *   <tr><td>standard</td><td>ISO 14289-1:2014</td><td>ISO 14289-2:2024</td></tr>
 *   <tr><td>PDF version</td><td>1.7</td><td>2.0</td></tr>
 *   <tr><td>XMP identification</td><td>{@code pdfuaid:part} 1</td><td>{@code pdfuaid:part} 2, {@code pdfuaid:rev} 2024</td></tr>
 *   <tr><td>structure types</td><td>PDF 1.7, {@code Title} role mapped to {@code P}</td>
 *       <td>with namespaces: PDF 2.0 where it has the type ({@code Title} included), {@code FENote}
 *       instead of {@code Note}, PDF 1.7 for the other types that PDF 2.0 dropped</td></tr>
 *   <tr><td>bookmarks, internal links</td><td>page destinations</td><td>page and structure destinations</td></tr>
 *   <tr><td>signature widgets</td><td>{@code TU}</td><td>{@code TU} and {@code Contents}</td></tr>
 * </table>
 *
 * <p>PDF/UA-1 is the default because it is what the PAC checker tests.</p>
 */
public enum PdfUaPart {

    /** PDF/UA-1, ISO 14289-1:2014, on PDF 1.7. The default. */
    PDF_UA_1(1, null, "1.7"),

    /** PDF/UA-2, ISO 14289-2:2024, on PDF 2.0. */
    PDF_UA_2(2, "2024", "2.0");

    private final int part;
    private final String revision;
    private final String pdfVersion;

    PdfUaPart(int part, String revision, String pdfVersion) {
        this.part = part;
        this.revision = revision;
        this.pdfVersion = pdfVersion;
    }

    /** The value of {@code pdfuaid:part}. */
    public int part() {
        return part;
    }

    /** The value of {@code pdfuaid:rev}, or {@code null} for PDF/UA-1, which does not use it. */
    public String revision() {
        return revision;
    }

    /** The PDF version the part is defined on. */
    public String pdfVersion() {
        return pdfVersion;
    }

    /** The part with the given number, 1 or 2. */
    public static PdfUaPart of(int part) {
        for (PdfUaPart value : values()) {
            if (value.part == part) {
                return value;
            }
        }
        throw new IllegalArgumentException("There is no PDF/UA-" + part + "; supported are PDF/UA-1 and PDF/UA-2");
    }

    @Override
    public String toString() {
        return "PDF/UA-" + part;
    }
}
