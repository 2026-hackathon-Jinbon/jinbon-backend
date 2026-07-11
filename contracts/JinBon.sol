// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * @title JinBon - Video Authenticity Registry
 * @notice Stores and manages video authenticity records on OmniOne Chain (BESU)
 */
contract JinBon {

    struct VideoRecord {
        string issuerDid;
        string signature;
        bool active;
        uint256 registeredAt;
        uint256 deactivatedAt;
    }

    // merkleRoot => VideoRecord
    mapping(string => VideoRecord) private records;

    // merkleRoot => exists
    mapping(string => bool) private registered;

    event VideoRegistered(string indexed merkleRoot, string issuerDid, uint256 timestamp);
    event VideoDeactivated(string indexed merkleRoot, string issuerDid, uint256 timestamp);

    /**
     * @notice Register a new video record
     * @param merkleRoot Merkle root hash of the video
     * @param issuerDid DID of the issuer
     * @param signature Digital signature (issuerDid + merkleRoot)
     */
    function register(
        string calldata merkleRoot,
        string calldata issuerDid,
        string calldata signature
    ) external {
        require(!registered[merkleRoot], "Already registered");

        records[merkleRoot] = VideoRecord({
            issuerDid: issuerDid,
            signature: signature,
            active: true,
            registeredAt: block.timestamp,
            deactivatedAt: 0
        });
        registered[merkleRoot] = true;

        emit VideoRegistered(merkleRoot, issuerDid, block.timestamp);
    }

    /**
     * @notice Deactivate a video record
     * @param merkleRoot Merkle root hash of the video
     * @param issuerDid DID of the issuer (must match the original registrant)
     */
    function deactivate(
        string calldata merkleRoot,
        string calldata issuerDid
    ) external {
        require(registered[merkleRoot], "Not registered");
        require(records[merkleRoot].active, "Already deactivated");
        require(
            keccak256(bytes(records[merkleRoot].issuerDid)) == keccak256(bytes(issuerDid)),
            "Not the original issuer"
        );

        records[merkleRoot].active = false;
        records[merkleRoot].deactivatedAt = block.timestamp;

        emit VideoDeactivated(merkleRoot, issuerDid, block.timestamp);
    }

    /**
     * @notice Check if a video is registered and active
     * @param merkleRoot Merkle root hash of the video
     * @return isRegistered Whether the video is registered
     * @return isActive Whether the video is active
     * @return issuerDid DID of the issuer
     * @return registeredAt Registration timestamp
     */
    function getRecord(string calldata merkleRoot)
        external
        view
        returns (
            bool isRegistered,
            bool isActive,
            string memory issuerDid,
            uint256 registeredAt
        )
    {
        if (!registered[merkleRoot]) {
            return (false, false, "", 0);
        }
        VideoRecord storage record = records[merkleRoot];
        return (true, record.active, record.issuerDid, record.registeredAt);
    }
}
