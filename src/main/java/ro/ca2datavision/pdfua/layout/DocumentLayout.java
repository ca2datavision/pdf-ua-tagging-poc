package ro.ca2datavision.pdfua.layout;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Everything the post-processor needs to know about a generated PDF that the PDF itself does
 * not say: the meaning (role) and position of every element, plus document level metadata.
 * It is the only link between the report engine and the post-processor, and it can be stored
 * next to the raw PDF as JSON (see {@link LayoutJson}).
 *
 * @param language BCP 47 language tag of the document, for example {@code en-GB}
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record DocumentLayout(String title, String author, String subject, String language, List<PageLayout> pages) {
}
