package ro.ca2datavision.pdfua;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ro.ca2datavision.pdfua.TaggedPdf.Node;
import ro.ca2datavision.pdfua.TaggedPdf.Painted;
import ro.ca2datavision.pdfua.layout.Roles;
import ro.ca2datavision.pdfua.postprocess.PdfUaPart;

/**
 * The demo document made for PDF/UA-2 (ISO 14289-2:2024): the same content as the PDF/UA-1
 * document, expressed for PDF 2.0. Where it makes the point, it is compared with the PDF/UA-1
 * run, which must not have changed.
 */
class PdfUa2Test {

    private static final String PDF_2_0 = "http://iso.org/pdf2/ssn";
    private static final String PDF_1_7 = "http://iso.org/pdf/ssn";
    private static final COSName SD = COSName.getPDFName("SD");

    private static DemoRun ua1;
    private static DemoRun ua2;

    @BeforeAll
    static void generate() throws Exception {
        ua1 = DemoRun.get(PdfUaPart.PDF_UA_1);
        ua2 = DemoRun.get(PdfUaPart.PDF_UA_2);
    }

    // ---- conformance --------------------------------------------------------------------------

    @Test
    void taggedAndSignedDocumentsPassPdfUa2Validation() {
        assertTrue(ua2.pdfUaValidation.compliant(), () -> "veraPDF PDF/UA-2: " + ua2.pdfUaValidation);
        assertEquals(List.of(), ua2.pdfUaValidation.failures());
        assertTrue(ua2.pdfUaValidation.checks() > 10_000, "the validator looked at the content: " + ua2.pdfUaValidation.checks());
        assertTrue(ua2.signedValidation.compliant(), () -> "veraPDF PDF/UA-2, signed: " + ua2.signedValidation);
        assertFalse(ua2.rawValidation.compliant(), "the JasperReports export is no PDF/UA-2 either");
    }

    @Test
    void postProcessingReportsNoProblems() {
        assertEquals(PdfUaPart.PDF_UA_2, ua2.result.report().part());
        assertEquals(List.of(), ua2.result.report().warnings());
        assertEquals(12, ua2.result.report().structureDestinations(), "11 bookmarks and the link to section 4.1");
    }

    @Test
    void declaresPdfUa2OnPdf20() throws Exception {
        byte[] file = Files.readAllBytes(ua2.result.pdfUa());
        assertEquals("%PDF-2.0", new String(file, 0, 8, US_ASCII), "file header");
        assertEquals("2.0", ua2.pdfUa.getDocumentCatalog().getVersion(), "catalog version");
        String xmp = new String(ua2.pdfUa.getDocumentCatalog().getMetadata().toByteArray(), UTF_8);
        assertTrue(xmp.contains("<pdfuaid:part>2</pdfuaid:part>"), xmp);
        assertTrue(xmp.contains("<pdfuaid:rev>2024</pdfuaid:rev>"), xmp);

        // the default output still claims PDF/UA-1, on PDF 1.7, without a revision
        String xmp1 = new String(ua1.pdfUa.getDocumentCatalog().getMetadata().toByteArray(), UTF_8);
        assertTrue(xmp1.contains("<pdfuaid:part>1</pdfuaid:part>"));
        assertFalse(xmp1.contains("pdfuaid:rev"));
        assertEquals("1.7", ua1.pdfUa.getDocumentCatalog().getVersion());
    }

    // ---- structure ----------------------------------------------------------------------------

    @Test
    void sameContentInTheSameOrderAsPdfUa1() {
        List<Node> one = ua1.tagged.readingOrder();
        List<Node> two = ua2.tagged.readingOrder();
        // PDF 2.0 calls a note FENote; every other type is the same
        assertEquals(one.stream().map(Node::type).toList(),
                two.stream().map(node -> "FENote".equals(node.type()) ? "Note" : node.type()).toList());
        assertEquals(one.stream().map(Node::text).toList(), two.stream().map(Node::text).toList());
    }

    @Test
    void everyElementSaysWhichNamespaceItBelongsTo() {
        COSDictionary root = ua2.pdfUa.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
        COSDictionary document = (COSDictionary) root.getCOSArray(COSName.K).getObject(0);
        assertEquals("Document", document.getNameAsString(COSName.S));
        assertEquals(PDF_2_0, ((COSDictionary) document.getDictionaryObject(COSName.getPDFName("NS"))).getString(COSName.getPDFName("NS")),
                "ISO 14289-2, 8.2.5.2: the Document element is in the PDF 2.0 namespace");

        Map<String, Set<String>> typesByNamespace = new TreeMap<>();
        for (Node node : ua2.tagged.readingOrder()) {
            assertNotNull(node.namespace(), () -> node.type() + " has no namespace");
            typesByNamespace.computeIfAbsent(node.namespace(), key -> new TreeSet<>()).add(node.type());
        }
        // BlockQuote is not a PDF 2.0 type; it stays a PDF 1.7 BlockQuote, which PDF/UA-2 allows (8.2.4)
        assertEquals(Set.of("BlockQuote"), typesByNamespace.get(PDF_1_7));
        assertEquals(Set.of("Caption", "FENote", "Figure", "Form", "H1", "H2", "L", "LBody", "LI", "Lbl", "Link",
                "P", "TD", "TH", "TR", "Table", "Title"), typesByNamespace.get(PDF_2_0));

        // the root names the namespaces it uses
        COSArray namespaces = root.getCOSArray(COSName.getPDFName("Namespaces"));
        Set<String> declared = new TreeSet<>();
        for (int i = 0; i < namespaces.size(); i++) {
            declared.add(((COSDictionary) namespaces.getObject(i)).getString(COSName.getPDFName("NS")));
        }
        assertEquals(Set.of(PDF_2_0, PDF_1_7), declared);
    }

    @Test
    void aReaderWithoutPdf20SupportStillKnowsEveryType() {
        // Software that predates PDF 2.0 ignores /NS and resolves unknown types through the role
        // map of the structure tree root. It must end up with PDF 1.7 types only: poppler 26.02
        // drops elements it cannot resolve, Title and FENote with their text included.
        COSDictionary roleMap = ua2.pdfUa.getDocumentCatalog().getStructureTreeRoot().getCOSObject()
                .getCOSDictionary(COSName.ROLE_MAP);
        assertEquals("P", roleMap.getNameAsString("Title"));
        assertEquals("Note", roleMap.getNameAsString("FENote"));
        assertEquals(2, roleMap.size(), "nothing else needs a fallback");
        for (Node node : ua2.tagged.readingOrder()) {
            String seenByOldReader = roleMap.containsKey(node.type()) ? roleMap.getNameAsString(node.type()) : node.type();
            assertTrue(Roles.STANDARD.contains(seenByOldReader), () -> node.type() + " is unknown to a PDF 1.7 reader");
        }
    }

    @Test
    void titleIsAPdf20TitleAndTheNoteAnFENote() {
        Node title = ua2.tagged.topLevel().get(1);
        assertEquals("Title", title.type());
        assertEquals(PDF_2_0, title.namespace());
        assertEquals("Service Agreement Summary", title.text());

        List<Node> notes = ua2.tagged.readingOrder().stream().filter(node -> "FENote".equals(node.type())).toList();
        assertEquals(1, notes.size());
        Node note = notes.get(0);
        assertEquals("Note: gross prices include value added tax at 20 percent.", note.text());
        assertNotNull(note.entry("ID"), "the ID that PDF/UA-1 needed is kept");
        // no NoteType attribute: the default None, since nothing in the text refers to the note
        assertNull(note.attribute("NoteType"));
        assertTrue(ua2.tagged.readingOrder().stream().noneMatch(node -> "Note".equals(node.type())), "ISO 14289-2, 8.2.5.14");
    }

    // ---- destinations -------------------------------------------------------------------------

    @Test
    void bookmarksLeadToTheStructureElementsOfTheirHeadings() {
        List<PDOutlineItem> items = new ArrayList<>();
        collect(ua2.pdfUa.getDocumentCatalog().getDocumentOutline(), items);
        assertEquals(11, items.size());
        for (PDOutlineItem item : items) {
            COSDictionary dictionary = item.getCOSObject();
            assertNull(dictionary.getDictionaryObject(COSName.DEST), "no plain page destination");
            COSDictionary action = (COSDictionary) dictionary.getDictionaryObject(COSName.A);
            assertEquals("GoTo", action.getNameAsString(COSName.S));
            COSArray pageDestination = (COSArray) action.getDictionaryObject(COSName.D);
            COSArray structureDestination = (COSArray) action.getDictionaryObject(SD);

            COSDictionary heading = (COSDictionary) structureDestination.getObject(0);
            assertSame(item.getStructureElement().getCOSObject(), heading, "SD and SE name the same element");
            assertTrue(heading.getNameAsString(COSName.S).matches("H[12]"), item.getTitle());
            // apart from the first element, the two destinations are the same: /XYZ left top zoom
            for (int i = 1; i < pageDestination.size(); i++) {
                assertEquals(pageDestination.getObject(i), structureDestination.getObject(i));
            }
            assertEquals("Page", ((COSDictionary) pageDestination.getObject(0)).getNameAsString(COSName.TYPE),
                    "readers without PDF 2.0 support keep the page destination");
        }
    }

    private static void collect(PDOutlineNode node, List<PDOutlineItem> into) {
        for (PDOutlineItem item : node.children()) {
            into.add(item);
            collect(item, into);
        }
    }

    @Test
    void linkInsideTheDocumentLeadsToTheHeadingOfSection41() {
        Node link = internalLink(ua2);
        COSDictionary action = link.annotations().get(0).getCOSDictionary(COSName.A);
        COSArray structureDestination = (COSArray) action.getDictionaryObject(SD);
        Node heading = ua2.tagged.readingOrder().stream()
                .filter(node -> "H2".equals(node.type()) && "4.1 Signatures".equals(node.text())).findFirst().orElseThrow();
        assertSame(heading.element(), structureDestination.getObject(0), "the anchor of the template is resolved exactly");
        assertNotNull(action.getDictionaryObject(COSName.D), "the page destination stays for older readers");

        // the PDF/UA-1 output has no structure destinations
        assertNull(internalLink(ua1).annotations().get(0).getCOSDictionary(COSName.A).getDictionaryObject(SD));
        assertNotNull(ua1.pdfUa.getDocumentCatalog().getDocumentOutline().getFirstChild().getCOSObject().getDictionaryObject(COSName.DEST));
    }

    private static Node internalLink(DemoRun run) {
        return run.tagged.readingOrder().stream()
                .filter(node -> "Link".equals(node.type()))
                .filter(node -> "GoTo".equals(node.annotations().get(0).getCOSDictionary(COSName.A).getNameAsString(COSName.S)))
                .findFirst().orElseThrow();
    }

    // ---- signature fields ---------------------------------------------------------------------

    @Test
    void signatureWidgetsAreDescribedInContentsToo() {
        for (PDField field : ua2.pdfUa.getDocumentCatalog().getAcroForm(null).getFields()) {
            PDAnnotationWidget widget = field.getWidgets().get(0);
            // ISO 14289-2, 8.10.2.3: without a Lbl in the Form element, Contents describes the widget
            assertEquals(field.getAlternateFieldName(), widget.getContents());
        }
        PDField signedField = ua2.signed.getDocumentCatalog().getAcroForm(null).getField(PdfUaPipeline.SIGNED_FIELD);
        String contents = signedField.getWidgets().get(0).getContents();
        assertTrue(contents.contains("Signed by Demo Customer on "), "the signed state is announced: " + contents);
        assertEquals(signedField.getAlternateFieldName(), contents);

        // the PDF/UA-1 output describes widgets with TU only, as before
        for (PDField field : ua1.pdfUa.getDocumentCatalog().getAcroForm(null).getFields()) {
            assertNull(field.getWidgets().get(0).getContents());
        }
    }

    @Test
    void signingOnlyAppendsToTheFile() throws Exception {
        byte[] unsigned = Files.readAllBytes(ua2.result.pdfUa());
        byte[] signed = Files.readAllBytes(ua2.result.signedPdf());
        assertArrayEquals(unsigned, Arrays.copyOf(signed, unsigned.length));
    }

    // ---- what does not change -------------------------------------------------------------------

    @Test
    void everyPaintingOperatorIsEitherTaggedOrAnArtifact() throws Exception {
        List<Painted> painted = ua2.tagged.painted();
        assertEquals(ua1.tagged.painted().size(), painted.size(), "the same content stream operators as for PDF/UA-1");
        for (Painted operator : painted) {
            assertTrue(operator.artifact() ^ operator.tagged(), () -> "page " + operator.page() + ": " + operator.operator());
        }
    }

    @Test
    void postProcessingDoesNotChangeTheAppearance() throws Exception {
        assertSameRendering(ua2.raw, ua2.pdfUa);
    }

    private static void assertSameRendering(PDDocument expected, PDDocument actual) throws Exception {
        PDFRenderer before = new PDFRenderer(expected);
        PDFRenderer after = new PDFRenderer(actual);
        for (int page = 0; page < expected.getNumberOfPages(); page++) {
            BufferedImage a = before.renderImageWithDPI(page, 72);
            BufferedImage b = after.renderImageWithDPI(page, 72);
            int different = 0;
            for (int y = 0; y < a.getHeight(); y++) {
                for (int x = 0; x < a.getWidth(); x++) {
                    if (a.getRGB(x, y) != b.getRGB(x, y)) {
                        different++;
                    }
                }
            }
            assertEquals(0, different, "pixels that differ on page " + (page + 1));
        }
    }
}
