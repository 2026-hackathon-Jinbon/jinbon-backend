package com.jinbon.domain.video.port;

/** 영상 등록에 사용하는 블록체인 경계. ABI와 RPC 세부사항은 구현체가 담당한다. */
public interface VideoLedgerPort {

    Registration register(String merkleRoot, String issuerDid, String signature);

    void deactivate(String merkleRoot, String issuerDid);

    Record getRecord(String merkleRoot);

    String getChainId();

    record Registration(String transactionHash, String blockNumber) {}

    record Record(boolean registered, boolean active, String issuerDid, String signature) {}
}
