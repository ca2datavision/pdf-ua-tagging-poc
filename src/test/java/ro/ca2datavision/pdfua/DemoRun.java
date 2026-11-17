package ro.ca2datavision.pdfua;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.verapdf.pdfa.flavours.PDFAFlavour;

import ro.ca2datavision.pdfua.postprocess.PdfUaPart;

/**
 * One run of the whole flow for the demo report and one part of PDF/UA, shared by the test
 * classes: JasperReports export, post-processing, signing, and the veraPDF validation of the
 * three PDFs with the profile of that part. The generated files stay in {@code target/pdf}
 * (PDF/UA-1) and {@code target/pdf-ua2} (PDF/UA-2) for inspection, for example with PAC.
 */
final class DemoRun {

    private static final Map<PdfUaPart, DemoRun> RUNS = new EnumMap<>(PdfUaPart.class);

    final PdfUaPart part;
    final PdfUaPipeline.Result result;
    final PDDocument raw;
    final PDDocument pdfUa;
    final PDDocument signed;
    /** The post-processed (unsigned) document, read back independently of the code that wrote it. */
    final TaggedPdf tagged;
    final VeraPdf.Outcome rawValidation;
    final VeraPdf.Outcome pdfUaValidation;
    final VeraPdf.Outcome signedValidation;

    private DemoRun(PdfUaPart part) throws Exception {
        this.part = part;
        Path output = Path.of("target", part == PdfUaPart.PDF_UA_1 ? "pdf" : "pdf-ua" + part.part());
        result = PdfUaPipeline.run(PdfUaPipeline.DEMO_TEMPLATE, output, part);
        raw = Loader.loadPDF(result.rawPdf().toFile());
        pdfUa = Loader.loadPDF(result.pdfUa().toFile());
        signed = Loader.loadPDF(result.signedPdf().toFile());
        tagged = new TaggedPdf(pdfUa);

        // Validate all three files and keep the outcome next to them: before, after, and signed.
        PDFAFlavour flavour = part == PdfUaPart.PDF_UA_1 ? PDFAFlavour.PDFUA_1 : PDFAFlavour.PDFUA_2;
        rawValidation = VeraPdf.validate(result.rawPdf(), flavour);
        pdfUaValidation = VeraPdf.validate(result.pdfUa(), flavour);
        signedValidation = VeraPdf.validate(result.signedPdf(), flavour);
        String profile = part == PdfUaPart.PDF_UA_1
                ? "veraPDF, PDF/UA-1 (ISO 14289-1) validation profile"
                : "veraPDF, PDF/UA-2 (ISO 14289-2) and Tagged PDF (ISO 32005) validation profile";
        Files.writeString(output.resolve("04-verapdf-pdfua" + part.part() + "-report.txt"), String.join(System.lineSeparator(),
                profile,
                "",
                result.rawPdf().getFileName() + ": " + rawValidation,
                "",
                result.pdfUa().getFileName() + ": " + pdfUaValidation,
                "",
                result.signedPdf().getFileName() + ": " + signedValidation,
                ""), StandardCharsets.UTF_8);
    }

    /** The PDF/UA-1 run, the default. */
    static DemoRun get() throws Exception {
        return get(PdfUaPart.PDF_UA_1);
    }

    static synchronized DemoRun get(PdfUaPart part) throws Exception {
        DemoRun run = RUNS.get(part);
        if (run == null) {
            run = new DemoRun(part);
            RUNS.put(part, run);
        }
        return run;
    }
}
