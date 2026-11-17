package ro.ca2datavision.pdfua.postprocess;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;

import ro.ca2datavision.pdfua.layout.Box;
import ro.ca2datavision.pdfua.layout.LayoutElement;
import ro.ca2datavision.pdfua.layout.SignatureInfo;

/**
 * Creates empty (unsigned) signature fields on top of the placeholders drawn by the report.
 *
 * <p>What makes such a field accessible:</p>
 * <ul>
 *   <li>the alternate field name (/TU) says what the signature is for; assistive technology
 *       announces it when the field gets focus (PDF/UA-1 7.18.4);</li>
 *   <li>the widget is referenced from a Form structure element, placed in the reading order
 *       right after the visible label (done by {@link PageTagger});</li>
 *   <li>the page uses the structure order as tab order (done by {@link PdfUaPostProcessor});</li>
 *   <li>for PDF/UA-2, the widget also carries the description in its Contents entry: the field
 *       has no Lbl structure element, and ISO 14289-2, 8.10.2.3 then asks for Contents.</li>
 * </ul>
 */
final class SignatureFields {

    private final PDDocument document;
    private final PdfUaPart part;
    private final Set<String> names = new HashSet<>();
    private PDAcroForm form;

    SignatureFields(PDDocument document, PdfUaPart part) {
        this.document = document;
        this.part = part;
    }

    PDAnnotationWidget create(PDPage page, LayoutElement placeholder) throws IOException {
        SignatureInfo info = placeholder.signature();
        if (!names.add(info.name())) {
            throw new IllegalStateException("Duplicate signature field name: " + info.name());
        }
        if (form == null) {
            form = document.getDocumentCatalog().getAcroForm();
            if (form == null) {
                form = new PDAcroForm(document);
                document.getDocumentCatalog().setAcroForm(form);
            }
        }

        PDSignatureField field = new PDSignatureField(form);
        field.setPartialName(info.name());
        field.setAlternateFieldName(info.description());

        Box box = placeholder.box();
        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(new PDRectangle(box.llx(), box.lly(), box.width(), box.height()));
        widget.setPage(page);
        widget.setPrinted(true);
        widget.setAppearance(emptyAppearance(box));
        if (part == PdfUaPart.PDF_UA_2) {
            widget.setContents(info.description());
        }

        List<PDAnnotation> annotations = page.getAnnotations();
        annotations.add(widget);
        page.setAnnotations(annotations);
        form.getFields().add(field);
        return widget;
    }

    /** The visible box is drawn by the report; the field itself shows nothing until it is signed. */
    private PDAppearanceDictionary emptyAppearance(Box box) throws IOException {
        PDAppearanceStream normal = new PDAppearanceStream(document);
        normal.setBBox(new PDRectangle(box.width(), box.height()));
        normal.setResources(new PDResources());
        try (OutputStream out = normal.getStream().createOutputStream()) {
            // no content
        }
        PDAppearanceDictionary appearance = new PDAppearanceDictionary();
        appearance.setNormalAppearance(normal);
        return appearance;
    }
}
