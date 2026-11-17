package ro.ca2datavision.pdfua.layout;

/**
 * Keys of {@link LayoutElement#attributes()}: properties of a structure element beyond its type.
 * The names are the PDF names of the entries or attributes they become.
 */
public final class Attributes {

    /** Table header cell (TH): which cells it is a header for: {@code Column}, {@code Row} or {@code Both}. */
    public static final String SCOPE = "Scope";
    /** Table cell: number of columns the cell spans (omitted for 1). */
    public static final String COL_SPAN = "ColSpan";
    /** Table cell: number of rows the cell spans (omitted for 1). */
    public static final String ROW_SPAN = "RowSpan";
    /** Table: a summary of its purpose and structure, for non-visual readers. */
    public static final String SUMMARY = "Summary";
    /**
     * List (L): how the items are labelled: {@code Disc}, {@code Circle}, {@code Square},
     * {@code Decimal}, {@code UpperRoman}, {@code LowerRoman}, {@code UpperAlpha},
     * {@code LowerAlpha} or {@code None}.
     */
    public static final String LIST_NUMBERING = "ListNumbering";
    /** Any element: natural language of its content when it differs from the document language. */
    public static final String LANG = "Lang";
    /** Any element: text that replaces the content for assistive technology. */
    public static final String ACTUAL_TEXT = "ActualText";

    private Attributes() {
    }
}
