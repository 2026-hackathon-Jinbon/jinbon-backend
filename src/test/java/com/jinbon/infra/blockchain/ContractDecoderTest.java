package com.jinbon.infra.blockchain;

import org.junit.jupiter.api.Test;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractDecoderTest {

    @Test
    void decodesRegisteredVideoRecord() {
        String encoded = FunctionEncoder.encodeConstructor(List.of(
                new Bool(true), new Bool(true), new Utf8String("did:omn:issuer"),
                new Uint256(BigInteger.valueOf(1234))));

        ContractDecoder.VideoRecord record = ContractDecoder.decodeGetRecord(encoded);

        assertThat(record.registered()).isTrue();
        assertThat(record.active()).isTrue();
        assertThat(record.issuerDid()).isEqualTo("did:omn:issuer");
        assertThat(record.registeredAt()).isEqualTo(BigInteger.valueOf(1234));
    }

    @Test
    void decodesSolidityDefaultRecordAsUnregistered() {
        String encoded = FunctionEncoder.encodeConstructor(List.of(
                new Bool(false), new Bool(false), new Utf8String(""), new Uint256(BigInteger.ZERO)));

        assertThat(ContractDecoder.decodeGetRecord(encoded).registered()).isFalse();
    }

    @Test
    void rejectsEmptyResponse() {
        assertThatThrownBy(() -> ContractDecoder.decodeGetRecord("0x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
