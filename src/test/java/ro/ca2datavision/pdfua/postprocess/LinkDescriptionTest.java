package ro.ca2datavision.pdfua.postprocess;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LinkDescriptionTest {

    @Test
    void addsTheTargetWhenTheLinkTextDoesNotShowIt() {
        assertEquals("Terms and conditions (https://www.example.com/terms)",
                PageTagger.describeLink("https://www.example.com/terms", " Terms and  conditions "));
    }

    @Test
    void keepsTheTextWhenItAlreadyIsTheAddress() {
        assertEquals("www.example.com/support",
                PageTagger.describeLink("https://www.example.com/support", "www.example.com/support"));
        assertEquals("support@example.com", PageTagger.describeLink("mailto:support@example.com", "support@example.com"));
    }

    @Test
    void recognisesAPhoneNumberWrittenWithSpaces() {
        assertEquals("+44 20 7946 0142", PageTagger.describeLink("tel:+442079460142", "+44 20 7946 0142"));
        assertEquals("Call us (+442079460142)", PageTagger.describeLink("tel:+442079460142", "Call us"));
    }

    @Test
    void fallsBackToTheTargetWithoutText() {
        assertEquals("support@example.com", PageTagger.describeLink("mailto:support@example.com", ""));
    }
}
