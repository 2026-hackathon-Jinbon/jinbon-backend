package com.jinbon.infra.blockchain;

import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;

import java.math.BigInteger;
import java.util.List;

/** JinBon 컨트랙트 조회 결과 ABI 디코더. */
public final class ContractDecoder {

    private ContractDecoder() {
    }

    public static VideoRecord decodeGetRecord(String encoded) {
        if (encoded == null || encoded.isBlank() || "0x".equals(encoded)) {
            throw new IllegalArgumentException("Empty getRecord response");
        }
        List<TypeReference<Type>> outputTypes = List.of(
                typeReference(Bool.class),
                typeReference(Bool.class),
                typeReference(Utf8String.class),
                typeReference(Uint256.class)
        );
        List<Type> values = FunctionReturnDecoder.decode(encoded, outputTypes);
        if (values.size() != 4) {
            throw new IllegalArgumentException("Invalid getRecord response");
        }
        return new VideoRecord(
                (Boolean) values.get(0).getValue(),
                (Boolean) values.get(1).getValue(),
                (String) values.get(2).getValue(),
                (BigInteger) values.get(3).getValue()
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static TypeReference<Type> typeReference(Class<? extends Type> type) {
        return (TypeReference) TypeReference.create(type);
    }

    public record VideoRecord(boolean registered, boolean active, String issuerDid,
                              BigInteger registeredAt) {
    }
}
