package ro.ca2datavision.pdfua;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.ValidationResult;

/**
 * Runs the veraPDF validator, the open source reference implementation of the machine-checkable
 * PDF/UA-1 requirements (Matterhorn Protocol), which are the same failure conditions PAC tests.
 */
final class VeraPdf {

    /** Outcome of a validation: the distinct failed rules, with one example location each. */
    record Outcome(boolean compliant, int checks, List<String> failures) {
        @Override
        public String toString() {
            return (compliant ? "compliant" : "NOT compliant") + ", " + checks + " checks"
                    + (failures.isEmpty() ? "" : System.lineSeparator() + String.join(System.lineSeparator(), failures));
        }
    }

    static {
        VeraGreenfieldFoundryProvider.initialise();
    }

    private VeraPdf() {
    }

    static Outcome validate(Path pdf, PDFAFlavour flavour) throws Exception {
        try (PDFAParser parser = Foundries.defaultInstance().createParser(pdf.toFile(), flavour);
             PDFAValidator validator = Foundries.defaultInstance().createValidator(flavour, false)) {
            ValidationResult result = validator.validate(parser);
            Set<String> rules = new LinkedHashSet<>();
            List<String> failures = new ArrayList<>();
            for (TestAssertion assertion : result.getTestAssertions()) {
                if (assertion.getStatus() != TestAssertion.Status.FAILED) {
                    continue;
                }
                String rule = assertion.getRuleId().getClause() + "-" + assertion.getRuleId().getTestNumber();
                if (rules.add(rule)) {
                    int occurrences = result.getFailedChecks().getOrDefault(assertion.getRuleId(), 1);
                    failures.add("  rule " + rule + " (" + occurrences + "x): " + assertion.getMessage()
                            + "  @ " + assertion.getLocation().getContext());
                }
            }
            return new Outcome(result.isCompliant(), result.getTotalAssertions(), failures);
        }
    }
}
