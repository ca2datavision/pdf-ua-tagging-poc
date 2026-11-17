package ro.ca2datavision.pdfua;

import java.nio.file.Path;

import ro.ca2datavision.pdfua.postprocess.PdfUaPart;

/**
 * Generates the demo document.
 *
 * <pre>
 * mvn compile exec:java                                    PDF/UA-1 into target/pdf
 * mvn compile exec:java -Dexec.args="--pdfua=2"            PDF/UA-2 into target/pdf-ua2
 * mvn compile exec:java -Dexec.args="--pdfua=2 some/dir"   PDF/UA-2 into some/dir
 * </pre>
 */
public final class PdfUaDemo {

    private PdfUaDemo() {
    }

    public static void main(String[] args) throws Exception {
        PdfUaPart part = PdfUaPart.PDF_UA_1;
        Path outputDirectory = null;
        for (String arg : args) {
            if (arg.startsWith("--pdfua=")) {
                part = PdfUaPart.of(Integer.parseInt(arg.substring("--pdfua=".length())));
            } else if (arg.startsWith("--")) {
                throw new IllegalArgumentException("Unknown option " + arg + "; known: --pdfua=1, --pdfua=2");
            } else {
                outputDirectory = Path.of(arg);
            }
        }
        if (outputDirectory == null) {
            outputDirectory = Path.of(part == PdfUaPart.PDF_UA_1 ? "target/pdf" : "target/pdf-ua" + part.part());
        }

        PdfUaPipeline.Result result = PdfUaPipeline.run(PdfUaPipeline.DEMO_TEMPLATE, outputDirectory, part);

        System.out.println("1. Raw JasperReports export (untagged): " + result.rawPdf().toAbsolutePath());
        System.out.println("   Layout extracted from the report:    " + result.layoutJson().toAbsolutePath());
        System.out.printf("2. Post-processed %s document:      %s%n", part, result.pdfUa().toAbsolutePath());
        System.out.println("   Structure tree as text:              " + result.structureDump().toAbsolutePath());
        System.out.println("3. Signed with a test certificate:      " + result.signedPdf().toAbsolutePath());
        System.out.println();
        System.out.println(result.report());
    }
}
