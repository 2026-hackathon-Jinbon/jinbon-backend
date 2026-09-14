package com.jinbon.domain.video.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PerceptualHashServiceTest {
    private final PerceptualHashService service = new PerceptualHashService();
    private static final String A = "0000000000000000";
    private static final String B = "ffffffffffffffff";

    @Test void matchingOrderedFramesAndLengthAreSimilar() {
        assertThat(service.compare("v2|1000000|" + A + "," + B,
                "v2|1040000|" + A + ",fffffffffffffffe").similar()).isTrue();
    }

    @Test void aSubsetIsOnlyPartialEvenWhenEveryInputFrameExistsInOriginal() {
        var comparison = service.compare("v2|500000|" + A, "v2|1000000|" + A + "," + B);
        assertThat(comparison.similar()).isFalse();
        assertThat(comparison.partial()).isTrue();
        assertThat(comparison.durationCompatible()).isFalse();
    }

    @Test void reorderedFramesAreNotSimilar() {
        var comparison = service.compare("v2|1000000|" + A + "," + B,
                "v2|1000000|" + B + "," + A);
        assertThat(comparison.similar()).isFalse();
        assertThat(comparison.partial()).isTrue();
    }

    @Test void aChangedSceneCannotHideInTheAverage() {
        String original = "v2|1000000|" + String.join(",", java.util.Collections.nCopies(16, A));
        String edited = "v2|1000000|" + String.join(",", java.util.Collections.nCopies(15, A)) + "," + B;
        var comparison = service.compare(original, edited);
        assertThat(comparison.meanDistance()).isEqualTo(4);
        assertThat(comparison.matchedRatio()).isGreaterThan(0.9);
        assertThat(comparison.similar()).isFalse();
    }

    @Test void legacyFingerprintsNeverClaimFullSimilarityWithoutDuration() {
        assertThat(service.compare(A + "," + B, "v2|1000000|" + A + "," + B).similar()).isFalse();
    }

    @Test void unrelatedFramesAreNotCandidates() {
        var comparison = service.compare("v2|1000000|" + A, "v2|1000000|" + B);
        assertThat(comparison.similar()).isFalse();
        assertThat(comparison.partial()).isFalse();
    }
}
