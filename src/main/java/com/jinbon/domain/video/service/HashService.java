package com.jinbon.domain.video.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 영상 파일 해시 생성 및 머클트리 구성 서비스.
 *
 * 이중 해시 구조:
 * - perceptualHash: 지각해시 (DCT 기반 pHash, 재인코딩 내성)
 * - fineHash: 전체 파일의 SHA-256 (정확한 동일성 검증용)
 * - merkleRoot: SHA-256(perceptualHash + fineHash)
 */
@Slf4j
@Service
public class HashService {

    /**
     * 영상 파일의 SHA-256 해시(fineHash)를 생성한다.
     */
    public String generateFineHash(InputStream inputStream) throws IOException {
        MessageDigest digest = getSha256();
        byte[] buffer = new byte[8192];
        int bytesRead;

        while ((bytesRead = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, bytesRead);
        }

        String fineHash = HexFormat.of().formatHex(digest.digest());
        log.debug("Fine hash generated - fineHash={}", fineHash.substring(0, 16) + "...");
        return fineHash;
    }

    /** perceptualHash와 fineHash로 머클 루트를 생성한다: SHA-256(perceptualHash + fineHash) */
    public String buildMerkleRoot(String perceptualHash, String fineHash) {
        MessageDigest digest = getSha256();
        digest.update(perceptualHash.getBytes());
        digest.update(fineHash.getBytes());
        String merkleRoot = HexFormat.of().formatHex(digest.digest());
        log.debug("Merkle root built - merkleRoot={}", merkleRoot.substring(0, 16) + "...");
        return merkleRoot;
    }

    /** 머클 패스를 JSON 형태로 생성한다 */
    public String buildMerklePath(String perceptualHash, String fineHash, String merkleRoot) {
        try {
            return new ObjectMapper().writeValueAsString(Map.of(
                    "leaves", List.of(perceptualHash, fineHash),
                    "root", merkleRoot
            ));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize merkle path", e);
        }
    }

    private MessageDigest getSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
