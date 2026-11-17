package ro.ca2datavision.pdfua.postprocess;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Contact details that the template did not turn into links are reported, not silently accepted. */
class ContactDetailsTest {

    @Test
    void findsEmailAddressesWebAddressesAndPhoneNumbers() {
        assertEquals(List.of("support@example.com", "www.example.com/support", "+44 20 7946 0142", "https://example.com/a?b=1"),
                PageTagger.findContactDetails(
                        "Write to support@example.com, see www.example.com/support. Call +44 20 7946 0142 (or visit https://example.com/a?b=1)."));
    }

    @Test
    void ignoresOrdinaryTextAndNumbers() {
        assertEquals(List.of(), PageTagger.findContactDetails(
                "Service Agreement SA-2026-0042 was signed on 17.11.2026 between 09:00 and 17:00; availability 99.50 percent, 1 + 2 = 3."));
    }
}
