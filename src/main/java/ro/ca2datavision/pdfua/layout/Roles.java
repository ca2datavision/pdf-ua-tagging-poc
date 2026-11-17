package ro.ca2datavision.pdfua.layout;

import java.util.Map;
import java.util.Set;

/** Structure types understood by the post-processor. */
public final class Roles {

    /** Pseudo role: the element is not part of the logical structure. */
    public static final String ARTIFACT = "Artifact";

    public static final String DOCUMENT = "Document";
    public static final String P = "P";
    public static final String FIGURE = "Figure";
    public static final String LINK = "Link";
    public static final String FORM = "Form";
    public static final String CAPTION = "Caption";
    public static final String NOTE = "Note";
    public static final String TABLE = "Table";
    public static final String TR = "TR";
    public static final String TH = "TH";
    public static final String TD = "TD";
    public static final String L = "L";
    public static final String LI = "LI";
    public static final String LBL = "Lbl";
    public static final String LBODY = "LBody";

    /** The standard structure types of PDF 1.7 (ISO 32000-1, 14.8.4). */
    public static final Set<String> STANDARD = Set.of(
            "Document", "Part", "Art", "Sect", "Div", "BlockQuote", "Caption", "TOC", "TOCI", "Index",
            "NonStruct", "Private",
            "P", "H", "H1", "H2", "H3", "H4", "H5", "H6",
            "L", "LI", "Lbl", "LBody",
            "Table", "TR", "TH", "TD", "THead", "TBody", "TFoot",
            "Span", "Quote", "Note", "Reference", "BibEntry", "Code", "Link", "Annot",
            "Ruby", "RB", "RT", "RP", "Warichu", "WT", "WP",
            "Figure", "Formula", "Form");

    /**
     * Non-standard types the templates may use, with the standard type they are mapped to in
     * the role map of the document. PDF 1.7 has no type for a document title (PDF 2.0 does).
     */
    public static final Map<String, String> CUSTOM = Map.of("Title", "P");

    private Roles() {
    }

    public static boolean isKnown(String role) {
        return STANDARD.contains(role) || CUSTOM.containsKey(role);
    }

    public static boolean isTableCell(String role) {
        return TH.equals(role) || TD.equals(role);
    }

    /** Heading level of H1..H6, or 0 for any other role. */
    public static int headingLevel(String role) {
        if (role != null && role.length() == 2 && role.charAt(0) == 'H' && role.charAt(1) >= '1' && role.charAt(1) <= '6') {
            return role.charAt(1) - '0';
        }
        return 0;
    }
}
