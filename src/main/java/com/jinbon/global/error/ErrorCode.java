package com.jinbon.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 비즈니스 에러 코드.
 *
 * 카테고리별 코드 접두사:
 * - C: 공통, A: 인증, M: 회원, V: 영상, VF: 검증, D: VC(Open DID)
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C001", "Internal server error."),
    UPLOAD_SIZE_EXCEEDED(HttpStatus.PAYLOAD_TOO_LARGE, "C002", "Upload size exceeds the 100MB limit."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "C003", "Invalid request."),

    // Auth
    ID_VERIFICATION_FAILED(HttpStatus.UNAUTHORIZED, "A001", "ID verification failed."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "A002", "Invalid refresh token."),
    NOT_A_REFRESH_TOKEN(HttpStatus.BAD_REQUEST, "A003", "Not a refresh token."),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "A004", "Expired or already used refresh token."),
    CI_NOT_FOUND(HttpStatus.BAD_REQUEST, "A005", "Failed to retrieve CI information."),
    NOT_A_SIGNUP_TOKEN(HttpStatus.BAD_REQUEST, "A006", "Not a signup token."),
    ID_VERIFICATION_PENDING(HttpStatus.CONFLICT, "A007", "ID verification is still in progress."),
    NOT_A_DID_REBIND_TOKEN(HttpStatus.UNAUTHORIZED, "A008", "Invalid or expired DID rebind token."),

    // Member
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "M001", "Member not found."),
    MEMBER_ALREADY_REGISTERED(HttpStatus.CONFLICT, "M002", "Member is already registered."),
    SIGNUP_NOT_COMPLETED(HttpStatus.CONFLICT, "M003", "DID registration is not completed."),
    MEMBER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "M004", "Member is not active."),
    DID_ALREADY_REGISTERED(HttpStatus.CONFLICT, "M005", "DID is already registered."),

    // Video
    ISSUER_ROLE_REQUIRED(HttpStatus.FORBIDDEN, "V001", "Only ISSUER role can register videos."),
    ISSUER_DID_NOT_REGISTERED(HttpStatus.BAD_REQUEST, "V002", "Issuer DID is not registered."),
    VIDEO_PROCESSING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "V003", "Failed to process video file."),
    VIDEO_ALREADY_REGISTERED(HttpStatus.CONFLICT, "V004", "The same video is registered by another account. Registration authority must be verified."),
    VIDEO_NOT_FOUND(HttpStatus.NOT_FOUND, "V005", "Video not found."),
    VIDEO_NOT_OWNED(HttpStatus.FORBIDDEN, "V006", "Not the owner of this video."),
    VIDEO_ALREADY_DEACTIVATED(HttpStatus.BAD_REQUEST, "V007", "Video is already deactivated."),
    BLOCKCHAIN_TX_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "V008", "Blockchain transaction failed."),
    SIGNATURE_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "V009", "Failed to generate signature."),

    // Verify
    BLOCKCHAIN_VERIFICATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "VF001", "Blockchain verification failed."),
    VIDEO_DOWNLOAD_FAILED(HttpStatus.BAD_REQUEST, "VF002", "Failed to download video from URL."),

    // VC (Open DID)
    VC_ISSUANCE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "D001", "Failed to issue verifiable credential."),
    VC_VERIFICATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "D002", "Failed to verify verifiable credential."),
    VC_REVOCATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "D003", "Failed to revoke verifiable credential."),
    VC_ISSUANCE_NOT_PREPARED(HttpStatus.BAD_REQUEST, "D004", "VC issuance is not in PENDING_WALLET state.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
