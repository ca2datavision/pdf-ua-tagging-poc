package ro.ca2datavision.pdfua;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import net.sf.jasperreports.engine.JasperPrint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.verapdf.pdfa.flavours.PDFAFlavour;

import ro.ca2datavision.pdfua.jasper.JasperLayoutExtractor;
import ro.ca2datavision.pdfua.jasper.JasperReportGenerator;
import ro.ca2datavision.pdfua.postprocess.PdfUaPostProcessor;
import ro.ca2datavision.pdfua.postprocess.PostProcessingReport;

/**
 * Templates written for older JasperReports versions often ask for a tagged export. With the
 * default tagger factory of the Community Edition that request ends in "Tagged PDF export is
 * not supported"; jasperreports.properties selects the silent factory instead.
 */
class SilentTaggerTest {

    @Test
    void reportThatAsksJasperForTaggedPdfStillExportsAndEndsUpAsPdfUa(@TempDir Path directory) throws Exception {
        JasperPrint print = JasperReportGenerator.fill(PdfUaPipeline.DEMO_TEMPLATE, DemoData.parameters());
        print.setProperty("net.sf.jasperreports.export.pdf.tagged", "true");

        Path raw = directory.resolve("raw.pdf");
        assertDoesNotThrow(() -> JasperReportGenerator.exportPdf(print, raw));

        Path pdfUa = directory.resolve("pdfua.pdf");
        PostProcessingReport report = PdfUaPostProcessor.process(raw, JasperLayoutExtractor.extract(print), pdfUa);
        assertEquals(List.of(), report.warnings());

        VeraPdf.Outcome outcome = VeraPdf.validate(pdfUa, PDFAFlavour.PDFUA_1);
        assertTrue(outcome.compliant(), outcome::toString);
    }
}
