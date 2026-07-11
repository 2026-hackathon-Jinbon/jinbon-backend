package com.jinbon.infra.blockchain;

import org.web3j.crypto.Hash;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * JinBon 스마트 컨트랙트용 Solidity ABI 인코딩 유틸리티.
 *
 * Solidity ABI 스펙에 따라 함수 호출을 인코딩한다:
 * - 4바이트 함수 셀렉터 (함수 시그니처의 keccak256 해시)
 * - 동적 타입(string)은 offset/length 방식으로 32바이트 정렬 인코딩
 */
public class ContractEncoder {

    private ContractEncoder() {
        // 유틸리티 클래스 - 인스턴스화 방지
    }

    /** register(string merkleRoot, string issuerDid, string signature) 셀렉터 */
    private static final String REGISTER_SELECTOR = functionSelector("register(string,string,string)");

    /** deactivate(string merkleRoot, string issuerDid) 셀렉터 */
    private static final String DEACTIVATE_SELECTOR = functionSelector("deactivate(string,string)");

    /** getRecord(string merkleRoot) 셀렉터 */
    private static final String GET_RECORD_SELECTOR = functionSelector("getRecord(string)");

    /** 영상 등록 트랜잭션 데이터 인코딩 */
    public static String encodeRegister(String merkleRoot, String issuerDid, String signature) {
        return REGISTER_SELECTOR + encodeDynamicParams(merkleRoot, issuerDid, signature);
    }

    /** 영상 비활성화 트랜잭션 데이터 인코딩 */
    public static String encodeDeactivate(String merkleRoot, String issuerDid) {
        return DEACTIVATE_SELECTOR + encodeDynamicParams(merkleRoot, issuerDid);
    }

    /** 영상 기록 조회 콜데이터 인코딩 */
    public static String encodeGetRecord(String merkleRoot) {
        return GET_RECORD_SELECTOR + encodeDynamicParams(merkleRoot);
    }

    /**
     * 가변 개수의 string 파라미터를 ABI 인코딩한다.
     *
     * 구조: [offset 섹션] + [data 섹션]
     * - offset 섹션: 각 파라미터의 데이터 위치를 32바이트로 기록
     * - data 섹션: 각 파라미터의 길이(32바이트) + 내용(32바이트 정렬 패딩)
     */
    private static String encodeDynamicParams(String... values) {
        StringBuilder sb = new StringBuilder();

        // offset 섹션: 각 파라미터가 data 섹션에서 시작하는 위치
        int baseOffset = values.length * 32;
        int currentOffset = baseOffset;
        for (String value : values) {
            sb.append(padLeft(Integer.toHexString(currentOffset), 64));
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            // 데이터 크기 = 32바이트(길이) + ceil(바이트길이 / 32) * 32
            currentOffset += 32 + ceilTo32(bytes.length);
        }

        // data 섹션: 길이 + 32바이트 정렬된 내용
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            sb.append(padLeft(Integer.toHexString(bytes.length), 64));
            sb.append(padRight(HexFormat.of().formatHex(bytes), ceilTo32(bytes.length) * 2));
        }

        return sb.toString();
    }

    /** 함수 시그니처에서 4바이트 셀렉터를 생성한다 (keccak256 해시의 앞 4바이트) */
    private static String functionSelector(String signature) {
        byte[] hash = keccak256(signature.getBytes(StandardCharsets.UTF_8));
        return "0x" + HexFormat.of().formatHex(hash, 0, 4);
    }

    private static String padLeft(String hex, int length) {
        return "0".repeat(Math.max(0, length - hex.length())) + hex;
    }

    private static String padRight(String hex, int length) {
        return hex + "0".repeat(Math.max(0, length - hex.length()));
    }

    /** 바이트 길이를 32의 배수로 올림한다 */
    private static int ceilTo32(int byteLength) {
        return ((byteLength + 31) / 32) * 32;
    }

    private static byte[] keccak256(byte[] input) {
        return Hash.sha3(input);
    }
}
