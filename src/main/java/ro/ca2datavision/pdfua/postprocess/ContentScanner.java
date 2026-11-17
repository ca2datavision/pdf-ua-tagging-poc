package ro.ca2datavision.pdfua.postprocess;

import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/**
 * Reads the content stream of a page and reports, for every operator that puts marks on the
 * page, <em>where</em> it paints. PDF/UA demands that each of these operators ends up either
 * in tagged content or in an artifact, so they are the units the tagger works with.
 *
 * <p>PDFBox interprets the stream (graphics state, text matrices, fonts); this class only
 * listens. Operators are recorded exactly as read, so the stream can be written back with
 * marked-content operators inserted around the units and nothing else changed.</p>
 */
final class ContentScanner extends PDFGraphicsStreamEngine {

    /** One operator of the page content stream with its operands. */
    record Op(Operator operator, List<COSBase> operands) {
        String name() {
            return operator.getName();
        }
    }

    enum Kind {
        /** A text showing operator (Tj, TJ, ', "). */
        TEXT,
        /** An image XObject or inline image. */
        IMAGE,
        /** A form XObject, for example a vector image. */
        FORM,
        /** A path: everything from its first construction operator to the painting operator. */
        PATH,
        /** A shading fill (sh). */
        SHADING
    }

    /** A run of operators {@code [start, end]} that paints one thing. */
    static final class Unit {
        final Kind kind;
        final int start;
        final int end;
        final StringBuilder text = new StringBuilder();
        private Rectangle2D bounds;
        /** Text units only: the font and the baseline points where the text starts and ends. */
        private PDFont font;
        private Point2D textStart;
        private Point2D textEnd;

        Unit(Kind kind, int start, int end) {
            this.kind = kind;
            this.start = start;
            this.end = end;
        }

        /** The painted area in page coordinates, or {@code null} when nothing visible was painted. */
        Rectangle2D bounds() {
            return bounds;
        }

        PDFont font() {
            return font;
        }

        Point2D textStart() {
            return textStart;
        }

        Point2D textEnd() {
            return textEnd;
        }

        void include(Rectangle2D area) {
            if (area != null) {
                bounds = bounds == null ? (Rectangle2D) area.clone() : bounds.createUnion(area);
            }
        }

        void include(Point2D point) {
            include(new Rectangle2D.Double(point.getX(), point.getY(), 0, 0));
        }
    }

    // Approximate vertical extent of a glyph in text space units (1 = font size).
    private static final float ASCENT = 0.8f;
    private static final float DESCENT = -0.2f;

    private final List<Op> ops = new ArrayList<>();
    private final List<Unit> units = new ArrayList<>();
    private final GeneralPath path = new GeneralPath();
    /** Per font dictionary: the bytes that show a space character with that font. */
    private final Map<COSDictionary, byte[]> spaceCodes = new IdentityHashMap<>();

    /** 0 outside of operator processing, 1 while a page level operator runs, more inside XObjects. */
    private int depth;
    private int opIndex = -1;
    private int pathStart = -1;
    /** Unit painted by the page level operator that is being processed. */
    private Unit current;

    private ContentScanner(PDPage page) {
        super(page);
    }

    static ContentScanner scan(PDPage page) throws IOException {
        ContentScanner scanner = new ContentScanner(page);
        scanner.processPage(page);
        return scanner;
    }

    List<Op> ops() {
        return ops;
    }

    /** The painting units in content stream order. */
    List<Unit> units() {
        return units;
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        if (depth > 0) {
            // operator of a form XObject, Type 3 glyph, ...: belongs to the page level operator
            depth++;
            try {
                super.processOperator(operator, operands);
            } finally {
                depth--;
            }
            return;
        }
        ops.add(new Op(operator, new ArrayList<>(operands)));
        opIndex = ops.size() - 1;
        current = null;
        depth = 1;
        try {
            super.processOperator(operator, operands);
        } finally {
            depth = 0;
        }
    }

    private Unit currentUnit(Kind kind) {
        if (current == null) {
            current = new Unit(kind, opIndex, opIndex);
            units.add(current);
        }
        return current;
    }

    /**
     * The character code of the space in the given font, as it has to be written in a text
     * showing operator, or {@code null} when the page never shows a space with that font.
     */
    byte[] spaceCode(PDFont font) {
        return font == null ? null : spaceCodes.get(font.getCOSObject());
    }

    // ---- text ---------------------------------------------------------------------------

    @Override
    protected void showText(byte[] string) throws IOException {
        PDFont font = getGraphicsState().getTextState().getFont();
        if (depth == 1 && font != null && !spaceCodes.containsKey(font.getCOSObject())) {
            ByteArrayInputStream in = new ByteArrayInputStream(string);
            while (in.available() > 0) {
                int offset = string.length - in.available();
                int code = font.readCode(in);
                if (" ".equals(font.toUnicode(code))) {
                    spaceCodes.put(font.getCOSObject(), Arrays.copyOfRange(string, offset, string.length - in.available()));
                    break;
                }
            }
        }
        super.showText(string);
    }

    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) {
        Unit unit = currentUnit(Kind.TEXT);
        float advance = displacement.getX();
        unit.include(textRenderingMatrix.transformPoint(0, DESCENT));
        unit.include(textRenderingMatrix.transformPoint(advance, DESCENT));
        unit.include(textRenderingMatrix.transformPoint(advance, ASCENT));
        unit.include(textRenderingMatrix.transformPoint(0, ASCENT));
        if (depth == 1) {
            unit.font = font;
            if (unit.textStart == null) {
                unit.textStart = textRenderingMatrix.transformPoint(0, 0);
            }
            unit.textEnd = textRenderingMatrix.transformPoint(advance, 0);
            String unicode = font.toUnicode(code);
            if (unicode != null) {
                unit.text.append(unicode);
            }
        }
    }

    // ---- images and forms ---------------------------------------------------------------

    @Override
    public void drawImage(PDImage image) {
        Unit unit = currentUnit(Kind.IMAGE);
        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
        unit.include(ctm.transformPoint(0, 0));
        unit.include(ctm.transformPoint(1, 0));
        unit.include(ctm.transformPoint(1, 1));
        unit.include(ctm.transformPoint(0, 1));
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
        includeForm(form);
        super.showForm(form);
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
        includeForm(form);
        super.showTransparencyGroup(form);
    }

    private void includeForm(PDFormXObject form) {
        Unit unit = currentUnit(Kind.FORM);
        PDRectangle box = form.getBBox();
        if (box != null) {
            Matrix matrix = form.getMatrix().multiply(getGraphicsState().getCurrentTransformationMatrix());
            unit.include(matrix.transformPoint(box.getLowerLeftX(), box.getLowerLeftY()));
            unit.include(matrix.transformPoint(box.getUpperRightX(), box.getLowerLeftY()));
            unit.include(matrix.transformPoint(box.getUpperRightX(), box.getUpperRightY()));
            unit.include(matrix.transformPoint(box.getLowerLeftX(), box.getUpperRightY()));
        }
    }

    // ---- paths --------------------------------------------------------------------------

    private void pathSegment() {
        if (depth == 1 && pathStart < 0) {
            pathStart = opIndex;
        }
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        pathSegment();
        path.moveTo(p0.getX(), p0.getY());
        path.lineTo(p1.getX(), p1.getY());
        path.lineTo(p2.getX(), p2.getY());
        path.lineTo(p3.getX(), p3.getY());
        path.closePath();
    }

    @Override
    public void moveTo(float x, float y) {
        pathSegment();
        path.moveTo(x, y);
    }

    @Override
    public void lineTo(float x, float y) {
        pathSegment();
        if (path.getCurrentPoint() == null) {
            path.moveTo(x, y);
        } else {
            path.lineTo(x, y);
        }
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        pathSegment();
        if (path.getCurrentPoint() == null) {
            path.moveTo(x3, y3);
        } else {
            path.curveTo(x1, y1, x2, y2, x3, y3);
        }
    }

    @Override
    public Point2D getCurrentPoint() {
        return path.getCurrentPoint();
    }

    @Override
    public void closePath() {
        if (path.getCurrentPoint() != null) {
            path.closePath();
        }
    }

    @Override
    public void clip(int windingRule) {
        // clipping does not paint
    }

    @Override
    public void endPath() {
        // "n": the path was only used for clipping
        path.reset();
        if (depth == 1) {
            pathStart = -1;
        }
    }

    @Override
    public void strokePath() {
        paintPath();
    }

    @Override
    public void fillPath(int windingRule) {
        paintPath();
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
        paintPath();
    }

    private void paintPath() {
        Rectangle2D area = path.getCurrentPoint() == null ? null : path.getBounds2D();
        if (depth == 1) {
            Unit unit = new Unit(Kind.PATH, pathStart >= 0 ? pathStart : opIndex, opIndex);
            unit.include(area);
            units.add(unit);
            pathStart = -1;
        } else if (current != null) {
            current.include(area);
        }
        path.reset();
    }

    @Override
    public void shadingFill(COSName shadingName) {
        if (depth == 1) {
            currentUnit(Kind.SHADING);
        }
    }
}
