package com.jinbon.domain.video.service;

import org.apache.catalina.core.ApplicationPart;
import org.apache.tomcat.util.http.fileupload.disk.DiskFileItem;
import org.apache.tomcat.util.http.fileupload.util.FileItemHeadersImpl;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.support.StandardMultipartHttpServletRequest;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;

class MediaFingerprintIntegrationTest {
    @TempDir Path temp;
    private final PerceptualHashService perceptual = new PerceptualHashService();
    private final VideoFingerprintService video = new VideoFingerprintService();
    private final AudioFingerprintService audio = new AudioFingerprintService();
    private final MediaFingerprintService media = new MediaFingerprintService(perceptual, video, audio);

    @Test void diskBackedUploadKeepsAllFingerprintsAndRemainsReadable() throws Exception {
        Path clip = record("original.mp4", 0);
        DiskFileItem item = new DiskFileItem("file", "video/mp4", false,
                "original.mp4", 0, temp.toFile());
        FileItemHeadersImpl headers = new FileItemHeadersImpl();
        headers.addHeader("Content-Disposition", "form-data; name=\"file\"; filename=\"original.mp4\"");
        item.setHeaders(headers);
        try (var output = item.getOutputStream()) {
            Files.copy(clip, output);
        }
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addPart(new ApplicationPart(item, temp.toFile()));
        var upload = new StandardMultipartHttpServletRequest(request).getFile("file");
        try {
            var expected = media.generate(clip);
            var actual = media.generate(upload);
            assertThat(actual.perceptual()).isEqualTo(expected.perceptual());
            assertThat(actual.segment()).isNotNull().isEqualTo(expected.segment());
            assertThat(actual.audio()).isNotNull().isEqualTo(expected.audio());
            assertThat(upload.getBytes()).isEqualTo(Files.readAllBytes(clip));
        } finally {
            item.delete();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {90, 180, 270})
    void displayRotationMatchesAnUprightReencodedCopy(int rotation) throws Exception {
        Path upright = record("upright.mp4", 0);
        Path camera = record("camera.mp4", rotation);
        try (var grabber = new FFmpegFrameGrabber(camera.toFile())) {
            grabber.start();
            assertThat(Math.floorMod((int) Math.round(grabber.getDisplayRotation()), 360))
                    .isEqualTo(rotation);
        }
        var reference = media.generate(camera);
        var query = media.generate(upright);
        var comparison = perceptual.compare(query.perceptual(), reference.perceptual());
        assertThat(comparison.similar()).as("%s", comparison).isTrue();
        assertThat(video.compare(query.segment(), reference.segment()).coverage()).isEqualTo(1.0);
        assertThat(audio.compare(query.audio(), reference.audio()).coverage()).isEqualTo(1.0);
    }

    private Path record(String name, int rotation) throws Exception {
        Path path = temp.resolve(name);
        int width = rotation % 180 == 0 ? 320 : 192;
        int height = rotation % 180 == 0 ? 192 : 320;
        try (var recorder = new FFmpegFrameRecorder(path.toFile(), width, height, 1);
             var converter = new Java2DFrameConverter()) {
            recorder.setVideoCodec(AV_CODEC_ID_H264);
            recorder.setAudioCodec(AV_CODEC_ID_AAC);
            recorder.setSampleRate(16000);
            recorder.setFrameRate(10);
            recorder.setDisplayRotation(rotation);
            recorder.start();
            for (int frame = 0; frame < 40; frame++) {
                BufferedImage image = new BufferedImage(320, 192, BufferedImage.TYPE_3BYTE_BGR);
                var g = image.createGraphics();
                g.scale(2, 2);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, 160, 96);
                g.setColor(Color.BLACK);
                g.fillRect(10 + frame, 8, 45, 25);
                g.setColor(Color.RED);
                g.fillOval(90, 45, 40, 45);
                g.dispose();
                for (int turn = 0; turn < rotation / 90; turn++) {
                    BufferedImage turned = new BufferedImage(image.getHeight(), image.getWidth(), BufferedImage.TYPE_3BYTE_BGR);
                    for (int y = 0; y < image.getHeight(); y++) {
                        for (int x = 0; x < image.getWidth(); x++) {
                            turned.setRGB(image.getHeight() - 1 - y, x, image.getRGB(x, y));
                        }
                    }
                    image = turned;
                }
                recorder.record(converter.convert(image));
                short[] samples = new short[1600];
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = (short) (10000 * Math.sin(2 * Math.PI * (300 + frame / 10 * 100)
                            * (frame * 1600 + i) / 16000));
                }
                recorder.recordSamples(16000, 1, ShortBuffer.wrap(samples));
            }
        }
        return path;
    }
}
