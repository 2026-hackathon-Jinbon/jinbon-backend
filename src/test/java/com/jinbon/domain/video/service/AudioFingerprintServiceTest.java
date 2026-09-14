package com.jinbon.domain.video.service;

import com.jinbon.domain.video.dto.SegmentMatchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AudioFingerprintServiceTest {

    private final AudioFingerprintService service = new AudioFingerprintService();

    // ── 파싱 ──────────────────────────────────────────────

    @Test
    void parsesValidAudioFingerprint() {
        String fp = "aud-v1|5000000|1000|0000000000000001,0000000000000002,0000000000000003,0000000000000004,0000000000000005";
        var parsed = service.parse(fp);

        assertThat(parsed).isNotNull();
        assertThat(parsed.durationMicros()).isEqualTo(5_000_000L);
        assertThat(parsed.intervalMs()).isEqualTo(1000L);
        assertThat(parsed.hashes()).hasSize(5);
        assertThat(parsed.hashes().get(0)).isEqualTo(1L);
    }

    @Test
    void parseReturnsNullForNonAudioFormat() {
        assertThat(service.parse(null)).isNull();
        assertThat(service.parse("seg-v1|1000000|1000|abc")).isNull();
        assertThat(service.parse("aud-v1|")).isNull();
    }

    @Test
    void silentFlagsMarkZeroHashesAsSilent() {
        String fp = "aud-v1|3000000|1000|0000000000000000,0000000000000001,0000000000000000";
        var parsed = service.parse(fp);

        assertThat(parsed.silentFlags()).containsExactly(true, false, true);
    }

    // ── 동일 지문 비교 ───────────────────────────────────

    @Test
    void identicalFingerprintsHaveFullCoverage() {
        String fp = buildFingerprint(5, 1000,
                0x0000000000000000L, 0xFFFF000000000000L, 0x00000000FFFF0000L,
                0xFFFFFFFF00000000L, 0x00000000FFFFFFFFL);

        SegmentMatchResult result = service.compare(fp, fp);

        assertThat(result.coverage()).isEqualTo(1.0);
        assertThat(result.orderPreserved()).isTrue();
        assertThat(result.matchedSegments()).isEqualTo(5);
        assertThat(result.unmatchedRanges()).isEmpty();
        assertThat(result.bestOffsetMs()).isEqualTo(0);
    }

    // ── 쇼츠(원본 구간 추출) 비교 ────────────────────────

    @Test
    void shortsClipMatchesOriginalAudioSegment() {
        long h0 = 0x0000000000000000L;
        long h1 = 0xFFFF000000000000L;
        long h2 = 0x00000000FFFF0000L;
        long h3 = 0xFFFFFFFF00000000L;
        long h4 = 0x00000000FFFFFFFFL;
        long h5 = 0xFFFF0000FFFF0000L;
        long h6 = 0x0000FFFF0000FFFFL;
        long h7 = 0xFFFFFFFFFFFFFFFL;
        long h8 = 0x0F0F0F0F0F0F0F0FL;
        long h9 = 0xF0F0F0F0F0F0F0F0L;

        // 원본: 10초
        String original = buildFingerprint(10, 1000, h0, h1, h2, h3, h4, h5, h6, h7, h8, h9);
        // 쇼츠: 3초, 원본의 3~5번 구간
        String shorts = buildFingerprint(3, 1000, h3, h4, h5);

        SegmentMatchResult result = service.compare(shorts, original);

        assertThat(result.coverage()).isEqualTo(1.0);
        assertThat(result.orderPreserved()).isTrue();
        assertThat(result.bestOffsetMs()).isEqualTo(3000L);
        assertThat(result.unmatchedRanges()).isEmpty();
    }

    // ── 불일치 구간 검출 ──────────────────────────────────

    @Test
    void detectsUnmatchedGap() {
        long h0 = 0x0000000000000000L;
        long h1 = 0xFFFF000000000000L;
        long h2 = 0x00000000FFFF0000L;
        long h3 = 0xFFFFFFFF00000000L;
        long h4 = 0x00000000FFFFFFFFL;

        String original = buildFingerprint(5, 1000, h0, h1, h2, h3, h4);
        // 제출: 가운데(인덱스 2)가 완전히 다른 해시로 교체 (음성 교체 시뮬레이션)
        String submitted = buildFingerprint(5, 1000, h0, h1, 0xAAAAAAAAAAAAAAAAL, h3, h4);

        SegmentMatchResult result = service.compare(submitted, original);

        assertThat(result.matchedSegments()).isEqualTo(4);
        assertThat(result.coverage()).isEqualTo(0.8);
        assertThat(result.unmatchedRanges()).hasSize(1);
        assertThat(result.unmatchedRanges().get(0).startMs()).isEqualTo(2000L);
        assertThat(result.unmatchedRanges().get(0).endMs()).isEqualTo(3000L);
    }

    // ── 순서 역전 검출 ───────────────────────────────────

    @Test
    void detectsOrderReversal() {
        long a = 0x0000000000000000L;
        long b = 0xFFFF000000000000L;
        long c = 0x00000000FFFF0000L;
        long d = 0xFFFFFFFF00000000L;

        String original = buildFingerprint(4, 1000, a, b, c, d);
        String submitted = buildFingerprint(4, 1000, d, c, b, a);

        SegmentMatchResult result = service.compare(submitted, original);

        assertThat(result.coverage()).isLessThan(1.0);
    }

    // ── null 처리 ────────────────────────────────────────

    @Test
    void returnsUnavailableWhenEitherFingerprintIsNull() {
        String fp = buildFingerprint(3, 1000, 0x1, 0x2, 0x3);

        SegmentMatchResult result1 = service.compare(null, fp);
        SegmentMatchResult result2 = service.compare(fp, null);

        assertThat(result1.totalQuerySegments()).isEqualTo(0);
        assertThat(result2.totalRefSegments()).isEqualTo(0);
    }

    // ── 재인코딩 시뮬레이션 (유사 해시) ──────────────────

    @Test
    void reEncodedAudioWithSimilarHashesHasHighCoverage() {
        long[] origHashes = {0x1, 0x2, 0x3, 0x4, 0x5};
        long[] reEncHashes = new long[origHashes.length];
        for (int i = 0; i < origHashes.length; i++) {
            reEncHashes[i] = origHashes[i] ^ (1L << i); // 1비트 플립
        }

        String original = buildFingerprint(5, 1000, origHashes);
        String reEncoded = buildFingerprint(5, 1000, reEncHashes);

        SegmentMatchResult result = service.compare(reEncoded, original);

        assertThat(result.coverage()).isEqualTo(1.0);
        assertThat(result.orderPreserved()).isTrue();
        assertThat(result.unmatchedRanges()).isEmpty();
    }

    // ── 완전히 다른 음성 ─────────────────────────────────

    @Test
    void completelyDifferentAudioHasZeroCoverage() {
        String original = buildFingerprintFromSingle(5, 1000, 0x0L);
        String submitted = buildFingerprintFromSingle(5, 1000, 0xFFFFFFFFFFFFFFFFL);

        SegmentMatchResult result = service.compare(submitted, original);

        assertThat(result.coverage()).isEqualTo(0.0);
        assertThat(result.matchedSegments()).isEqualTo(0);
    }

    // ── 무음 세그먼트 카운트 ─────────────────────────────

    @Test
    void silentSegmentsAreCounted() {
        // 제출: 2개 무음(hash=0), 3개 유효
        String query = "aud-v1|5000000|1000|0000000000000000,0000000000000001,0000000000000000,0000000000000002,0000000000000003";
        String ref = "aud-v1|5000000|1000|0000000000000000,0000000000000001,0000000000000000,0000000000000002,0000000000000003";

        SegmentMatchResult result = service.compare(query, ref);

        assertThat(result.silentSegments()).isEqualTo(2);
        assertThat(result.coverage()).isEqualTo(1.0);
    }

    // ── 헬퍼 ─────────────────────────────────────────────

    private String buildFingerprint(int count, int intervalMs, long... hashes) {
        long durationMicros = (long) count * intervalMs * 1000;
        String hashStr = IntStream.range(0, hashes.length)
                .mapToObj(i -> String.format("%016x", hashes[i]))
                .collect(Collectors.joining(","));
        return "aud-v1|" + durationMicros + "|" + intervalMs + "|" + hashStr;
    }

    private String buildFingerprintFromSingle(int count, int intervalMs, long hash) {
        long[] hashes = new long[count];
        java.util.Arrays.fill(hashes, hash);
        return buildFingerprint(count, intervalMs, hashes);
    }
}
