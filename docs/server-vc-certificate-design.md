# 진본 서버: 블록체인 등록 기반 VC 보증서 설계

## 1. 목표

진본 서버의 역할을 다음 한 문장으로 정의한다.

> 영상의 디지털 지문을 블록체인에 등록하고, 해당 등록 사실과 등록 주체를 증명하는 VC 보증서를 발급한다.

각 기술의 책임은 분리한다.

| 구성요소 | 책임 |
|---|---|
| 블록체인 | 영상 디지털 지문, 등록 주체, 등록 시각과 활성 상태의 변경 불가능한 기준 원장 |
| VC | 진본 Issuer가 확인한 온체인 등록 사실을 사용자가 보관·제출할 수 있는 보증서로 표현 |
| 진본 DB | 등록 처리 상태, 온체인 영수증, VC 발급 연결 정보와 검색 인덱스 관리 |
| 검증 API | 입력 영상, VC, 온체인 기록의 연결 관계를 검증하고 구조화된 판정 반환 |

VC 원문은 블록체인과 진본 DB에 저장하지 않는다. 사용자 Wallet이 보관한다.

## 2. 보증 범위

초기 VC의 보증 범위는 `BLOCKCHAIN_REGISTRATION`으로 고정한다.

보증하는 내용:

- 특정 영상 디지털 지문이 명시된 체인과 컨트랙트에 등록되었다.
- 해당 온체인 기록의 등록 주체 DID가 VC subject와 일치한다.
- 진본이 트랜잭션 성공과 온체인 값을 확인한 뒤 VC를 발급했다.
- 발급 시점에 등록과 VC가 유효했다.

보증하지 않는 내용:

- 등록자가 영상의 제작자 또는 법적 저작권자라는 사실
- 영상 내용이 현실의 사실이라는 점
- AI 생성 여부
- 최초 등록 이전의 원본성

따라서 서버와 화면의 기본 용어는 `등록자`, `영상 등록 증명서`, `온체인 등록 보증서`를 사용한다. 별도 권리 심사 기능이 생기기 전에는 `소유자`, `저작권 증명서`, `원본 보증서`로 표현하지 않는다.

## 3. 식별자와 용어

현재 코드의 `issuerDid`는 실제로 영상을 등록한 회원의 DID다. VC의 `issuer`는 진본/Open DID Issuer DID이므로 둘을 구분한다.

| 의미 | 권장 이름 | 현재 대응값 |
|---|---|---|
| 영상 등록자 | `registrantDid` | `Video.issuerDid`, 컨트랙트 `issuerDid` |
| VC 발급기관 | `credentialIssuerDid` | `Video.vcIssuerDid`, Open DID Issuer DID |
| VC 보유·제출 주체 | `holderDid` | 초기에는 `registrantDid`와 동일 |

컨트랙트 ABI와 기존 DB 호환 때문에 1차 구현에서는 물리 컬럼과 컨트랙트 필드 이름을 유지할 수 있다. 신규 DTO, 문서, 로그부터 의미를 `registrantDid`로 통일하고, 컨트랙트 V2 배포 시 물리 이름도 변경한다.

## 4. 기준 데이터

### 4.1 블록체인 기록

현재 `JinBon.sol`의 다음 값을 기준 원장으로 사용한다.

```text
key: merkleRoot
value:
  registrantDid     // 현재 컨트랙트 필드명 issuerDid
  active
  registeredAt
  deactivatedAt
```

현재 `signature`는 서버 HMAC이며 등록자 DID 개인키 서명이 아니다. 공개 검증 가능한 등록자 서명으로 설명하면 안 된다. 컨트랙트가 `onlyOwner`로 등록 권한을 통제하므로 1차 범위에서는 호환을 위해 유지하되 보증 근거나 화면에 노출하지 않는다. 컨트랙트 V2에서는 제거하거나, 등록자가 생성한 DID 서명을 별도 요구사항으로 설계한다.

### 4.2 VC 클레임

VC 스키마 이름은 `VideoRegistrationCredential`로 정의한다. 커스텀 클레임은 최소 다음을 포함한다.

| 클레임 | 값/출처 | 필수 |
|---|---|---|
| `credentialSubject.id` | 등록자 DID | O |
| `assuranceType` | `BLOCKCHAIN_REGISTRATION` | O |
| `videoCommitment` | 온체인 key와 같은 `merkleRoot` | O |
| `registrantDid` | 온체인 등록자 DID | O |
| `chainId` | 서버 블록체인 설정 | O |
| `contractAddress` | 서버 블록체인 설정 | O |
| `transactionHash` | 확정된 트랜잭션 영수증 | O |
| `blockNumber` | 확정된 트랜잭션 영수증 | O |
| `registeredAt` | 가능하면 온체인 block timestamp | O |
| `videoTitle` | 사용자 표시용 제목 | 선택 |
| `schemaVersion` | `1` | O |

VC에는 `fineHash`와 `perceptualHash` 원문을 넣지 않는다. 블록체인 기준값인 `merkleRoot`를 `videoCommitment`로 사용한다. 세부 해시는 진본 서버가 입력 영상에서 commitment를 재구성하는 데 사용한다.

`transactionHash`만으로 체인을 특정할 수 없으므로 `chainId`와 `contractAddress`를 반드시 함께 넣는다.

## 5. 발급 흐름

```text
1. 등록자 인증 및 ISSUER 역할 확인
2. fineHash와 perceptualHash 계산
3. videoCommitment(현재 merkleRoot) 계산
4. DB 중복 등록 예약
5. 블록체인 register 트랜잭션 전송
6. 성공 receipt와 blockNumber 확인
7. eth_call로 commitment, registrantDid, active를 다시 확인
8. 확정된 온체인 증거로 VC claim snapshot 생성
9. Open DID Offer 생성
10. Wallet이 사용자 동의 후 VC 수령
11. 완료 API에서 VC 서명·상태·Issuer·subject·claim snapshot 검증
12. vcId 연결 및 ISSUED 확정
```

핵심 규칙:

- 온체인 확정과 재조회가 끝나기 전에 VC Offer를 생성하지 않는다.
- VC 클레임은 요청값이 아니라 확정된 DB/receipt/온체인 값을 사용한다.
- VC 발급 실패는 블록체인 등록을 취소하지 않는다. 재발급 준비가 가능해야 한다.
- 완료 요청의 `vcId`가 유효하다는 것만 확인하지 않고, 해당 영상용으로 준비한 클레임과 일치하는지 확인한다.

## 6. 발급 상태

MVP의 최소 상태는 기존 enum을 유지하면서 의미를 명확히 한다.

```text
NOT_REQUESTED
  -> PENDING_WALLET
  -> ISSUED
```

영상의 온체인 등록 성공은 `txHash`와 `blockNumber`가 모두 존재하고 온체인 재조회가 일치하는 것으로 판정한다. 운영 안정화 단계에서는 외부 트랜잭션과 DB 트랜잭션의 불일치를 복구하기 위해 별도 `blockchainRegistrationStatus`를 도입한다.

```text
PENDING -> SUBMITTED -> CONFIRMED
                     -> FAILED
```

이는 2차 범위로 둔다. 현재 API 계약을 한 번에 크게 변경하지 않는다.

## 7. 클레임 스냅샷 결속

현재 완료 로직은 `vcId`의 ACTIVE 상태와 서명만 검사한다. 다른 영상용으로 발급된 정상 VC도 연결될 수 있으므로 충분하지 않다.

발급 준비 시 서버는 정규화한 핵심 클레임의 SHA-256을 저장한다.

```text
vcClaimSnapshotHash = SHA-256(
  schemaVersion + "\n" +
  assuranceType + "\n" +
  videoCommitment + "\n" +
  registrantDid + "\n" +
  credentialIssuerDid + "\n" +
  chainId + "\n" +
  contractAddress + "\n" +
  transactionHash + "\n" +
  blockNumber + "\n" +
  registeredAt
)
```

완료 시 검증기가 반환한 VC의 issuer, subject와 클레임을 같은 규칙으로 정규화해 비교한다.

Open DID Verifier 2.0.0의 현재 연동 API가 검증된 클레임을 반환하지 않는다면, 다음 중 지원되는 한 가지를 확인한 뒤 구현한다.

1. Verifier의 VP 검증 결과에서 검증된 subject/claims 수신
2. Wallet이 제출한 VC/VP를 Verifier가 검증하고 검증 결과와 claims 반환
3. Issuer 관리 API에서 발급된 VC의 claims 조회

검증된 클레임을 얻을 수 없는 상태에서 `vcId`만 연결하는 방식은 보증서 결속 요건을 충족하지 못한다. 이 항목은 구현 전 Open DID API 스파이크의 필수 통과 조건이다.

또한 현재 Issuer holder의 `userInfo`를 DID 기준으로 덮어쓰므로, 같은 사용자가 여러 영상 Offer를 동시에 준비하면 클레임이 섞일 가능성이 있다. 1차 구현에서는 등록자별 VC 발급 준비를 직렬화하고 PENDING Offer를 명시적으로 관리한다. Open DID가 Offer별 claim snapshot을 지원하는지 확인되면 그 기능을 우선 사용한다.

## 8. 검증 모델

검증 결과는 하나의 `authentic` boolean에 모든 의미를 합치지 않는다.

| 결과 | 의미 |
|---|---|
| `contentMatch` | 입력 영상이 등록된 exact/similar 영상과 일치하는가 |
| `blockchainVerified` | 재계산한 commitment와 온체인 기록이 일치하고 active인가 |
| `certificateVerified` | VC 서명, 상태, 신뢰 Issuer, subject와 모든 온체인 클레임이 일치하는가 |
| `holderVerified` | 선택적 VP challenge로 현재 제출자가 subject DID 키를 통제하는가 |

공개 영상 업로드 검증은 Wallet VC 제출 없이도 가능하므로 다음 두 결과를 구분한다.

```text
registrationVerified = contentMatch && blockchainVerified
certificateVerified  = VC가 제출/연결된 경우에만 별도 판정
```

정책상 최상위 판정은 다음과 같이 정의한다.

```text
CERTIFIED_EXACT_MATCH:
  exact contentMatch && blockchainVerified && certificateVerified

REGISTERED_EXACT_MATCH:
  exact contentMatch && blockchainVerified && VC 미제출/미발급

CERTIFICATE_INVALID:
  contentMatch && blockchainVerified && VC가 존재하지만 검증 실패
```

현재 코드의 `authentic = blockchainVerified && !verificationUnavailable`는 VC 실패를 최종 판정에 반영하지 않으므로 변경 대상이다. 기존 클라이언트 호환을 위해 `authentic`는 당분간 유지하되, 신규 구조화 필드와 `verdict`를 기준 계약으로 삼는다.

## 9. API 변경 범위

### 9.1 기존 API 유지

- `POST /api/videos`: 영상 등록, 온체인 확정, VC Offer 준비
- `POST /api/videos/{id}/vc/prepare`: 실패·취소된 발급 재개
- `POST /api/videos/{id}/vc/complete`: Wallet 수령 결과 연결
- `POST /api/verify/file`, URL 검증 API: 영상 등록/보증서 검증

### 9.2 응답 필드 보강

등록·상세 응답에 다음 구조를 추가한다. 기존 평면 필드는 호환 기간 동안 유지한다.

```json
{
  "blockchainEvidence": {
    "network": "omnione",
    "chainId": "...",
    "contractAddress": "0x...",
    "transactionHash": "0x...",
    "blockNumber": "0x...",
    "videoCommitment": "...",
    "status": "CONFIRMED"
  },
  "certificate": {
    "type": "VideoRegistrationCredential",
    "assuranceType": "BLOCKCHAIN_REGISTRATION",
    "vcId": "...",
    "issuerDid": "did:omn:issuer",
    "subjectDid": "did:omn:registrant",
    "issuanceStatus": "ISSUED"
  }
}
```

검증 응답에는 최소 다음을 추가한다.

```json
{
  "contentMatch": "EXACT|SIMILAR|NONE",
  "registrationVerified": true,
  "certificateVerified": true,
  "certificateStatus": "VALID|NOT_ISSUED|INVALID|UNAVAILABLE",
  "verdict": "CERTIFIED_EXACT_MATCH"
}
```

### 9.3 완료 요청

현재의 `vcId + offerId`는 유지한다. 서버가 Open DID를 통해 검증된 claims를 조회할 수 없다면 완료 API는 Wallet의 VP 제출 프로토콜로 변경해야 한다. 이 경우 nonce/challenge와 presentation 결과 식별자가 추가된다.

## 10. DB 변경 범위

1차 구현에서 `videos`에 다음 컬럼을 추가한다.

| 컬럼 | 용도 |
|---|---|
| `vc_claim_snapshot_hash` | 준비한 핵심 클레임과 완료된 VC 결속 |
| `vc_schema_version` | VC 스키마 버전 |
| `vc_assurance_type` | 초기값 `BLOCKCHAIN_REGISTRATION` |

`chainId`와 `contractAddress`는 현재 전역 설정값이지만, 향후 네트워크 변경 후 과거 증거를 올바르게 검증하려면 영상 등록 시점 값을 보존해야 한다. 운영 배포 전에는 다음 컬럼도 저장한다.

| 컬럼 | 용도 |
|---|---|
| `chain_id` | 등록 당시 체인 식별자 |
| `contract_address` | 등록 당시 컨트랙트 주소 |

현재 `ddl-auto: update`는 로컬 개발에는 편하지만 운영 마이그레이션 추적에 부적합하다. 운영 전 Flyway 도입과 명시적 migration을 별도 작업으로 잡는다.

## 11. 코드 변경 예상 위치

| 위치 | 변경 내용 |
|---|---|
| `VideoRegisterService` | 온체인 재조회 후 claim 생성, 새 클레임 키, snapshot 저장·비교 |
| `Video` | 보증서 메타데이터와 claim snapshot 필드, 상태 전이 불변식 |
| `VcIssuanceService`, `OpenDidIssuerClient` | `VideoRegistrationCredential` 클레임 전달과 동시 Offer 보호 |
| `VcVerificationService`, `OpenDidVerifierClient` | 단순 VALID 확인이 아닌 issuer/subject/claims 검증 결과 반환 |
| `VideoVerifyService` | 등록 검증과 보증서 검증 분리, VC invalid 판정 수정 |
| DTO/API 문서 | blockchain evidence와 certificate 결과 구조화 |
| `OpenDidProperties`, `BlockchainProperties` | schema version, chain ID 등 증거 메타데이터 설정 |
| Open DID 관리 설정 | VC schema와 plan을 신규 클레임에 맞게 갱신 |
| 테스트 | 클레임 결속, 오발급 연결 차단, 상태별 verdict 검증 |

스마트 컨트랙트 변경은 1차 필수 범위가 아니다. 현재 컨트랙트만으로 온체인 등록 보증서 발급이 가능하다.

## 12. 테스트와 수용 기준

### 단위 테스트

- VC 준비 클레임의 `videoCommitment`가 온체인 `merkleRoot`와 동일하다.
- receipt의 txHash/blockNumber와 서버 설정의 chain/contract가 VC claim에 포함된다.
- 등록자 DID와 VC issuer DID를 혼동하지 않는다.
- 다른 영상, 다른 subject, 다른 issuer, 다른 txHash의 정상 VC를 완료 요청에 넣어도 거부한다.
- revoked/expired/invalid VC는 `certificateVerified=false`다.
- VC 미발급 영상은 `registrationVerified=true`, `certificateStatus=NOT_ISSUED`가 가능하다.
- VC invalid인 경우 최상위 결과가 인증 완료로 표시되지 않는다.

### 통합 테스트

- 영상 등록 → receipt 확정 → 온체인 재조회 → Offer 생성 순서를 보장한다.
- Wallet 발급 → 완료 → 검증에서 동일 클레임을 확인한다.
- 블록체인 등록 성공 후 VC 발급 실패 시 재시도가 가능하다.
- 동일 회원의 동일 영상 재요청은 새 온체인 트랜잭션을 만들지 않는다.
- 동일 등록자의 복수 영상 VC가 서로 뒤섞이지 않는다.
- 비활성화 후 온체인과 API 모두 유효한 보증서로 판정하지 않는다.

### 완료 조건

1. 검증자는 VC만 보고 어떤 체인/컨트랙트/트랜잭션/commitment를 확인해야 하는지 알 수 있다.
2. 서버는 VC의 서명만 아니라 영상 및 온체인 클레임 결속까지 확인한다.
3. VC가 없어도 온체인 영상 등록 검증은 유지된다.
4. VC 원문과 개인정보는 블록체인 또는 서버 DB에 추가 저장하지 않는다.
5. API가 `등록 확인`, `보증서 확인`, `현재 holder 확인`을 서로 다른 의미로 반환한다.

## 13. 구현 순서

### Phase 0 — Open DID API 스파이크

- 실제 발급 VC의 클레임 구조 캡처
- Verifier가 검증된 issuer, subject, claims를 반환하는 경로 확인
- 한 Holder의 복수 pending Offer가 claims를 snapshot하는지 확인
- 통과하지 못하면 VP 제출 기반 완료 프로토콜 설계

### Phase 1 — 보증서 데이터 계약

- Open DID VC schema/plan 갱신
- 서버 claim builder와 snapshot hash 구현
- DB 필드와 설정 추가
- 등록/상세 DTO에 보증서와 온체인 증거 구조 추가

### Phase 2 — 안전한 발급 완료

- 완료 시 issuer/subject/claims 결속 검증
- 다른 영상 VC 연결 차단
- 등록자별 동시 발급 충돌 방지

### Phase 3 — 검증 결과 개편

- `registrationVerified`와 `certificateVerified` 분리
- verdict 확장 및 기존 `authentic` 호환 정책 적용
- 캐시 키/무효화에 VC 상태 변화 반영

### Phase 4 — 운영 안정화

- Flyway migration
- 블록체인 등록 상태와 재시도/복구 작업
- VC 폐기·재발급 상태 전이
- 컨트랙트 V2에서 HMAC signature 제거 여부 결정

## 14. 이번 범위에서 제외

- 저작권·소유권 심사와 그에 대한 VC
- 영상 원문 저장
- VC 원문 온체인 또는 서버 저장
- 선택적 공개(Selective Disclosure)
- 컨트랙트 재배포
- 다중 체인 지원

위 항목은 `VideoRegistrationCredential`의 발급·검증이 안정화된 뒤 별도 요구사항으로 진행한다.
