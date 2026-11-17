package ro.ca2datavision.pdfua.sign;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Calendar;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

/**
 * Signs one of the empty signature fields of a PDF/UA document, without breaking PDF/UA.
 *
 * <p>The signature is written as an incremental update: the bytes of the original file stay as
 * they are and the changes are appended, which is what keeps earlier signatures valid. Three
 * things are done for accessibility:</p>
 * <ul>
 *   <li>the existing field is signed. It already sits in a Form structure element, in the
 *       reading order after its label, and keeps that place;</li>
 *   <li>the visible signature uses an embedded font, as PDF/UA demands of every font, and is
 *       marked as an artifact: its text is not part of the tagged content, and</li>
 *   <li>what the visible signature shows is added to the description of the field, which is
 *       what a screen reader announces: to the alternate field name (TU), and for a PDF/UA-2
 *       document also to the Contents of the widget. A blind reader learns that the field is
 *       signed and by whom without depending on the signature panel of the viewer.</li>
 * </ul>
 */
public final class PdfSigner {

    /**
     * The words of a signature. Nothing is hard-wired, so they can be in the language of the document.
     *
     * @param reason       why the signer signs; stored in the signature
     * @param visibleLines the text drawn in the field, one entry per line
     * @param announcement sentence added to the description of the field, for example
     *                     "Signed by Jane Roe on 17 November 2026."
     */
    public record Wording(String reason, List<String> visibleLines, String announcement) {
    }

    /** Classpath location of the font drawn in the visible signature; any TrueType font works. */
    private static final String FONT = "net/sf/jasperreports/fonts/dejavu/DejaVuSans.ttf";
    private static final float FONT_SIZE = 8f;

    private PdfSigner() {
    }

    /**
     * @param pdf       the document with the empty field
     * @param target    where to write the signed document
     * @param fieldName name of the signature field to sign
     */
    public static void sign(Path pdf, Path target, String fieldName, SigningIdentity identity, Wording wording)
            throws IOException, GeneralSecurityException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile());
             SignatureOptions options = new SignatureOptions()) {
            // "null": do not let PDFBox add its default resources (a Helvetica that is not embedded)
            PDAcroForm form = document.getDocumentCatalog().getAcroForm(null);
            PDField field = form == null ? null : form.getField(fieldName);
            if (!(field instanceof PDSignatureField signatureField)) {
                throw new IllegalArgumentException("No signature field named " + fieldName);
            }
            if (signatureField.getSignature() != null) {
                throw new IllegalStateException("Field " + fieldName + " is already signed");
            }
            PDAnnotationWidget widget = signatureField.getWidgets().get(0);
            PDPage page = widget.getPage();

            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName(identity.name());
            signature.setReason(wording.reason());
            signature.setSignDate(Calendar.getInstance());
            // putting the signature into the field makes PDFBox sign this field instead of adding one
            signatureField.getCOSObject().setItem(COSName.V, signature);

            if (wording.announcement() != null) {
                signatureField.setAlternateFieldName(append(signatureField.getAlternateFieldName(), wording.announcement()));
                // a PDF/UA-2 widget is also described by its Contents (ISO 14289-2, 8.10.2.3)
                if (widget.getContents() != null) {
                    widget.setContents(append(widget.getContents(), wording.announcement()));
                }
            }

            options.setPreferredSignatureSize(SignatureOptions.DEFAULT_SIGNATURE_SIZE * 2);
            options.setVisualSignature(visibleSignature(page.getMediaBox(), widget.getRectangle(), wording.visibleLines()));
            options.setPage(document.getPages().indexOf(page));
            document.addSignature(signature, content -> signatureContainer(content, identity), options);

            Files.createDirectories(target.toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(target)) {
                document.saveIncremental(out);
            }
        }
    }

    private static String append(String description, String sentence) {
        return description == null || description.isBlank() ? sentence : description + " " + sentence;
    }

    /**
     * A one-page PDF that only holds a signature field with the appearance to use; PDFBox copies
     * the appearance from it. Building it as a document of its own lets PDFBox embed just the
     * glyphs that are used, which it cannot do in an incremental update.
     */
    private static InputStream visibleSignature(PDRectangle pageSize, PDRectangle fieldArea, List<String> lines) throws IOException {
        try (PDDocument template = new PDDocument();
             InputStream fontData = PdfSigner.class.getClassLoader().getResourceAsStream(FONT)) {
            if (fontData == null) {
                throw new IOException("Font for the visible signature not found on the classpath: " + FONT);
            }
            PDPage page = new PDPage(pageSize);
            template.addPage(page);
            PDFont font = PDType0Font.load(template, fontData);

            PDAcroForm form = new PDAcroForm(template);
            template.getDocumentCatalog().setAcroForm(form);
            PDSignatureField field = new PDSignatureField(form);
            form.getFields().add(field);
            PDAnnotationWidget widget = field.getWidgets().get(0);
            widget.setRectangle(fieldArea);
            widget.setPage(page);
            page.getAnnotations().add(widget);

            PDAppearanceStream appearance = new PDAppearanceStream(template);
            appearance.setBBox(new PDRectangle(fieldArea.getWidth(), fieldArea.getHeight()));
            PDResources resources = new PDResources();
            appearance.setResources(resources);
            try (PDPageContentStream content = new PDPageContentStream(template, appearance)) {
                // decoration as far as the tag tree is concerned: the field description carries the meaning
                content.beginMarkedContent(COSName.ARTIFACT);
                content.beginText();
                content.setFont(font, FONT_SIZE);
                content.setLeading(FONT_SIZE * 1.35f);
                content.newLineAtOffset(6, fieldArea.getHeight() - FONT_SIZE - 6);
                for (String line : lines) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
                content.endMarkedContent();
            }
            PDAppearanceDictionary appearances = new PDAppearanceDictionary();
            appearances.getCOSObject().setDirect(true);
            appearances.setNormalAppearance(appearance);
            widget.setAppearance(appearances);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            template.save(bytes);
            return new ByteArrayInputStream(bytes.toByteArray());
        }
    }

    /** The detached CMS (PKCS#7) signature over the bytes of the document. */
    private static byte[] signatureContainer(InputStream content, SigningIdentity identity) throws IOException {
        try {
            CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
            generator.addSignerInfoGenerator(
                    new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                            .build(new JcaContentSignerBuilder("SHA256withRSA").build(identity.key()), identity.chain().get(0)));
            generator.addCertificates(new JcaCertStore(identity.chain()));
            return generator.generate(new CMSProcessableByteArray(content.readAllBytes()), false).getEncoded();
        } catch (GeneralSecurityException | CMSException | OperatorCreationException e) {
            throw new IOException("Cannot create the signature", e);
        }
    }
}
