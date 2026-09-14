package com.jinbon.domain.video.service;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Random;
import static org.assertj.core.api.Assertions.assertThat;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4;

/** 실제 인코딩된 영상에서 프레임을 추출하여 변형 전후 판정을 검증한다. */
class VideoComparisonMediaTest {
    @TempDir Path directory;
    private final PerceptualHashService service = new PerceptualHashService();

    @Test void distinguishesEncodingChangesFromTimelineEditsOnTwoIndependentSources() throws Exception {
        for (int seed : new int[]{17, 83}) {
            String original = fingerprint(seed, "original", new int[]{0, 1, 2, 3}, 320, 2);
            String encoded = fingerprint(seed, "encoded", new int[]{0, 1, 2, 3}, 320, 8);
            String resized = fingerprint(seed, "resized", new int[]{0, 1, 2, 3}, 160, 4);
            String trimmed = fingerprint(seed, "trimmed", new int[]{0, 1}, 320, 2);
            String reordered = fingerprint(seed, "reordered", new int[]{3, 2, 1, 0}, 320, 2);
            String replaced = fingerprint(seed, "replaced", new int[]{0, 1, 4, 3}, 320, 2);
            String unrelated = fingerprint(seed + 1000, "unrelated", new int[]{0, 1, 2, 3}, 320, 2);
            check(seed, "encoding", original, encoded, true);
            check(seed, "resize", original, resized, true);
            check(seed, "trim", original, trimmed, false);
            check(seed, "reorder", original, reordered, false);
            check(seed, "replace", original, replaced, false);
            check(seed, "unrelated", original, unrelated, false);
            assertThat(service.compare(original, unrelated).partial()).isFalse();
        }
    }

    private void check(int seed, String variant, String original, String input, boolean similar) {
        var comparison = service.compare(input, original);
        System.out.printf("MEDIA seed=%d variant=%s mean=%.2f max=%d coverage=%.2f length=%s similar=%s partial=%s%n",
                seed, variant, comparison.meanDistance(), comparison.maxDistance(), comparison.matchedRatio(),
                comparison.durationCompatible(), comparison.similar(), comparison.partial());
        assertThat(comparison.similar()).as("seed=%s variant=%s", seed, variant).isEqualTo(similar);
    }

    private String fingerprint(int seed, String name, int[] scenes, int width, int quality) throws Exception {
        Path file = directory.resolve(seed + "-" + name + ".mp4");
        int height = width * 3 / 4;
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(file.toFile(), width, height);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            recorder.setVideoCodec(AV_CODEC_ID_MPEG4);
            recorder.setFrameRate(8);
            recorder.setVideoQuality(quality);
            recorder.start();
            for (int scene : scenes) {
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                Random random = new Random(seed * 31L + scene);
                for (int y = 0; y < 6; y++) {
                    for (int x = 0; x < 8; x++) {
                        int gray = random.nextInt(256);
                        graphics.setColor(new Color(gray, gray, gray));
                        graphics.fillRect(x * width / 8, y * height / 6, width / 8, height / 6);
                    }
                }
                graphics.dispose();
                for (int frame = 0; frame < 16; frame++) recorder.record(converter.convert(image));
            }
            recorder.stop();
        }
        return service.generateFingerprint(file);
    }
}
