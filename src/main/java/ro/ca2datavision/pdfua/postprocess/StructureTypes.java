package ro.ca2datavision.pdfua.postprocess;

import java.util.Map;
import java.util.Set;

import ro.ca2datavision.pdfua.layout.Roles;

/**
 * How the roles of the layout are written as structure types in a PDF/UA-2 document.
 *
 * <p>Layouts speak the structure types of PDF 1.7 (ISO 32000-1), plus {@code Title}. A PDF/UA-1
 * document uses them as they are. PDF/UA-2 documents are PDF 2.0 documents, where every
 * structure element belongs to a namespace (ISO 32000-2:2020, 14.8.6) and PDF/UA-2 adds rules
 * of its own:</p>
 * <ul>
 *   <li>the types that PDF 2.0 also defines are written in the PDF 2.0 namespace. That includes
 *       {@code Title}, which PDF 1.7 lacks and which therefore needs no role mapping any more,
 *       and {@code Document}, which ISO 14289-2, 8.2.5.2 requires in that namespace;</li>
 *   <li>{@code Note} becomes {@code FENote}, its PDF 2.0 successor: ISO 14289-2, 8.2.5.14 does
 *       not allow {@code Note};</li>
 *   <li>the other PDF 1.7 types that PDF 2.0 dropped ({@code BlockQuote}, {@code Art},
 *       {@code Quote}, {@code Code}, ...) stay in the PDF 1.7 namespace. ISO 14289-2, 8.2.4
 *       allows that namespace, and the type keeps its meaning instead of being reduced to a
 *       generic {@code Div} or {@code Span};</li>
 *   <li>{@code H} is refused (ISO 14289-2, 8.2.5.12), and so is {@code TOCI}, which would need a
 *       {@code Ref} to its target (8.2.5.8) that the layout cannot express.</li>
 * </ul>
 *
 * <p>Software that predates PDF 2.0 ignores namespaces and does not know {@code Title} or
 * {@code FENote}; poppler 26.02, for one, drops such elements together with their content. The
 * role map of the structure tree root therefore maps them to their PDF 1.7 equivalents
 * ({@link #PDF_1_7_FALLBACK}). PDF 2.0 processors, veraPDF among them, apply that role map only to
 * elements outside an explicit namespace, so for them nothing changes. LaTeX's tagpdf and
 * Typst's pdf-writer write the same kind of fallback into PDF 2.0 documents.</p>
 */
final class StructureTypes {

    /** The namespace of the standard structure types of PDF 1.7. */
    static final String PDF_1_7_NAMESPACE = "http://iso.org/pdf/ssn";
    /** The namespace of the standard structure types of PDF 2.0. */
    static final String PDF_2_0_NAMESPACE = "http://iso.org/pdf2/ssn";

    static final String FENOTE = "FENote";

    /** The standard structure types of PDF 2.0 (ISO 32000-2:2020, 14.8.4); headings go beyond H6 there. */
    static final Set<String> PDF_2_0 = Set.of(
            "Document", "DocumentFragment", "Part", "Sect", "Div", "Aside", "NonStruct",
            "P", "H", "H1", "H2", "H3", "H4", "H5", "H6", "Title", FENOTE, "Sub",
            "Lbl", "Span", "Em", "Strong", "Link", "Annot", "Form",
            "Ruby", "RB", "RT", "RP", "Warichu", "WT", "WP",
            "L", "LI", "LBody",
            "Table", "TR", "TH", "TD", "THead", "TBody", "TFoot", "Caption",
            "Figure", "Formula", "Artifact");

    /**
     * PDF 2.0 types that the layout can produce and that PDF 1.7 does not define, with the PDF 1.7
     * type a reader without namespace support should read them as.
     */
    static final Map<String, String> PDF_1_7_FALLBACK = Map.of("Title", "P", FENOTE, "Note");

    /** A structure type and the namespace that defines it. */
    record Type(String name, String namespace) {
    }

    private StructureTypes() {
    }

    /** The structure type that represents a role of the layout in a PDF/UA-2 document. */
    static Type forPdfUa2(String role) {
        return switch (role) {
            case Roles.NOTE -> new Type(FENOTE, PDF_2_0_NAMESPACE);
            case "H" -> throw new IllegalStateException(
                    "PDF/UA-2 does not allow the structure type H (ISO 14289-2, 8.2.5.12); use H1 to H6 in the template");
            case "TOCI" -> throw new IllegalStateException(
                    "PDF/UA-2 requires every TOCI to point to its target with a Ref entry (ISO 14289-2, 8.2.5.8),"
                            + " which this post-processor cannot provide");
            default -> new Type(role, PDF_2_0.contains(role) ? PDF_2_0_NAMESPACE : PDF_1_7_NAMESPACE);
        };
    }
}
