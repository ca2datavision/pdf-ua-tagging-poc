package ro.ca2datavision.pdfua;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.verapdf.pdfa.flavours.PDFAFlavour;

import ro.ca2datavision.pdfua.TaggedPdf.Node;
import ro.ca2datavision.pdfua.sign.PdfSigner;
import ro.ca2datavision.pdfua.sign.SigningIdentity;

/** The demo document after the customer's signature field has been signed. */
class SignedDocumentTest {

    private static DemoRun demo;
    private static byte[] unsignedBytes;
    private static byte[] signedBytes;

    @BeforeAll
    static void generate() throws Exception {
        demo = DemoRun.get();
        unsignedBytes = Files.readAllBytes(demo.result.pdfUa());
        signedBytes = Files.readAllBytes(demo.result.signedPdf());
    }

    private static PDSignatureField field(PDDocument document, String name) {
        return (PDSignatureField) document.getDocumentCatalog().getAcroForm(null).getField(name);
    }

    @Test
    void signedDocumentStillPassesPdfUa1Validation() {
        assertTrue(demo.signedValidation.compliant(), () -> "veraPDF PDF/UA-1: " + demo.signedValidation);
        assertEquals(List.of(), demo.signedValidation.failures());
    }

    @Test
    void signingOnlyAppendsToTheFile() {
        // an incremental update: the unsigned document is still there, byte for byte
        assertTrue(signedBytes.length > unsignedBytes.length);
        assertArrayEquals(unsignedBytes, Arrays.copyOf(signedBytes, unsignedBytes.length));
    }

    @Test
    void signatureIsValidAndCoversTheWholeFile() throws Exception {
        PDSignature signature = field(demo.signed, PdfUaPipeline.SIGNED_FIELD).getSignature();
        assertNotNull(signature, "the field is signed");
        assertEquals("Demo Customer", signature.getName());
        assertTrue(signature.getReason().contains("Service Agreement SA-2026-0042"));

        // everything except the signature value itself is signed
        int[] range = signature.getByteRange();
        assertEquals(0, range[0]);
        assertEquals(signedBytes.length, range[2] + range[3], "the signed bytes end at the end of the file");

        // the signature matches these bytes and the certificate inside it
        // read as a stream: the space reserved for the signature is padded with zeros after it
        CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(signature.getSignedContent(signedBytes)),
                new ByteArrayInputStream(signature.getContents(signedBytes)));
        SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();
        X509CertificateHolder certificate = (X509CertificateHolder) cms.getCertificates().getMatches(signer.getSID()).iterator().next();
        assertTrue(signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(certificate)), "signature verifies");
        assertTrue(certificate.getSubject().toString().contains("Demo Customer"));
    }

    @Test
    void signedFieldKeepsItsPlaceInTheStructureAndSaysThatItIsSigned() throws Exception {
        PDSignatureField signedField = field(demo.signed, PdfUaPipeline.SIGNED_FIELD);
        PDAnnotationWidget widget = signedField.getWidgets().get(0);
        assertEquals("Form", PdfUaPipelineTest.structParent(demo.signed, widget).getNameAsString(COSName.S));

        // what a screen reader announces: what the signature is for, and that it has been given
        String description = signedField.getAlternateFieldName();
        assertTrue(description.startsWith("Customer signature. By signing here you confirm"), description);
        assertTrue(description.contains("Signed by Demo Customer on "), description);

        // the tag tree is the one of the unsigned document
        TaggedPdf signedTags = new TaggedPdf(demo.signed);
        assertEquals(demo.tagged.readingOrder().stream().map(Node::type).toList(),
                signedTags.readingOrder().stream().map(Node::type).toList());
    }

    @Test
    void visibleSignatureUsesAnEmbeddedFont() throws Exception {
        PDAnnotationWidget widget = field(demo.signed, PdfUaPipeline.SIGNED_FIELD).getWidgets().get(0);
        PDResources resources = widget.getNormalAppearanceStream().getResources();
        int fonts = 0;
        for (COSName name : resources.getFontNames()) {
            PDFont font = resources.getFont(name);
            assertTrue(font.isEmbedded(), font.getName() + " is embedded");
            fonts++;
        }
        assertEquals(1, fonts, "the visible signature draws text");
    }

    @Test
    void otherFieldStaysEmptyAndCanBeSignedAfterwards(@TempDir Path directory) throws Exception {
        assertNull(field(demo.signed, "ProviderSignature").getSignature(), "the provider has not signed yet");

        Path both = directory.resolve("both-signed.pdf");
        PdfSigner.sign(demo.result.signedPdf(), both, "ProviderSignature", SigningIdentity.selfSigned("Demo Provider"),
                new PdfSigner.Wording("We will deliver the service", List.of("Signed by Demo Provider"), "Signed by Demo Provider."));

        byte[] bytes = Files.readAllBytes(both);
        assertArrayEquals(signedBytes, Arrays.copyOf(bytes, signedBytes.length), "the first signature is untouched");
        try (PDDocument document = Loader.loadPDF(both.toFile())) {
            assertEquals(2, document.getSignatureDictionaries().size());
            assertNotNull(field(document, "ProviderSignature").getSignature());
        }
        VeraPdf.Outcome outcome = VeraPdf.validate(both, PDFAFlavour.PDFUA_1);
        assertTrue(outcome.compliant(), outcome::toString);
    }

    @Test
    void fieldCannotBeSignedTwice(@TempDir Path directory) throws Exception {
        SigningIdentity someone = SigningIdentity.selfSigned("Someone Else");
        PdfSigner.Wording wording = new PdfSigner.Wording("again", List.of("again"), null);
        assertThrows(IllegalStateException.class, () -> PdfSigner.sign(
                demo.result.signedPdf(), directory.resolve("twice.pdf"), PdfUaPipeline.SIGNED_FIELD, someone, wording));
        assertThrows(IllegalArgumentException.class, () -> PdfSigner.sign(
                demo.result.signedPdf(), directory.resolve("missing.pdf"), "NoSuchField", someone, wording));
    }
}
