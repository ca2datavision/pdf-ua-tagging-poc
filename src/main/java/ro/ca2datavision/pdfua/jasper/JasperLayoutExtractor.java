package ro.ca2datavision.pdfua.jasper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JRLineBox;
import net.sf.jasperreports.engine.JROrigin;
import net.sf.jasperreports.engine.JRPrintAnchor;
import net.sf.jasperreports.engine.JRPrintElement;
import net.sf.jasperreports.engine.JRPrintFrame;
import net.sf.jasperreports.engine.JRPrintImage;
import net.sf.jasperreports.engine.JRPrintPage;
import net.sf.jasperreports.engine.JRPrintText;
import net.sf.jasperreports.engine.JRPropertiesMap;
import net.sf.jasperreports.engine.JRStyledTextAttributeSelector;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReportsContext;
import net.sf.jasperreports.engine.PrintPageFormat;
import net.sf.jasperreports.engine.type.BandTypeEnum;
import net.sf.jasperreports.engine.util.JRStyledText;
import net.sf.jasperreports.engine.util.JRStyledTextUtil;

import ro.ca2datavision.pdfua.jasper.LayoutAssembler.Node;
import ro.ca2datavision.pdfua.layout.ArtifactInfo;
import ro.ca2datavision.pdfua.layout.Attributes;
import ro.ca2datavision.pdfua.layout.Box;
import ro.ca2datavision.pdfua.layout.DocumentLayout;
import ro.ca2datavision.pdfua.layout.ElementKind;
import ro.ca2datavision.pdfua.layout.LayoutElement;
import ro.ca2datavision.pdfua.layout.PageLayout;
import ro.ca2datavision.pdfua.layout.Roles;
import ro.ca2datavision.pdfua.layout.SignatureInfo;

/**
 * Derives the {@link DocumentLayout} from a filled report.
 *
 * <p>The filled report ({@link JasperPrint}) knows the exact position of every element and
 * carries the properties of the template, which is all the information the exported PDF has
 * lost. Two vocabularies are understood:</p>
 * <ul>
 *   <li>the {@code pdfua.*} properties of this project;</li>
 *   <li>the tagging properties of JasperReports itself
 *       ({@code net.sf.jasperreports.export.pdf.tag.*} with the values {@code start},
 *       {@code end}, {@code full}, and {@code net.sf.jasperreports.export.accessibility.tag}).
 *       Templates prepared for the JasperReports tagger keep working, and the table component
 *       writes these properties by itself when
 *       {@code net.sf.jasperreports.components.table.accessible} is set.</li>
 * </ul>
 *
 * <p>The role of an element is decided in this order:</p>
 * <ol>
 *   <li>{@code pdfua.signature.name}: the element is a signature field placeholder;</li>
 *   <li>{@code pdfua.role}: explicit structure type, or {@code Artifact};</li>
 *   <li>a JasperReports tag property with the value {@code full} (the innermost one, when an
 *       element has several), or {@code ...accessibility.tag};</li>
 *   <li>defaults: everything in the page header, page footer and background bands is an
 *       artifact; text is a paragraph; an image is a figure when it has alternate text;
 *       lines, rectangles and ellipses are artifacts.</li>
 * </ol>
 *
 * <p>Nesting (rows, tables, list items, lists) is the job of the {@link LayoutAssembler}.</p>
 */
public final class JasperLayoutExtractor {

    public static final String PROPERTY_ROLE = "pdfua.role";
    public static final String PROPERTY_ALT = "pdfua.alt";
    public static final String PROPERTY_ARTIFACT_TYPE = "pdfua.artifact.type";
    public static final String PROPERTY_ARTIFACT_SUBTYPE = "pdfua.artifact.subtype";
    public static final String PROPERTY_SIGNATURE_NAME = "pdfua.signature.name";
    public static final String PROPERTY_SIGNATURE_DESCRIPTION = "pdfua.signature.description";
    /** Header cell: {@code Column}, {@code Row} or {@code Both}. Without it the scope is derived from the row. */
    public static final String PROPERTY_SCOPE = "pdfua.scope";
    public static final String PROPERTY_COLSPAN = "pdfua.colspan";
    public static final String PROPERTY_ROWSPAN = "pdfua.rowspan";
    /** Any name; cells with different names belong to different tables even when nothing separates them. */
    public static final String PROPERTY_TABLE = "pdfua.table";
    public static final String PROPERTY_TABLE_SUMMARY = "pdfua.table.summary";
    /** Any name; list items with different names belong to different lists. */
    public static final String PROPERTY_LIST = "pdfua.list";
    public static final String PROPERTY_LIST_NUMBERING = "pdfua.list.numbering";

    // ---- properties defined by JasperReports ----
    public static final String PROPERTY_JASPER_ACCESSIBILITY_TAG = "net.sf.jasperreports.export.accessibility.tag";
    public static final String PROPERTY_JASPER_TAG_PREFIX = "net.sf.jasperreports.export.pdf.tag.";
    public static final String PROPERTY_JASPER_COLSPAN = PROPERTY_JASPER_TAG_PREFIX + "colspan";
    public static final String PROPERTY_JASPER_ROWSPAN = PROPERTY_JASPER_TAG_PREFIX + "rowspan";
    public static final String PROPERTY_JASPER_ACTUAL_TEXT = PROPERTY_JASPER_TAG_PREFIX + "attribute.ActualText";
    /** On the report: document language. On an element: language of that element. */
    public static final String PROPERTY_JASPER_LANGUAGE = PROPERTY_JASPER_TAG_PREFIX + "language";
    public static final String PROPERTY_JASPER_TITLE = "net.sf.jasperreports.export.pdf.metadata.title";
    public static final String PROPERTY_JASPER_AUTHOR = "net.sf.jasperreports.export.pdf.metadata.author";
    public static final String PROPERTY_JASPER_SUBJECT = "net.sf.jasperreports.export.pdf.metadata.subject";

    /**
     * The JasperReports tag properties ({@code ...pdf.tag.<name>}) and the structure types they
     * stand for, from the outermost to the innermost when one element carries several.
     */
    private static final String[][] JASPER_TAGS = {
            {"table", Roles.TABLE}, {"tr", Roles.TR}, {"th", Roles.TH}, {"td", Roles.TD},
            {"l", Roles.L}, {"li", Roles.LI}, {"lbl", Roles.LBL}, {"lbody", Roles.LBODY},
            {"h1", "H1"}, {"h2", "H2"}, {"h3", "H3"}, {"h4", "H4"}, {"h5", "H5"}, {"h6", "H6"},
            {"reference", "Reference"}, {"note", Roles.NOTE}};

    private final JasperReportsContext context = DefaultJasperReportsContext.getInstance();
    private float pageHeight;
    private int pageNumber;
    private int sequence;

    private JasperLayoutExtractor() {
    }

    public static DocumentLayout extract(JasperPrint print) {
        return new JasperLayoutExtractor().extractDocument(print);
    }

    private DocumentLayout extractDocument(JasperPrint print) {
        LayoutAssembler assembler = new LayoutAssembler();
        List<JRPrintPage> printPages = print.getPages();
        List<PrintPageFormat> formats = new ArrayList<>();
        for (int i = 0; i < printPages.size(); i++) {
            PrintPageFormat format = print.getPageFormat(i);
            formats.add(format);
            pageNumber = i + 1;
            pageHeight = format.getPageHeight();
            sequence = 0;
            assembler.addPage(describe(printPages.get(i).getElements(), 0, 0));
        }

        List<List<LayoutElement>> assembled = assembler.finish();
        List<PageLayout> pages = new ArrayList<>();
        for (int i = 0; i < assembled.size(); i++) {
            pages.add(new PageLayout(i + 1, formats.get(i).getPageWidth(), formats.get(i).getPageHeight(), assembled.get(i)));
        }

        JRPropertiesMap properties = print.getPropertiesMap();
        String language = properties.getProperty(PROPERTY_JASPER_LANGUAGE);
        if (language == null && print.getLocaleCode() != null) {
            language = print.getLocaleCode().replace('_', '-');
        }
        return new DocumentLayout(
                properties.getProperty(PROPERTY_JASPER_TITLE),
                properties.getProperty(PROPERTY_JASPER_AUTHOR),
                properties.getProperty(PROPERTY_JASPER_SUBJECT),
                language,
                pages);
    }

    private List<Node> describe(List<JRPrintElement> elements, int offsetX, int offsetY) {
        List<Node> result = new ArrayList<>();
        for (JRPrintElement element : elements) {
            result.add(describe(element, offsetX, offsetY));
        }
        return result;
    }

    private Node describe(JRPrintElement element, int offsetX, int offsetY) {
        Node node = new Node();
        node.id = "p" + pageNumber + "-" + (++sequence) + (element.getKey() == null ? "" : "-" + element.getKey());
        node.page = pageNumber;
        node.sourceId = element.getSourceElementId();
        node.box = Box.fromTopLeft(offsetX + element.getX(), offsetY + element.getY(), element.getWidth(), element.getHeight(), pageHeight);
        ArtifactInfo bandArtifact = bandArtifact(element.getOrigin());
        if (element instanceof JRPrintAnchor anchored && anchored.getAnchorName() != null) {
            // links inside the document point to this name; PDF/UA-2 needs the element behind it
            node.anchor = anchored.getAnchorName();
        }

        String signatureName = property(element, PROPERTY_SIGNATURE_NAME);
        if (signatureName != null) {
            String description = property(element, PROPERTY_SIGNATURE_DESCRIPTION);
            if (description == null) {
                throw new IllegalStateException("Signature placeholder '" + signatureName + "' needs " + PROPERTY_SIGNATURE_DESCRIPTION
                        + ": a screen reader user must be told what the signature is for");
            }
            node.kind = ElementKind.SIGNATURE;
            node.role = Roles.FORM;
            node.signature = new SignatureInfo(signatureName, description);
            return node;
        }

        node.role = explicitRole(element, node);
        node.alt = property(element, PROPERTY_ALT);

        if (element instanceof JRPrintText printText) {
            node.kind = ElementKind.TEXT;
            node.text = plainText(printText);
            if (node.role == null) {
                node.role = bandArtifact != null ? Roles.ARTIFACT : Roles.P;
            }
        } else if (element instanceof JRPrintImage printImage) {
            node.kind = ElementKind.IMAGE;
            if (node.alt == null) {
                // JasperReports convention: the hyperlink tooltip of an image is its alternate text
                node.alt = printImage.getHyperlinkTooltip();
            }
            if (node.role == null) {
                node.role = bandArtifact != null || node.alt == null ? Roles.ARTIFACT : Roles.FIGURE;
            }
            if (Roles.FIGURE.equals(node.role) && (node.alt == null || node.alt.isBlank())) {
                throw new IllegalStateException("Image " + node.id + " is a Figure but has no alternate text (" + PROPERTY_ALT + ")");
            }
        } else if (element instanceof JRPrintFrame frame) {
            node.kind = ElementKind.FRAME;
            JRLineBox lineBox = frame.getLineBox();
            node.children.addAll(describe(frame.getElements(),
                    offsetX + frame.getX() + lineBox.getLeftPadding(),
                    offsetY + frame.getY() + lineBox.getTopPadding()));
            adoptCellRoleOfContent(node);
        } else {
            // lines, rectangles, ellipses and anything unknown: decoration
            node.kind = ElementKind.GRAPHIC;
            node.role = Roles.ARTIFACT;
            node.opens.clear();
            node.closes.clear();
        }

        if (node.role != null && !node.isArtifact() && !Roles.isKnown(node.role)) {
            throw new IllegalStateException("Element " + node.id + " has an unknown " + PROPERTY_ROLE + ": " + node.role);
        }

        node.artifact = node.isArtifact() ? explicitArtifact(element, bandArtifact) : bandArtifact;
        if (!node.isArtifact()) {
            readAttributes(element, node);
        }
        return node;
    }

    /**
     * The role stated by the template, or {@code null}. Group marks of the JasperReports
     * protocol ({@code start}, {@code end}, and {@code full} on an outer level) are stored in
     * the node for the assembler.
     */
    private static String explicitRole(JRPrintElement element, Node node) {
        String role = property(element, PROPERTY_ROLE);

        // JasperReports tags, outermost first. "full" means: this element is the whole thing.
        List<String> full = new ArrayList<>();
        for (String[] tag : JASPER_TAGS) {
            String value = property(element, PROPERTY_JASPER_TAG_PREFIX + tag[0]);
            if (value == null) {
                continue;
            }
            switch (value.toLowerCase(Locale.ROOT)) {
                case "start" -> node.opens.add(tag[1]);
                case "end" -> node.closes.add(0, tag[1]);
                case "full" -> {
                    // decided below: the innermost "full" is the element itself, the others wrap it
                    node.opens.add(tag[1]);
                    node.closes.add(0, tag[1]);
                    full.add(tag[1]);
                }
                default -> throw new IllegalStateException("Element with " + PROPERTY_JASPER_TAG_PREFIX + tag[0] + "=" + value
                        + ": expected start, end or full");
            }
        }
        if (!full.isEmpty()) {
            // An explicit pdfua.role replaces the innermost "full" (a detail cell that the table
            // component marks td=full can be made a row header with pdfua.role=TH).
            String innermost = full.get(full.size() - 1);
            node.opens.remove(node.opens.lastIndexOf(innermost));
            node.closes.remove(innermost);
            if (role == null) {
                role = innermost;
            }
        }

        String jasperTag = property(element, PROPERTY_JASPER_ACCESSIBILITY_TAG);
        if (jasperTag != null) {
            String tag = jasperTag.toLowerCase(Locale.ROOT);
            if (tag.matches("h[1-6]")) {
                role = role != null ? role : tag.toUpperCase(Locale.ROOT);
            } else {
                switch (tag) {
                    case "artifact" -> role = role != null ? role : Roles.ARTIFACT;
                    case "table" -> role = role != null ? role : Roles.TABLE;
                    case "note" -> role = role != null ? role : Roles.NOTE;
                    case "reference" -> role = role != null ? role : "Reference";
                    case "columnheader" -> {
                        role = role == null || Roles.TD.equals(role) ? Roles.TH : role;
                        node.attributes.put(Attributes.SCOPE, "Column");
                    }
                    case "rowheader" -> {
                        role = role == null || Roles.TD.equals(role) ? Roles.TH : role;
                        node.attributes.put(Attributes.SCOPE, "Row");
                    }
                    default -> {
                        // table_layout and future values: no structure meaning here
                    }
                }
            }
        }
        return role;
    }

    /**
     * The table component turns a cell into a frame and marks every body cell TD; properties
     * written on the cell itself are not passed on. So the text in a cell can speak for its
     * cell: when the only content of a cell has a cell role of its own (pdfua.role=TH on the
     * service name), the cell takes that role and the text becomes an ordinary paragraph in it.
     */
    private static void adoptCellRoleOfContent(Node cell) {
        if (!Roles.isTableCell(cell.role)) {
            return;
        }
        List<Node> content = cell.children.stream().filter(child -> !child.isArtifact()).toList();
        if (content.size() == 1 && content.get(0).kind == ElementKind.TEXT && Roles.isTableCell(content.get(0).role)) {
            Node text = content.get(0);
            cell.role = text.role;
            cell.attributes.remove(Attributes.SCOPE);
            String scope = text.attributes.remove(Attributes.SCOPE);
            if (scope != null && Roles.TH.equals(cell.role)) {
                cell.attributes.put(Attributes.SCOPE, scope);
            }
            text.role = Roles.P;
        }
    }

    private static void readAttributes(JRPrintElement element, Node node) {
        put(node, Attributes.SCOPE, property(element, PROPERTY_SCOPE));
        put(node, Attributes.COL_SPAN, spanOf(element, PROPERTY_COLSPAN, PROPERTY_JASPER_COLSPAN));
        put(node, Attributes.ROW_SPAN, spanOf(element, PROPERTY_ROWSPAN, PROPERTY_JASPER_ROWSPAN));
        put(node, Attributes.LANG, property(element, PROPERTY_JASPER_LANGUAGE));
        put(node, Attributes.ACTUAL_TEXT, property(element, PROPERTY_JASPER_ACTUAL_TEXT));
        if (!Roles.TH.equals(node.role)) {
            node.attributes.remove(Attributes.SCOPE);
        }
        node.tableName = property(element, PROPERTY_TABLE);
        node.tableSummary = property(element, PROPERTY_TABLE_SUMMARY);
        node.listName = property(element, PROPERTY_LIST);
        node.listNumbering = property(element, PROPERTY_LIST_NUMBERING);
    }

    private static void put(Node node, String attribute, String value) {
        if (value != null) {
            node.attributes.put(attribute, value);
        }
    }

    /** A span of more than one cell, as text; {@code null} for a cell that spans only itself. */
    private static String spanOf(JRPrintElement element, String ownProperty, String jasperProperty) {
        String value = property(element, ownProperty);
        if (value == null) {
            value = property(element, jasperProperty);
        }
        if (value == null) {
            return null;
        }
        int span = Integer.parseInt(value);
        return span > 1 ? String.valueOf(span) : null;
    }

    private static ArtifactInfo explicitArtifact(JRPrintElement element, ArtifactInfo bandArtifact) {
        String type = property(element, PROPERTY_ARTIFACT_TYPE);
        if (type != null) {
            return new ArtifactInfo(type, property(element, PROPERTY_ARTIFACT_SUBTYPE));
        }
        return bandArtifact != null ? bandArtifact : ArtifactInfo.LAYOUT;
    }

    /**
     * Content of these bands repeats on every page and is not part of the reading flow. Only the
     * bands of the report itself count: a table component builds its column footer as the page
     * footer of an inner report, and that is content of the table.
     */
    private static ArtifactInfo bandArtifact(JROrigin origin) {
        BandTypeEnum band = origin == null || origin.getReportName() != null ? null : origin.getBandType();
        if (band == null) {
            return null;
        }
        return switch (band) {
            case PAGE_HEADER -> ArtifactInfo.HEADER;
            case PAGE_FOOTER, LAST_PAGE_FOOTER -> ArtifactInfo.FOOTER;
            case BACKGROUND -> ArtifactInfo.BACKGROUND;
            default -> null;
        };
    }

    private static String property(JRPrintElement element, String name) {
        if (!element.hasProperties()) {
            return null;
        }
        String value = element.getPropertiesMap().getProperty(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The text without styled text / HTML markup. */
    private String plainText(JRPrintText printText) {
        JRStyledText styledText = JRStyledTextUtil.getInstance(context)
                .getStyledText(printText, JRStyledTextAttributeSelector.getAllSelector(context));
        return styledText == null ? null : styledText.getText();
    }
}
