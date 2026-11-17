package ro.ca2datavision.pdfua;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.COSObjectable;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ro.ca2datavision.pdfua.TaggedPdf.Node;
import ro.ca2datavision.pdfua.TaggedPdf.Painted;
import ro.ca2datavision.pdfua.layout.DocumentLayout;
import ro.ca2datavision.pdfua.layout.LayoutElement;
import ro.ca2datavision.pdfua.layout.LayoutJson;
import ro.ca2datavision.pdfua.layout.Roles;

/**
 * Checks the post-processed demo document: the flow, the PDF/UA basics and the three goals of
 * the project (everything tagged; artifacts; links). Tables, lists and the signed file have
 * their own test classes; all of them share one run of the flow ({@link DemoRun}).
 */
class PdfUaPipelineTest {

    private static final String LOGO_ALT = "Aurora Data Services logo";

    private static DemoRun demo;
    private static PdfUaPipeline.Result result;
    private static PDDocument raw;
    private static PDDocument pdfUa;
    private static TaggedPdf tagged;

    @BeforeAll
    static void generate() throws Exception {
        demo = DemoRun.get();
        result = demo.result;
        raw = demo.raw;
        pdfUa = demo.pdfUa;
        tagged = demo.tagged;
    }

    // ---- the flow itself --------------------------------------------------------------------

    @Test
    void intermediateFilesAreKept() {
        assertAll(
                () -> assertTrue(Files.size(result.rawPdf()) > 0, "raw JasperReports PDF"),
                () -> assertTrue(Files.size(result.layoutJson()) > 0, "layout extracted from the report"),
                () -> assertTrue(Files.size(result.pdfUa()) > 0, "post-processed PDF"),
                () -> assertTrue(Files.size(result.structureDump()) > 0, "structure dump"),
                () -> assertTrue(Files.size(result.signedPdf()) > 0, "signed PDF"));
    }

    @Test
    void jasperExportIsNotTaggedAndFailsPdfUaValidation() {
        assertNull(raw.getDocumentCatalog().getStructureTreeRoot(), "the Community Edition export has no structure tree");
        assertNull(raw.getDocumentCatalog().getAcroForm(), "JasperReports creates no signature fields");
        assertFalse(demo.rawValidation.compliant());
    }

    @Test
    void postProcessingReportsNoProblems() {
        assertEquals(List.of(), result.report().warnings());
    }

    @Test
    void documentHasAtLeastTwoPages() {
        assertEquals(3, pdfUa.getNumberOfPages());
        assertEquals(raw.getNumberOfPages(), pdfUa.getNumberOfPages());
    }

    @Test
    void postProcessingDoesNotChangeTheAppearance() throws Exception {
        PDFRenderer before = new PDFRenderer(raw);
        PDFRenderer after = new PDFRenderer(pdfUa);
        for (int page = 0; page < raw.getNumberOfPages(); page++) {
            BufferedImage expected = before.renderImageWithDPI(page, 96);
            BufferedImage actual = after.renderImageWithDPI(page, 96);
            assertEquals(expected.getWidth(), actual.getWidth());
            assertEquals(expected.getHeight(), actual.getHeight());
            int different = 0;
            for (int y = 0; y < expected.getHeight(); y++) {
                for (int x = 0; x < expected.getWidth(); x++) {
                    if (expected.getRGB(x, y) != actual.getRGB(x, y)) {
                        different++;
                    }
                }
            }
            assertEquals(0, different, "pixels that differ on page " + (page + 1));
        }
    }

    // ---- PDF/UA conformance -------------------------------------------------------------------

    @Test
    void postProcessedDocumentPassesPdfUa1Validation() {
        assertTrue(demo.pdfUaValidation.compliant(), () -> "veraPDF PDF/UA-1: " + demo.pdfUaValidation);
        assertEquals(List.of(), demo.pdfUaValidation.failures());
        assertTrue(demo.pdfUaValidation.checks() > 1000, "the validator really looked at the content: " + demo.pdfUaValidation.checks());
    }

    @Test
    void documentLevelRequirementsAreMet() throws Exception {
        PDDocumentCatalog catalog = pdfUa.getDocumentCatalog();
        String xmp = new String(catalog.getMetadata().toByteArray(), StandardCharsets.UTF_8);
        String title = "Service Agreement Summary SA-2026-0042";
        assertAll(
                () -> assertTrue(catalog.getMarkInfo().isMarked(), "MarkInfo/Marked"),
                () -> assertEquals("en-GB", catalog.getLanguage(), "document language"),
                () -> assertEquals(title, pdfUa.getDocumentInformation().getTitle(), "title"),
                () -> assertTrue(catalog.getViewerPreferences().displayDocTitle(), "DisplayDocTitle"),
                () -> assertTrue(xmp.contains("<pdfuaid:part>1</pdfuaid:part>"), "PDF/UA identifier in XMP"),
                () -> assertTrue(xmp.contains(title), "dc:title in XMP"),
                () -> assertNotNull(catalog.getDocumentOutline().getFirstChild(), "bookmarks"));
        for (PDPage page : pdfUa.getPages()) {
            assertEquals("S", page.getCOSObject().getNameAsString(COSName.getPDFName("Tabs")), "tab order follows the structure");
        }
    }

    // ---- 1. everything is tagged ----------------------------------------------------------------

    @Test
    void everyPaintingOperatorIsEitherTaggedOrAnArtifact() throws Exception {
        List<Painted> painted = tagged.painted();
        assertFalse(painted.isEmpty());
        for (Painted operator : painted) {
            assertTrue(operator.artifact() ^ operator.tagged(),
                    () -> "page " + operator.page() + ": " + operator.operator() + " must be in tagged content or in an artifact, never both or neither: " + operator.tags());
        }
    }

    @Test
    void structureTreeFollowsTheReadingOrderOfTheReport() {
        List<String> types = tagged.topLevel().stream().map(Node::type).toList();
        assertEquals(List.of(
                // page 1: logo, title, introduction, section 1
                "Figure", "Title", "P",
                "H1", "P",
                "H2", "P", "Figure", "P",
                "H2", "P", "Figure", "BlockQuote", "P",
                // page 2: section 2 with two lists and the table, which ends on page 3
                "H1", "H2", "P", "L",
                "H2", "L",
                "H2", "P", "Table", "Note",
                // page 3: contact and acceptance
                "H1", "H2", "P", "P", "P",
                "H1", "P",
                "H2", "P", "Form", "P", "P", "Form", "P"), types);
    }

    @Test
    void onlyStandardStructureTypesAreUsedAfterRoleMapping() {
        var roleMap = pdfUa.getDocumentCatalog().getStructureTreeRoot().getRoleMap();
        for (Node node : tagged.readingOrder()) {
            String type = roleMap.containsKey(node.type()) ? String.valueOf(roleMap.get(node.type())) : node.type();
            assertTrue(Roles.STANDARD.contains(type), () -> node.type() + " is not a standard structure type");
        }
        assertEquals("P", roleMap.get("Title"));
    }

    @Test
    void headingsAreTaggedAndDoNotSkipLevels() {
        List<Node> headings = tagged.readingOrder().stream().filter(node -> Roles.headingLevel(node.type()) > 0).toList();
        assertEquals(List.of("H1", "H2", "H2", "H1", "H2", "H2", "H2", "H1", "H2", "H1", "H2"), headings.stream().map(Node::type).toList());
        assertEquals("1. About this document", headings.get(0).text());
        assertEquals("2.3 Price list", headings.get(6).text());
        assertEquals("4.1 Signatures", headings.get(10).text());

        int previous = 0;
        for (Node heading : headings) {
            int level = Roles.headingLevel(heading.type());
            assertTrue(level <= previous + 1, () -> "heading level skipped before \"" + heading.text() + "\"");
            previous = level;
        }
    }

    @Test
    void paragraphTextKeepsItsWordSpacesAcrossLineBreaks() {
        String intro = tagged.topLevel().get(2).text();
        assertEquals("This document summarises the managed analytics service that Aurora Data Services SRL provides to the "
                + "customer. It is also a demonstration of an accessible, tagged PDF that is produced with JasperReports "
                + "and a PDF/UA post-processing step.", intro);
    }

    @Test
    void figuresHaveAlternateTextAndABoundingBox() {
        List<Node> figures = tagged.readingOrder().stream().filter(node -> "Figure".equals(node.type())).toList();
        assertEquals(3, figures.size());
        for (Node figure : figures) {
            assertTrue(figure.alt() != null && !figure.alt().isBlank(), "alternate text");
        }
        assertEquals(3, countFiguresWithBoundingBox(pdfUa.getDocumentCatalog().getStructureTreeRoot().getCOSObject()));
    }

    private static int countFiguresWithBoundingBox(COSDictionary element) {
        int count = 0;
        if ("Figure".equals(element.getNameAsString(COSName.S))) {
            COSDictionary attributes = element.getCOSDictionary(COSName.A);
            if (attributes != null && attributes.getCOSArray(COSName.BBOX) != null) {
                count++;
            }
        }
        COSBase kids = element.getDictionaryObject(COSName.K);
        if (kids instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.getObject(i) instanceof COSDictionary kid && kid.containsKey(COSName.S)) {
                    count += countFiguresWithBoundingBox(kid);
                }
            }
        }
        return count;
    }

    // ---- 2. everything that should be an artifact is an artifact ------------------------------------------

    @Test
    void logoIsAFigureOnTheFirstPageOnly() {
        List<Node> logos = tagged.readingOrder().stream().filter(node -> LOGO_ALT.equals(node.alt())).toList();
        assertEquals(1, logos.size(), "the logo is announced exactly once");
        assertEquals(1, logos.get(0).page());
        assertEquals("Figure", logos.get(0).type());
        assertEquals("Figure", tagged.topLevel().get(0).type(), "the logo is the first thing in the reading order");
    }

    @Test
    void logoOnFollowingPagesIsAPaginationArtifact() throws Exception {
        // on the first page the logo is the first XObject that is tagged as a figure
        Painted firstPageLogo = tagged.painted().stream()
                .filter(operator -> operator.page() == 1 && "Do".equals(operator.operator()) && operator.tags().equals(List.of("Figure")))
                .findFirst().orElseThrow();
        assertTrue(firstPageLogo.tagged(), "page 1: real content");

        for (int page = 2; page <= pdfUa.getNumberOfPages(); page++) {
            int number = page;
            Painted logo = tagged.painted().stream()
                    .filter(operator -> operator.page() == number && "Do".equals(operator.operator())
                            && firstPageLogo.operand().equals(operator.operand()))
                    .findFirst().orElseThrow(() -> new AssertionError("the logo XObject is drawn on page " + number));
            assertTrue(logo.artifact(), "page " + page + ": artifact");
            assertFalse(logo.tagged());
            assertEquals("Pagination", logo.properties().getNameAsString(COSName.TYPE));
            assertEquals("Header", logo.properties().getNameAsString(COSName.SUBTYPE));
        }
    }

    @Test
    void watermarkIsAnArtifactOnEveryPage() throws Exception {
        for (int page = 1; page <= pdfUa.getNumberOfPages(); page++) {
            int number = page;
            // the background band is drawn first: the watermark is the first XObject of the page
            Painted watermark = tagged.painted().stream()
                    .filter(operator -> operator.page() == number && "Do".equals(operator.operator()))
                    .findFirst().orElseThrow();
            assertTrue(watermark.artifact() && !watermark.tagged(), "page " + page);
            assertEquals("Pagination", watermark.properties().getNameAsString(COSName.TYPE));
            assertEquals("Watermark", watermark.properties().getNameAsString(COSName.SUBTYPE));
        }
    }

    @Test
    void linesAndOtherDrawingsAreArtifacts() throws Exception {
        List<Painted> paths = tagged.painted().stream().filter(Painted::isPath).toList();
        assertTrue(paths.size() >= 10, "horizontal and vertical lines are present: " + paths.size());
        for (Painted path : paths) {
            assertTrue(path.artifact() && !path.tagged(), () -> "page " + path.page() + ": path is not an artifact");
        }
    }

    @Test
    void pageHeaderAndFooterAreArtifactsAndNotInTheStructureTree() throws Exception {
        String structureText = tagged.readingOrder().stream().map(Node::text).collect(Collectors.joining("\n"));
        assertFalse(structureText.contains("Confidential"), "footer text is not read");
        assertFalse(structureText.contains("Page 1 of"), "page numbers are not read");
        assertFalse(structureText.contains("Summary for the customer"), "running header is not read");

        Set<String> subtypes = tagged.painted().stream()
                .filter(operator -> operator.artifact() && "Pagination".equals(operator.properties().getNameAsString(COSName.TYPE)))
                .map(operator -> String.valueOf(operator.properties().getNameAsString(COSName.SUBTYPE)))
                .collect(Collectors.toSet());
        // "null": repeated table header rows are pagination artifacts without a subtype
        assertEquals(Set.of("Header", "Footer", "Watermark", "null"), subtypes);
    }

    @Test
    void layoutMarksTheRepeatedLogoAsArtifact() throws Exception {
        DocumentLayout layout = LayoutJson.read(result.layoutJson());
        LayoutElement first = logoIn(layout, 0);
        LayoutElement second = logoIn(layout, 1);
        assertEquals("Figure", first.role());
        assertTrue(second.isArtifact());
        assertEquals("Header", second.artifact().subtype());
    }

    private static LayoutElement logoIn(DocumentLayout layout, int pageIndex) {
        return layout.pages().get(pageIndex).elements().stream()
                .filter(element -> LOGO_ALT.equals(element.alt())).findFirst().orElseThrow();
    }

    // ---- 3. links, e-mail addresses and phone numbers -------------------------------------------------------

    @Test
    void everyLinkAnnotationIsTaggedAsLinkWithADescription() throws Exception {
        int links = 0;
        for (PDPage page : pdfUa.getPages()) {
            for (PDAnnotation annotation : page.getAnnotations()) {
                if (!(annotation instanceof PDAnnotationLink link)) {
                    continue;
                }
                links++;
                COSDictionary parent = structParent(pdfUa, link);
                assertEquals("Link", parent.getNameAsString(COSName.S), "link annotation sits in a Link structure element");
                assertTrue(link.getContents() != null && !link.getContents().isBlank(), "alternate description (/Contents)");
            }
        }
        assertEquals(7, links, "5 links to the outside, one of them wrapped over two lines, and 1 link inside the document");
    }

    @Test
    void webEmailAndPhoneLinksAreInsideTheirParagraph() {
        List<Node> links = tagged.readingOrder().stream()
                .filter(node -> "Link".equals(node.type()) && uri(node) != null).toList();
        assertEquals(List.of(
                        "https://www.w3.org/WAI/fundamentals/accessibility-intro/",
                        "https://www.example.com/support",
                        "mailto:support@example.com",
                        "tel:+442079460142",
                        "https://www.example.com/terms"),
                links.stream().map(PdfUaPipelineTest::uri).toList());
        assertEquals(List.of(
                        "W3C Web Accessibility Initiative",
                        "www.example.com/support",
                        "support@example.com",
                        "+44 20 7946 0142",
                        "Read the full terms and conditions of the service"),
                links.stream().map(Node::text).toList());
        for (Node link : links) {
            assertEquals("P", link.parentType(), "inline link is a child of its paragraph");
            assertFalse(link.annotations().isEmpty(), "Link element references its annotation");
        }
        // a link that wraps to a second line is still one Link element with two annotations
        assertEquals(2, links.get(0).annotations().size());
    }

    @Test
    void linkToAPlaceInTheDocumentIsTaggedToo() {
        List<Node> internal = tagged.readingOrder().stream()
                .filter(node -> "Link".equals(node.type()) && uri(node) == null).toList();
        assertEquals(1, internal.size());
        Node link = internal.get(0);
        assertEquals("Sign the agreement in section 4.1", link.text());
        assertEquals("LBody", link.parentType(), "the link is the body of a list item");
        COSDictionary annotation = link.annotations().get(0);
        assertTrue(annotation.containsKey(COSName.DEST) || annotation.containsKey(COSName.A), "the annotation leads somewhere");
        // no address to add for a link inside the document: the description is the link text
        assertEquals("Sign the agreement in section 4.1", annotation.getString(COSName.CONTENTS));
    }

    private static String uri(Node link) {
        PDAnnotationLink annotation = new PDAnnotationLink(link.annotations().get(0));
        return annotation.getAction() instanceof PDActionURI action ? action.getURI() : null;
    }

    // ---- signature fields -------------------------------------------------------------------------------------------------

    @Test
    void signatureFieldsAreAccessible() throws Exception {
        List<PDField> fields = pdfUa.getDocumentCatalog().getAcroForm().getFields();
        assertEquals(List.of("CustomerSignature", "ProviderSignature"), fields.stream().map(PDField::getPartialName).toList());
        for (PDField field : fields) {
            assertTrue(field instanceof PDSignatureField, "signature field");
            assertNull(((PDSignatureField) field).getSignature(), "the field is empty, ready to be signed");
            // the description is what a screen reader announces when the field gets focus
            assertTrue(field.getAlternateFieldName().contains("By signing here"), field.getAlternateFieldName());
            assertTrue(field.getAlternateFieldName().contains("Service Agreement SA-2026-0042"));

            PDAnnotationWidget widget = field.getWidgets().get(0);
            assertEquals("Form", structParent(pdfUa, widget).getNameAsString(COSName.S), "widget sits in a Form structure element");
            assertTrue(widget.isPrinted());
            assertTrue(widget.getRectangle().getWidth() > 100 && widget.getRectangle().getHeight() > 30, "visible field");
            assertEquals(3, pdfUa.getPages().indexOf(widget.getPage()) + 1);
        }
    }

    @Test
    void everySignatureFieldIsPrecededByAVisibleLabelSayingWhatIsSigned() {
        List<Node> order = tagged.topLevel();
        int forms = 0;
        for (int i = 0; i < order.size(); i++) {
            if (!"Form".equals(order.get(i).type())) {
                continue;
            }
            forms++;
            Node label = order.get(i - 1);
            Node field = order.get(i);
            assertEquals("P", label.type());
            assertTrue(label.text().contains("By signing in the field below"), label.text());
            assertTrue(label.text().contains("Service Agreement SA-2026-0042"), label.text());
            assertEquals(1, field.annotations().size());
            assertEquals("Sig", field.annotations().get(0).getNameAsString(COSName.FT));
        }
        assertEquals(2, forms);
    }

    /** Resolves /StructParent of an annotation through the parent tree, as a PDF reader does. */
    static COSDictionary structParent(PDDocument document, PDAnnotation annotation) throws Exception {
        int key = annotation.getStructParent();
        assertTrue(key >= 0, "annotation has a /StructParent entry");
        Object value = document.getDocumentCatalog().getStructureTreeRoot().getParentTree().getValue(key);
        assertNotNull(value, "parent tree entry " + key);
        return (COSDictionary) ((COSObjectable) value).getCOSObject();
    }
}
