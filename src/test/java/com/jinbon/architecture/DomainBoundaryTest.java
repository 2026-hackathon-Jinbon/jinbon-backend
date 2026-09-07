package com.jinbon.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DomainBoundaryTest {

    @Test
    void domainDoesNotImportInfrastructureImplementations() throws IOException {
        Path domainRoot = Path.of("src/main/java/com/jinbon/domain");
        List<String> violations;
        try (var files = Files.walk(domainRoot)) {
            violations = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> lines(path).stream()
                            .filter(line -> line.startsWith("import com.jinbon.infra."))
                            .map(line -> path + ": " + line))
                    .toList();
        }

        assertThat(violations).isEmpty();
    }

    private List<String> lines(Path path) {
        try {
            return Files.readAllLines(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect " + path, e);
        }
    }
}
