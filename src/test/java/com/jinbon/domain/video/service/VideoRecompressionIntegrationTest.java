package com.jinbon.domain.video.service;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;

class VideoRecompressionIntegrationTest {
    @TempDir Path temp;
    private final PerceptualHashService perceptual = new PerceptualHashService();
    private final VideoFingerprintService segments = new VideoFingerprintService();

    @Test
    void newSegmentFormatMatchesLegacyReferences() {
        String query = "seg-v3|1000000|1000|ffffffffffffffff:0000000000000000";
        String reference = "seg-v2|1000000|1000|0000000000000000";
        assertThat(segments.compare(query, reference).coverage()).isEqualTo(1.0);
        assertThat(segments.parse("seg-v3|1000000|1000|:")).isNull();
    }

    @ParameterizedTest
    @CsvSource({"180,320,30", "180,320,15", "136,240,30"})
    void recompressionAndSmallFramesPreserveContent(int width, int height, int fps) throws Exception {
        Path reference = recordReference();
        Path query = recompress(reference, width, height, fps, false);
        var candidate = perceptual.compare(perceptual.generateFingerprint(query),
                perceptual.generateFingerprint(reference));
        var match = segments.compare(segments.generate(query), segments.generate(reference));

        assertThat(candidate.similar()).as("candidate search: %s", candidate).isTrue();
        assertThat(match.coverage()).as("segment comparison: %s", match).isEqualTo(1.0);
        assertThat(match.bestOffsetMs()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"180,320,30", "136,240,15"})
    void replacingASectionStillFailsContentComparison(int width, int height, int fps) throws Exception {
        Path reference = recordReference();
        Path query = recompress(reference, width, height, fps, true);
        var match = segments.compare(segments.generate(query), segments.generate(reference));
        assertThat(match.coverage()).isLessThan(0.95);
        assertThat(match.unmatchedRanges()).isNotEmpty();
    }

    private Path recordReference() throws Exception {
        Path file = temp.resolve("reference.mp4");
        try (var recorder = recorder(file, 180, 320, 30, 0);
             var converter = new Java2DFrameConverter()) {
            recorder.start();
            for (int i = 0; i < 120; i++) {
                BufferedImage image = new BufferedImage(180, 320, BufferedImage.TYPE_3BYTE_BGR);
                var g = image.createGraphics();
                g.setColor(new Color(225, 235, 245));
                g.fillRect(0, 0, 180, 320);
                // 1.5초 장면 전환이 15fps 재압축에서 한 프레임 이동한다.
                if (i >= 45) {
                    g.translate(180, 0);
                    g.scale(-1, 1);
                }
                g.setColor(Color.DARK_GRAY);
                g.fillRect(12 + (i * 2) % 110, 15 + (i * 3) % 220, 48, 65);
                g.setColor(Color.BLUE);
                g.fillOval(15 + (i * 3) % 90, 130 + (i * 2) % 100, 60, 45);
                g.setColor(Color.RED);
                g.fillRect(135, 15, 20, 180);
                g.dispose();
                recorder.record(converter.convert(image));
            }
        }
        return file;
    }

    private Path recompress(Path source, int width, int height, int fps, boolean replace) throws Exception {
        Path file = temp.resolve("recompressed.mp4");
        try (var grabber = new FFmpegFrameGrabber(source.toFile());
             var recorder = recorder(file, width, height, fps, 3);
             var converter = new Java2DFrameConverter()) {
            grabber.start();
            recorder.start();
            int index = 0;
            org.bytedeco.javacv.Frame frame;
            while ((frame = grabber.grabImage()) != null) {
                int current = index++;
                if (fps == 15 && current % 2 != 0) continue;
                if (replace && current >= 30 && current < 60) {
                    recorder.record(converter.convert(new BufferedImage(180, 320, BufferedImage.TYPE_3BYTE_BGR)));
                } else {
                    recorder.record(frame);
                }
            }
        }
        return file;
    }

    private FFmpegFrameRecorder recorder(Path file, int width, int height, int fps, int bFrames) {
        var recorder = new FFmpegFrameRecorder(file.toFile(), width, height, 0);
        recorder.setVideoCodec(AV_CODEC_ID_H264);
        recorder.setPixelFormat(AV_PIX_FMT_YUV420P);
        recorder.setFrameRate(fps);
        recorder.setGopSize(fps * 2);
        recorder.setMaxBFrames(bFrames);
        recorder.setVideoOption("crf", "23");
        return recorder;
    }
}
