package ro.ca2datavision.pdfua;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperPrint;

import ro.ca2datavision.pdfua.jasper.JasperLayoutExtractor;
import ro.ca2datavision.pdfua.jasper.JasperReportGenerator;
import ro.ca2datavision.pdfua.layout.DocumentLayout;
import ro.ca2datavision.pdfua.layout.LayoutJson;
import ro.ca2datavision.pdfua.postprocess.PdfUaPart;
import ro.ca2datavision.pdfua.postprocess.PdfUaPostProcessor;
import ro.ca2datavision.pdfua.postprocess.PostProcessingReport;
import ro.ca2datavision.pdfua.postprocess.StructureDump;
import ro.ca2datavision.pdfua.sign.PdfSigner;
import ro.ca2datavision.pdfua.sign.SigningIdentity;

/**
 * The whole flow, with every intermediate result kept on disk:
 *
 * <pre>
 * JRXML template
 *    | JasperReports (Community Edition, no tagging)
 *    v
 * 01-jasper-raw.pdf        untagged PDF, exactly as exported by JasperReports
 * 01-jasper-layout.json    role, position and nesting of every report element (from the JasperPrint)
 *    | PdfUaPostProcessor (PDFBox)
 *    v
 * 02-pdfua.pdf             tagged PDF/UA-1 document       (02-pdfua2.pdf for PDF/UA-2)
 * 02-pdfua-structure.txt   its structure tree and artifacts as readable text
 *    | PdfSigner (PDFBox + BouncyCastle), with a throw-away test certificate
 *    v
 * 03-pdfua-signed.pdf      the same document with the customer's signature field signed
 * </pre>
 *
 * <p>The names of the files after step 2 say which part of PDF/UA they conform to: "pdfua" for
 * PDF/UA-1, as before PDF/UA-2 was supported, and "pdfua2" for PDF/UA-2.</p>
 */
public final class PdfUaPipeline {

    public static final String DEMO_TEMPLATE = "reports/accessible-demo.jrxml";

    /** The files written by one run. */
    public record Result(PdfUaPart part, Path rawPdf, Path layoutJson, Path pdfUa, Path structureDump, Path signedPdf,
                         PostProcessingReport report) {
    }

    /** Name of the signature field that the demo signs; the other one stays empty. */
    public static final String SIGNED_FIELD = "CustomerSignature";

    private PdfUaPipeline() {
    }

    /** Runs the flow for PDF/UA-1. */
    public static Result run(String templateResource, Path outputDirectory)
            throws JRException, IOException, GeneralSecurityException {
        return run(templateResource, outputDirectory, PdfUaPart.PDF_UA_1);
    }

    /** Runs the flow for the given part of PDF/UA. */
    public static Result run(String templateResource, Path outputDirectory, PdfUaPart part)
            throws JRException, IOException, GeneralSecurityException {
        String name = fileName(part);
        Files.createDirectories(outputDirectory);
        Path rawPdf = outputDirectory.resolve("01-jasper-raw.pdf");
        Path layoutJson = outputDirectory.resolve("01-jasper-layout.json");
        Path pdfUa = outputDirectory.resolve("02-" + name + ".pdf");
        Path structureDump = outputDirectory.resolve("02-" + name + "-structure.txt");
        Path signedPdf = outputDirectory.resolve("03-" + name + "-signed.pdf");

        // 1. JasperReports: fill the template and export a plain PDF
        JasperPrint print = JasperReportGenerator.fill(templateResource, DemoData.parameters());
        JasperReportGenerator.exportPdf(print, rawPdf);

        // 2. Keep what the PDF does not know: what the elements mean and where they are
        DocumentLayout layout = JasperLayoutExtractor.extract(print);
        LayoutJson.write(layout, layoutJson);

        // 3. Post-process: tags, artifacts, links, signature fields, PDF/UA metadata
        PostProcessingReport report = PdfUaPostProcessor.process(rawPdf, layout, pdfUa, part);

        // 4. Human readable view of the result
        Files.writeString(structureDump, StructureDump.dump(pdfUa), StandardCharsets.UTF_8);

        // 5. Sign one of the two signature fields, as the customer would
        SigningIdentity customer = SigningIdentity.selfSigned("Demo Customer");
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK));
        PdfSigner.sign(pdfUa, signedPdf, SIGNED_FIELD, customer, new PdfSigner.Wording(
                "I have read the summary and accept Service Agreement SA-2026-0042",
                List.of("Signed by " + customer.name(), "on " + date, "with a test certificate"),
                "Signed by " + customer.name() + " on " + date + "."));

        return new Result(part, rawPdf, layoutJson, pdfUa, structureDump, signedPdf, report);
    }

    /** "pdfua" for PDF/UA-1, the names used before PDF/UA-2 was supported, and "pdfua2" for PDF/UA-2. */
    public static String fileName(PdfUaPart part) {
        return part == PdfUaPart.PDF_UA_1 ? "pdfua" : "pdfua" + part.part();
    }
}
