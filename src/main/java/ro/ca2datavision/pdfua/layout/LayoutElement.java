package ro.ca2datavision.pdfua.layout;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One element of a generated page together with its meaning.
 *
 * @param id        identifier used in log messages and in the layout file
 * @param kind      what the element draws
 * @param role      structure type (H1, P, Figure, ...), {@link Roles#ARTIFACT}, or {@code null}
 *                  for a frame that only positions its children
 * @param box       position on the page, in PDF user space
 * @param text      the text of a text element, for information only
 * @param alt       alternate text of a figure
 * @param artifact  artifact classification; set for artifacts and for everything that lives in
 *                  a page header or footer
 * @param signature set when the element is a signature field placeholder
 * @param anchor    the name under which links inside the document can point to the element
 * @param structureId elements with the same id are parts of one structure element: a table or
 *                  list that continues on the next page has one part per page
 * @param attributes further properties of the structure element, see {@link Attributes}
 * @param children  elements nested in a frame or group, in reading order
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record LayoutElement(
        String id,
        ElementKind kind,
        String role,
        Box box,
        String text,
        String alt,
        ArtifactInfo artifact,
        SignatureInfo signature,
        String anchor,
        String structureId,
        Map<String, String> attributes,
        List<LayoutElement> children) {

    public LayoutElement {
        // keep the order in which the attributes were found: Map.copyOf would iterate in an order
        // that changes from one JVM run to the next, and so would the PDF and the JSON
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        children = children == null ? List.of() : List.copyOf(children);
    }

    @JsonIgnore
    public boolean isArtifact() {
        return Roles.ARTIFACT.equals(role);
    }
}
