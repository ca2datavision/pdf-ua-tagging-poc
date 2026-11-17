package ro.ca2datavision.pdfua.jasper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JREmptyDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.pdf.JRPdfExporter;

/**
 * Plain JasperReports: compile a JRXML template, fill it, export it to PDF. Nothing here knows
 * about accessibility; the exported PDF is untagged.
 */
public final class JasperReportGenerator {

    /**
     * With the stock OpenPDF build, JasperReports logs "Stock OpenPDF detected, link annotation
     * /StructParent tagging is disabled" on every start. That only concerns its own (Professional)
     * tagger; here the post-processor links the annotations to the structure tree. The reference
     * is kept so that the setting is not garbage collected together with the logger.
     */
    private static final Logger OPENPDF_CHECK_LOGGER =
            Logger.getLogger("net.sf.jasperreports.pdf.classic.producer.StandardPdfUtils");

    static {
        OPENPDF_CHECK_LOGGER.setLevel(Level.SEVERE);
    }

    private JasperReportGenerator() {
    }

    /** Compiles the template found on the classpath and fills it with a single empty record. */
    public static JasperPrint fill(String templateResource, Map<String, Object> parameters) throws JRException, IOException {
        return fill(templateResource, parameters, new JREmptyDataSource(1));
    }

    /** Compiles the template found on the classpath and fills it from the given data source. */
    public static JasperPrint fill(String templateResource, Map<String, Object> parameters, JRDataSource dataSource)
            throws JRException, IOException {
        try (InputStream template = JasperReportGenerator.class.getClassLoader().getResourceAsStream(templateResource)) {
            if (template == null) {
                throw new IOException("Report template not found on the classpath: " + templateResource);
            }
            JasperReport report = JasperCompileManager.compileReport(template);
            return JasperFillManager.fillReport(report, new HashMap<>(parameters), dataSource);
        }
    }

    public static void exportPdf(JasperPrint print, Path target) throws JRException, IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        JRPdfExporter exporter = new JRPdfExporter();
        exporter.setExporterInput(new SimpleExporterInput(print));
        exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(target.toFile()));
        exporter.exportReport();
    }
}
