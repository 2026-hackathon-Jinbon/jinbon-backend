package com.jinbon.domain.video.service;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.image.BufferedImage;
import java.nio.ShortBuffer;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_PCM_S16LE;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;

class AudioPaddingIntegrationTest {
    @TempDir Path temp;

    @ParameterizedTest
    @CsvSource({"300,true", "315,false"})
    void toleratesOneFrameOfContainerPaddingButNotAnAppendedSecond(int frames, boolean matches) throws Exception {
        // 19.968s audio; 31.25fps represents that duration exactly.
        Path reference = record("reference.mov", 31.25, 624);
        Path query = record("query.mov", 15, frames);
        var service = new AudioFingerprintService();
        var match = service.compare(service.generate(query), service.generate(reference));
        assertThat(match.coverage() == 1.0).as("%s", match).isEqualTo(matches);
    }

    private Path record(String name, double fps, int frames) throws Exception {
        Path path = temp.resolve(name);
        int totalSamples = 319488; // 19.968 * 16000
        try (var recorder = new FFmpegFrameRecorder(path.toFile(), 64, 64, 1);
             var converter = new Java2DFrameConverter()) {
            recorder.setVideoCodec(AV_CODEC_ID_H264);
            recorder.setAudioCodec(AV_CODEC_ID_PCM_S16LE);
            recorder.setSampleRate(16000);
            recorder.setFrameRate(fps);
            recorder.start();
            var image = new BufferedImage(64, 64, BufferedImage.TYPE_3BYTE_BGR);
            int written = 0;
            for (int i = 0; i < frames; i++) {
                recorder.record(converter.convert(image));
                int end = Math.min(totalSamples, (int) Math.round((i + 1) * 16000 / fps));
                if (end > written) {
                    short[] samples = new short[end - written];
                    for (int n = 0; n < samples.length; n++) {
                        int index = written + n;
                        samples[n] = (short) (10000 * Math.sin(2 * Math.PI
                                * (300 + index / 16000 * 80) * index / 16000));
                    }
                    recorder.recordSamples(16000, 1, ShortBuffer.wrap(samples));
                    written = end;
                }
            }
        }
        try (var grabber = new org.bytedeco.javacv.FFmpegFrameGrabber(path.toFile())) {
            grabber.start();
            var stream = grabber.getFormatContext().streams(grabber.getAudioStream());
            long duration = Math.round(stream.duration() * 1_000_000.0
                    * stream.time_base().num() / stream.time_base().den());
            assertThat(duration).as("fixture audio must be exactly 19.968 seconds").isEqualTo(19_968_000);
        }
        return path;
    }
}
