package ro.ca2datavision.pdfua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import net.sf.jasperreports.engine.JREmptyDataSource;
import net.sf.jasperreports.engine.JasperPrint;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.verapdf.pdfa.flavours.PDFAFlavour;

import ro.ca2datavision.pdfua.TaggedPdf.Node;
import ro.ca2datavision.pdfua.TaggedPdf.Painted;
import ro.ca2datavision.pdfua.jasper.JasperLayoutExtractor;
import ro.ca2datavision.pdfua.jasper.JasperReportGenerator;
import ro.ca2datavision.pdfua.postprocess.PdfUaPart;
import ro.ca2datavision.pdfua.postprocess.PdfUaPostProcessor;
import ro.ca2datavision.pdfua.postprocess.PostProcessingReport;

/**
 * The classic JasperReports table: a column header band and one detail band per record, over
 * several pages, without the table component and without any frame. The same report is marked
 * in two ways, and each is made into PDF/UA-1 and PDF/UA-2; all four must give the same
 * structure:
 * <ul>
 *   <li>{@code tabular-roles.jrxml}: only {@code pdfua.role} = TH or TD on the cells;</li>
 *   <li>{@code tabular-jasper-tags.jrxml}: the start/end/full protocol of JasperReports.</li>
 * </ul>
 * The summary of the report links to the first page. For PDF/UA-2 that link needs a structure
 * destination, and as the target is a page, not an anchor, it is the first element of the page.
 */
class TabularReportTest {

    private static final int RECORDS = 90;

    static Stream<Arguments> templatesAndParts() {
        return Stream.of(
                Arguments.of("reports/tabular-roles.jrxml", PdfUaPart.PDF_UA_1),
                Arguments.of("reports/tabular-jasper-tags.jrxml", PdfUaPart.PDF_UA_1),
                Arguments.of("reports/tabular-roles.jrxml", PdfUaPart.PDF_UA_2),
                Arguments.of("reports/tabular-jasper-tags.jrxml", PdfUaPart.PDF_UA_2));
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("templatesAndParts")
    void bandsOverSeveralPagesBecomeOneTable(String template, PdfUaPart part, @TempDir Path directory) throws Exception {
        JasperPrint print = JasperReportGenerator.fill(template, Map.of(), new JREmptyDataSource(RECORDS));
        Path raw = directory.resolve("raw.pdf");
        Path pdfUa = directory.resolve("pdfua.pdf");
        JasperReportGenerator.exportPdf(print, raw);
        PostProcessingReport report = PdfUaPostProcessor.process(raw, JasperLayoutExtractor.extract(print), pdfUa, part);

        assertEquals(List.of(), report.warnings());
        assertEquals(1, report.tables());
        VeraPdf.Outcome outcome = VeraPdf.validate(pdfUa, part == PdfUaPart.PDF_UA_1 ? PDFAFlavour.PDFUA_1 : PDFAFlavour.PDFUA_2);
        assertTrue(outcome.compliant(), outcome::toString);

        try (PDDocument document = Loader.loadPDF(pdfUa.toFile())) {
            assertEquals(3, document.getNumberOfPages(), "the table runs over three pages");
            TaggedPdf tagged = new TaggedPdf(document);

            // heading, ONE table, and the two paragraphs after it: the table ended by itself
            List<Node> top = tagged.topLevel();
            assertEquals(List.of("H1", "Table", "P", "P"), top.stream().map(Node::type).toList());
            assertEquals("End of the list.", top.get(2).text());

            List<Node> rows = top.get(1).children();
            assertEquals(1 + RECORDS, rows.size(), "the header row once, then one row per record");
            Node header = rows.get(0);
            assertEquals(List.of("No.", "Item", "Quantity"), header.children().stream().map(Node::text).toList());
            for (Node cell : header.children()) {
                assertEquals("TH", cell.type());
                assertEquals("Column", cell.attribute("Scope"));
            }
            for (Node row : rows.subList(1, rows.size())) {
                assertEquals("TR", row.type());
                assertEquals(List.of("TD", "TD", "TD"), row.children().stream().map(Node::type).toList());
            }
            assertEquals(List.of("90", "Item 90", "270"), rows.get(RECORDS).children().stream().map(Node::text).toList());
            assertEquals(3, rows.get(RECORDS).children().get(0).page(), "the last row is on the third page");

            // the column header is printed on every page; on pages 2 and 3 it is an artifact
            for (int page = 2; page <= 3; page++) {
                int number = page;
                long repeatedHeaderCells = tagged.painted().stream()
                        .filter(operator -> operator.page() == number && !operator.isPath() && operator.artifact()
                                && "Pagination".equals(operator.properties().getNameAsString(COSName.TYPE))
                                && operator.properties().getNameAsString(COSName.SUBTYPE) == null)
                        .count();
                assertEquals(3, repeatedHeaderCells, "repeated header cells on page " + page);
            }
            for (Painted operator : tagged.painted()) {
                assertTrue(operator.artifact() ^ operator.tagged(), "nothing unmarked");
            }

            // the link back to the first page
            Node link = top.get(3).children().get(0);
            assertEquals("Link", link.type());
            assertEquals("Back to the first page", link.text());
            COSDictionary action = link.annotations().get(0).getCOSDictionary(COSName.A);
            COSArray structureDestination = action.getCOSArray(COSName.getPDFName("SD"));
            if (part == PdfUaPart.PDF_UA_2) {
                assertSame(top.get(0).element(), structureDestination.getObject(0), "leads to the heading at the top of page 1");
            } else {
                assertNull(structureDestination, "no structure destinations in PDF/UA-1");
            }
        }
    }
}
