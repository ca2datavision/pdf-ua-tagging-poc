package ro.ca2datavision.pdfua.layout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Reads and writes a {@link DocumentLayout} as JSON. */
public final class LayoutJson {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private LayoutJson() {
    }

    public static void write(DocumentLayout layout, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        MAPPER.writeValue(target.toFile(), layout);
    }

    public static DocumentLayout read(Path source) throws IOException {
        return MAPPER.readValue(source.toFile(), DocumentLayout.class);
    }
}
