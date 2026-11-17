package ro.ca2datavision.pdfua.postprocess;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;

import ro.ca2datavision.pdfua.layout.ArtifactInfo;
import ro.ca2datavision.pdfua.layout.Attributes;
import ro.ca2datavision.pdfua.layout.Box;
import ro.ca2datavision.pdfua.layout.ElementKind;
import ro.ca2datavision.pdfua.layout.LayoutElement;
import ro.ca2datavision.pdfua.layout.PageLayout;
import ro.ca2datavision.pdfua.layout.Roles;
import ro.ca2datavision.pdfua.postprocess.ContentScanner.Kind;
import ro.ca2datavision.pdfua.postprocess.ContentScanner.Op;
import ro.ca2datavision.pdfua.postprocess.ContentScanner.Unit;

/**
 * Tags one page.
 *
 * <ol>
 *   <li>Every painting unit of the content stream is matched, by position, with the layout
 *       element that produced it. Text and images of an element with a role become tagged
 *       content; everything else (lines, borders, backgrounds, page headers and footers,
 *       repeated logos) becomes an artifact. Nothing stays unmarked.</li>
 *   <li>The content stream is rewritten with the marked-content operators around the units.</li>
 *   <li>The structure elements are created in the reading order of the layout. Text covered by
 *       a link annotation goes into a Link element together with the annotation; signature
 *       placeholders become form fields inside Form elements.</li>
 * </ol>
 */
final class PageTagger {

    /** A heading found on the page, used for the bookmarks. */
    record Heading(int level, String title, PDPage page, float left, float top, COSDictionary element) {
    }

    /** Slack for rounding differences between the layout and the coordinates in the PDF. */
    private static final float TOLERANCE = 1.5f;

    /** Operators a marked-content sequence must not straddle. */
    private static final Set<String> BOUNDARIES = Set.of("BT", "ET", "q", "Q", "BMC", "BDC", "EMC");

    private sealed interface Target permits ContentTarget, ArtifactTarget {
    }

    /** Real content of a layout element; {@code link} is the annotation that covers it, if any. */
    private record ContentTarget(LayoutElement element, PDAnnotationLink link) implements Target {
    }

    private record ArtifactTarget(ArtifactInfo info) implements Target {
    }

    /** Consecutive units with the same target: one marked-content sequence. */
    private static final class Group {
        final Target target;
        final int start;
        int end;
        int mcid = -1;
        final StringBuilder text = new StringBuilder();
        Rectangle2D bounds;

        Group(Target target, Unit unit) {
            this.target = target;
            this.start = unit.start;
            add(unit);
        }

        void add(Unit unit) {
            end = unit.end;
            text.append(unit.text);
            if (unit.bounds() != null) {
                bounds = bounds == null ? unit.bounds() : bounds.createUnion(unit.bounds());
            }
        }
    }

    private final PDDocument document;
    private final PDPage page;
    private final PageLayout layout;
    private final StructureBuilder structure;
    private final SignatureFields signatureFields;
    private final List<Heading> headings;
    private final PostProcessingReport report;

    private final List<LayoutElement> allElements = new ArrayList<>();
    private final List<PDAnnotationLink> links = new ArrayList<>();
    /** Elements inside a frame that is an artifact: they are artifacts too, whatever their role. */
    private final Map<LayoutElement, ArtifactInfo> hiddenByFrame = new IdentityHashMap<>();
    private final Map<LayoutElement, List<Group>> contentOf = new IdentityHashMap<>();
    private final Map<PDAnnotationLink, Boolean> claimedLinks = new IdentityHashMap<>();
    /** Operator index -> space to show right after that operator (see restoreWordSpaces). */
    private final Map<Integer, byte[]> wordSpaces = new HashMap<>();

    PageTagger(PDDocument document, PDPage page, PageLayout layout, StructureBuilder structure,
               SignatureFields signatureFields, List<Heading> headings, PostProcessingReport report) {
        this.document = document;
        this.page = page;
        this.layout = layout;
        this.structure = structure;
        this.signatureFields = signatureFields;
        this.headings = headings;
        this.report = report;
    }

    void tag() throws IOException {
        flatten(layout.elements(), null);
        for (PDAnnotation annotation : page.getAnnotations()) {
            if (annotation instanceof PDAnnotationLink link) {
                links.add(link);
            } else {
                report.warn("Page " + layout.number() + ": " + annotation.getSubtype()
                        + " annotation is not handled and stays outside of the structure tree");
            }
        }

        ContentScanner scan = ContentScanner.scan(page);
        if (scan.ops().stream().anyMatch(op -> "BDC".equals(op.name()) || "BMC".equals(op.name()))) {
            report.warn("Page " + layout.number() + ": the content already contains marked content;"
                    + " the post-processor expects an untagged PDF and the result is probably wrong");
        }
        List<Group> groups = group(scan);
        writeContent(scan.ops(), groups);

        for (LayoutElement element : layout.elements()) {
            build(element, structure.document());
        }
        tagUnclaimedLinks();
    }

    private void flatten(List<LayoutElement> elements, ArtifactInfo hiddenBy) {
        for (LayoutElement element : elements) {
            allElements.add(element);
            if (hiddenBy != null) {
                hiddenByFrame.put(element, hiddenBy);
            }
            ArtifactInfo forChildren = hiddenBy;
            if (forChildren == null && element.kind() == ElementKind.FRAME && element.isArtifact()) {
                forChildren = element.artifact() != null ? element.artifact() : ArtifactInfo.LAYOUT;
            }
            flatten(element.children(), forChildren);
        }
    }

    // ---- step 1: decide what every painting unit is ----------------------------------------

    private List<Group> group(ContentScanner scan) {
        List<Unit> units = scan.units();
        List<Target> targets = new ArrayList<>(units.size());
        for (Unit unit : units) {
            targets.add(resolve(unit, targets.isEmpty() ? null : targets.get(targets.size() - 1)));
        }
        restoreWordSpaces(scan, targets);

        List<Group> groups = new ArrayList<>();
        Group current = null;
        for (int i = 0; i < units.size(); i++) {
            Unit unit = units.get(i);
            Target target = targets.get(i);
            if (current != null && same(current.target, target) && !boundaryBetween(scan.ops(), current.end, unit.start)) {
                current.add(unit);
                continue;
            }
            current = new Group(target, unit);
            groups.add(current);
        }

        int mcid = 0;
        for (Group group : groups) {
            if (group.target instanceof ContentTarget content) {
                group.mcid = mcid++;
                contentOf.computeIfAbsent(content.element(), e -> new ArrayList<>()).add(group);
                report.taggedContent++;
            } else {
                report.artifacts++;
            }
        }
        return groups;
    }

    /**
     * JasperReports drops the space at which it wraps a line, so the text of a paragraph reads
     * "...Data Services SRLprovides to..." for anything that does not guess word boundaries from
     * glyph positions. Wherever a line of an element ends without white space and the element
     * continues on another line, a space is shown at the end of the line. It is invisible and
     * does not move anything, because every line is positioned absolutely.
     */
    private void restoreWordSpaces(ContentScanner scan, List<Target> targets) {
        List<Unit> units = scan.units();
        Unit previous = null;
        LayoutElement previousElement = null;
        for (int i = 0; i < units.size(); i++) {
            Unit unit = units.get(i);
            if (unit.kind != Kind.TEXT || !(targets.get(i) instanceof ContentTarget content)) {
                continue;
            }
            if (previous != null && previousElement == content.element()
                    && startsNewLine(previous, unit) && needsSpaceBetween(previous.text, unit.text)) {
                byte[] space = scan.spaceCode(previous.font());
                if (space != null) {
                    wordSpaces.put(previous.end, space);
                    previous.text.append(' ');
                    report.wordSpaces++;
                } else {
                    report.warn("Page " + layout.number() + ": cannot restore the space after \"" + previous.text
                            + "\" because the font has no space on this page");
                }
            }
            previous = unit;
            previousElement = content.element();
        }
    }

    private static boolean startsNewLine(Unit previous, Unit next) {
        if (previous.textEnd() == null || next.textStart() == null) {
            return false;
        }
        return Math.abs(next.textStart().getY() - previous.textEnd().getY()) > 0.5
                || next.textStart().getX() < previous.textEnd().getX() - 0.5;
    }

    private static boolean needsSpaceBetween(CharSequence before, CharSequence after) {
        if (before.isEmpty() || after.isEmpty()) {
            return false;
        }
        char last = before.charAt(before.length() - 1);
        // a word broken at a hyphen continues on the next line
        boolean hyphen = last == '-' || last == '\u00AD' || last == '\u2010';
        return !Character.isWhitespace(last) && !hyphen && !Character.isWhitespace(after.charAt(0));
    }

    private Target resolve(Unit unit, Target previous) {
        Rectangle2D bounds = unit.bounds();
        if (bounds == null) {
            // nothing visible (for example an empty string): keep it with its neighbour
            return previous != null ? previous : new ArtifactTarget(ArtifactInfo.LAYOUT);
        }
        double x = bounds.getCenterX();
        double y = bounds.getCenterY();
        boolean paintsContent = unit.kind == Kind.TEXT || unit.kind == Kind.IMAGE || unit.kind == Kind.FORM;
        LayoutElement element = elementAt(unit, x, y);
        ArtifactInfo hiddenBy = element == null ? null : hiddenByFrame.get(element);

        if (paintsContent && element != null && hiddenBy == null && element.role() != null && !element.isArtifact()) {
            return new ContentTarget(element, linkAt(x, y));
        }
        if (paintsContent && element == null) {
            report.warn("Page " + layout.number() + ": " + unit.kind + " at (" + Math.round(x) + ", " + Math.round(y)
                    + ") is not covered by any layout element and was marked as an artifact"
                    + (unit.text.isEmpty() ? "" : ": \"" + unit.text + "\""));
        }
        // paths (lines, borders, backgrounds, underlines) never carry meaning in a report
        ArtifactInfo info = hiddenBy != null ? hiddenBy
                : element != null && element.artifact() != null ? element.artifact() : ArtifactInfo.LAYOUT;
        return new ArtifactTarget(info);
    }

    /**
     * The layout element at the given point that can have produced the unit. For images it is
     * the element whose box fits the painted area best: a watermark behind the whole page and a
     * picture in the middle of it both contain the centre of the page. For everything else it
     * is the smallest element at that point.
     */
    private LayoutElement elementAt(Unit unit, double x, double y) {
        boolean image = unit.kind == Kind.IMAGE || unit.kind == Kind.FORM;
        LayoutElement best = null;
        double bestScore = 0;
        for (LayoutElement element : allElements) {
            boolean compatible = switch (unit.kind) {
                case TEXT -> element.kind() == ElementKind.TEXT;
                case IMAGE, FORM -> element.kind() == ElementKind.IMAGE;
                case PATH, SHADING -> true;
            };
            if (!compatible || !element.box().contains(x, y, TOLERANCE)) {
                continue;
            }
            double score = image ? overlap(unit.bounds(), element.box()) : 1 / Math.max(element.box().area(), 0.01);
            if (best == null || score > bestScore) {
                best = element;
                bestScore = score;
            }
        }
        return best;
    }

    /** Intersection over union of the two rectangles: 1 for identical ones, 0 for disjoint ones. */
    private static double overlap(Rectangle2D area, Box box) {
        Rectangle2D other = new Rectangle2D.Double(box.llx(), box.lly(), box.width(), box.height());
        Rectangle2D intersection = area.createIntersection(other);
        if (intersection.getWidth() <= 0 || intersection.getHeight() <= 0) {
            return 0;
        }
        double common = intersection.getWidth() * intersection.getHeight();
        double union = area.getWidth() * area.getHeight() + other.getWidth() * other.getHeight() - common;
        return union <= 0 ? 0 : common / union;
    }

    private PDAnnotationLink linkAt(double x, double y) {
        PDAnnotationLink best = null;
        float bestArea = Float.MAX_VALUE;
        for (PDAnnotationLink link : links) {
            PDRectangle rect = link.getRectangle();
            if (rect == null) {
                continue;
            }
            boolean inside = x >= rect.getLowerLeftX() - TOLERANCE && x <= rect.getUpperRightX() + TOLERANCE
                    && y >= rect.getLowerLeftY() - TOLERANCE && y <= rect.getUpperRightY() + TOLERANCE;
            float area = rect.getWidth() * rect.getHeight();
            if (inside && area < bestArea) {
                best = link;
                bestArea = area;
            }
        }
        return best;
    }

    private static boolean same(Target a, Target b) {
        if (a instanceof ContentTarget x && b instanceof ContentTarget y) {
            return x.element() == y.element() && x.link() == y.link();
        }
        if (a instanceof ArtifactTarget x && b instanceof ArtifactTarget y) {
            return x.info().equals(y.info());
        }
        return false;
    }

    private static boolean boundaryBetween(List<Op> ops, int from, int to) {
        for (int i = from + 1; i < to; i++) {
            if (BOUNDARIES.contains(ops.get(i).name())) {
                return true;
            }
        }
        return false;
    }

    // ---- step 2: rewrite the content stream -----------------------------------------------------

    private void writeContent(List<Op> ops, List<Group> groups) throws IOException {
        Map<Integer, Group> starts = new HashMap<>();
        Map<Integer, Group> ends = new HashMap<>();
        for (Group group : groups) {
            starts.put(group.start, group);
            ends.put(group.end, group);
        }

        List<Object> tokens = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            Group group = starts.get(i);
            if (group != null) {
                beginMarkedContent(group, tokens);
            }
            tokens.addAll(ops.get(i).operands());
            tokens.add(ops.get(i).operator());
            byte[] space = wordSpaces.get(i);
            if (space != null) {
                tokens.add(new COSString(space));
                tokens.add(Operator.getOperator("Tj"));
            }
            if (ends.containsKey(i)) {
                tokens.add(Operator.getOperator("EMC"));
            }
        }

        PDStream stream = new PDStream(document);
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            new ContentStreamWriter(out).writeTokens(tokens);
        }
        page.setContents(stream);
    }

    private static void beginMarkedContent(Group group, List<Object> tokens) {
        COSDictionary properties = new COSDictionary();
        if (group.target instanceof ContentTarget content) {
            // /P <</MCID 3>> BDC ... EMC
            tokens.add(COSName.getPDFName(content.link() != null ? Roles.LINK : content.element().role()));
            properties.setInt(COSName.MCID, group.mcid);
        } else {
            // /Artifact <</Type /Pagination /Subtype /Header>> BDC ... EMC
            ArtifactInfo info = ((ArtifactTarget) group.target).info();
            tokens.add(COSName.ARTIFACT);
            properties.setName(COSName.TYPE, info.type());
            if (info.subtype() != null) {
                properties.setName(COSName.SUBTYPE, info.subtype());
            }
        }
        tokens.add(properties);
        tokens.add(Operator.getOperator("BDC"));
    }

    // ---- step 3: structure elements, in reading order -------------------------------------------

    private void build(LayoutElement element, COSDictionary parent) throws IOException {
        if (element.kind() == ElementKind.SIGNATURE) {
            buildSignature(element, parent);
        } else if (element.kind() == ElementKind.FRAME) {
            buildFrame(element, parent);
        } else if (element.role() != null && !element.isArtifact()) {
            buildLeaf(element, parent);
        }
    }

    private void buildFrame(LayoutElement frame, COSDictionary parent) throws IOException {
        if (frame.isArtifact()) {
            return;
        }
        if (frame.role() == null) {
            // a frame without a role only positions its children
            for (LayoutElement child : frame.children()) {
                build(child, parent);
            }
            return;
        }
        // A group with parts on several pages (a table that continues) is one structure element.
        COSDictionary container = frame.structureId() == null ? null : structure.shared(frame.structureId());
        boolean created = container == null;
        if (created) {
            container = structure.newElement(frame.role(), null);
            structure.applyAttributes(container, frame.attributes());
            if (structure.part() == PdfUaPart.PDF_UA_2 && Roles.L.equals(frame.role())
                    && !frame.attributes().containsKey(Attributes.LIST_NUMBERING) && hasLabels(frame)) {
                report.warn("Page " + layout.number() + ": list " + frame.id() + " has labels but no numbering;"
                        + " PDF/UA-2 requires ListNumbering for it (ISO 14289-2, 8.2.5.25):"
                        + " set pdfua.list.numbering in the template");
            }
            if (frame.structureId() != null) {
                structure.share(frame.structureId(), container);
            }
        }
        for (LayoutElement child : frame.children()) {
            build(child, container);
        }
        // An empty group is dropped, except table cells: a table needs all its cells.
        boolean kept = !created;
        if (created && (structure.hasKids(container) || Roles.isTableCell(frame.role()) || frame.structureId() != null)) {
            structure.append(parent, container);
            kept = true;
            if (Roles.TABLE.equals(frame.role())) {
                report.tables++;
            } else if (Roles.L.equals(frame.role())) {
                report.lists++;
            }
        }
        if (kept) {
            structure.register(frame.id(), container);
        }
    }

    private static boolean hasLabels(LayoutElement list) {
        return list.children().stream().anyMatch(item -> item.children().stream().anyMatch(part -> Roles.LBL.equals(part.role())));
    }

    private void buildSignature(LayoutElement placeholder, COSDictionary parent) throws IOException {
        PDAnnotationWidget widget = signatureFields.create(page, placeholder);
        COSDictionary form = structure.newElement(Roles.FORM, page);
        structure.append(parent, form);
        structure.register(placeholder.id(), form);
        structure.addAnnotation(form, page, widget);
        report.signatureFields++;
    }

    private void buildLeaf(LayoutElement element, COSDictionary parent) {
        List<Group> groups = contentOf.get(element);
        if (groups == null && Roles.isTableCell(element.role())) {
            // an empty cell paints nothing but still is a cell
            COSDictionary cell = structure.newElement(element.role(), page);
            structure.applyAttributes(cell, element.attributes());
            structure.append(parent, cell);
            structure.register(element.id(), cell);
            return;
        }
        if (groups == null) {
            if (element.kind() == ElementKind.IMAGE || (element.text() != null && !element.text().isBlank())) {
                report.warn("Page " + layout.number() + ": no content found for " + element.role() + " " + element.id());
            }
            return;
        }

        COSDictionary structElement = structure.newElement(element.role(), page);
        structure.applyAttributes(structElement, element.attributes());
        structure.append(parent, structElement);
        structure.register(element.id(), structElement);

        StringBuilder text = new StringBuilder();
        StringBuilder textOutsideLinks = new StringBuilder();
        Rectangle2D bounds = null;
        LinkRun run = null;
        for (Group group : groups) {
            text.append(group.text);
            if (group.bounds != null) {
                bounds = bounds == null ? group.bounds : bounds.createUnion(group.bounds);
            }
            PDAnnotationLink link = ((ContentTarget) group.target).link();
            if (link == null) {
                structure.addContent(structElement, page, group.mcid);
                textOutsideLinks.append(group.text);
                run = null;
                continue;
            }
            textOutsideLinks.append('\n');
            // A link that wraps to the next line consists of several annotations with the same
            // target: they share one Link element.
            String target = targetOf(link);
            String destination = destinationOf(link);
            if (run == null || !Objects.equals(run.destination, destination)) {
                run = new LinkRun(structure.newElement(Roles.LINK, page), target, destination);
                structure.append(structElement, run.element);
                report.links++;
            }
            structure.addContent(run.element, page, group.mcid);
            run.text.append(group.text);
            if (claimedLinks.put(link, Boolean.TRUE) == null) {
                structure.addAnnotation(run.element, page, link);
                run.annotations.add(link);
            }
            // every annotation of the run gets the description of the whole link
            String description = describeLink(run.target, run.text.toString());
            run.annotations.forEach(annotation -> annotation.setContents(description));
        }

        for (String contact : findContactDetails(textOutsideLinks)) {
            report.warn("Page " + layout.number() + ": \"" + contact + "\" in " + element.role() + " " + element.id()
                    + " looks like a web address, e-mail address or phone number but is not a link;"
                    + " wrap it in <a href=\"...\"> (https:, mailto: or tel:) in the template");
        }

        if (element.kind() == ElementKind.IMAGE) {
            if (element.alt() == null || element.alt().isBlank()) {
                report.warn("Page " + layout.number() + ": Figure " + element.id() + " has no alternate text");
            }
            structure.setAlternateDescription(structElement, element.alt());
            if (bounds != null) {
                structure.setBoundingBox(structElement, bounds);
            }
            report.figures++;
        }
        int level = Roles.headingLevel(element.role());
        if (level > 0) {
            String title = text.toString().isBlank() ? element.text() : text.toString().strip();
            headings.add(new Heading(level, title, page, element.box().llx(), element.box().ury(), structElement));
            report.headings++;
        }
    }

    private static final class LinkRun {
        final COSDictionary element;
        /** The address to mention in the description: the URI, or {@code null} for a link inside the document. */
        final String target;
        /** Identifies where the link leads; annotations with the same destination are one link. */
        final String destination;
        final StringBuilder text = new StringBuilder();
        final List<PDAnnotationLink> annotations = new ArrayList<>();

        LinkRun(COSDictionary element, String target, String destination) {
            this.element = element;
            this.target = target;
            this.destination = destination;
        }
    }

    /** PDF/UA wants every link annotation in a Link element, also when no text was found for it. */
    private void tagUnclaimedLinks() {
        for (PDAnnotationLink link : links) {
            if (!claimedLinks.containsKey(link)) {
                String target = targetOf(link);
                report.warn("Page " + layout.number() + ": link to " + (target == null ? "a place in the document" : target)
                        + " covers no tagged text; it was added to the structure tree without content");
                COSDictionary linkElement = structure.newElement(Roles.LINK, page);
                structure.append(structure.document(), linkElement);
                structure.addAnnotation(linkElement, page, link);
                String description = describeLink(target, "");
                link.setContents(description.isEmpty() ? "Link" : description);
                report.links++;
            }
        }
    }

    /** The URI of a link to the outside, {@code null} for a link to a place in the document. */
    private static String targetOf(PDAnnotationLink link) {
        return link.getAction() instanceof PDActionURI uri ? uri.getURI() : null;
    }

    private static String destinationOf(PDAnnotationLink link) {
        String uri = targetOf(link);
        if (uri != null) {
            return uri;
        }
        COSBase destination = link.getCOSObject().getDictionaryObject(COSName.DEST);
        if (destination == null) {
            destination = link.getCOSObject().getDictionaryObject(COSName.A);
        }
        return "internal:" + destination;
    }

    private static final Pattern CONTACT_DETAILS = Pattern.compile(
            "[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+"                        // e-mail address
            + "|\\b(?:https?://|www\\.)[^\\s,;]*[^\\s,;.!?)]"             // web address
            + "|(?<![\\w+])(?:\\+|00)\\d[\\d ()./-]{6,}\\d");           // phone number in international format

    /**
     * Web addresses, e-mail addresses and phone numbers in plain text. The report engine only
     * creates link annotations for text that the template marks as a hyperlink; anything found
     * here was left as ordinary text and cannot be activated by the reader.
     */
    static List<String> findContactDetails(CharSequence text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = CONTACT_DETAILS.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /**
     * Alternate description of a link annotation (/Contents, required by PDF/UA-1 7.18.5): the
     * visible link text, followed by the target when the text does not already show it.
     */
    static String describeLink(String target, String linkText) {
        String text = linkText.strip().replaceAll("\\s+", " ");
        if (target == null) {
            return text;
        }
        String address = target.replaceFirst("^(?i)(mailto:|tel:)", "");
        if (text.isEmpty()) {
            return address;
        }
        String compactText = text.replaceAll("[\\s().-]", "");
        String compactAddress = address.replaceFirst("^(?i)https?://", "").replaceAll("[\\s().-]", "");
        return compactText.contains(compactAddress) ? text : text + " (" + address + ")";
    }
}
