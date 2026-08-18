# VC 재발급 프로토콜

## 목적

앱 재설치·기기 변경으로 Holder Wallet과 DID가 교체된 경우, 기존 영상의 블록체인 진본 기록은 유지하면서 새 DID에 영상 진본 VC를 다시 발급한다.

VC 원문과 개인키는 서버에 저장하지 않는다. 서버는 영상 소유권, 기존/신규 VC ID, 발급 세션 및 상태만 관리하고 VC 수령·보관은 iOS Wallet SDK가 담당한다.

## 핵심 원칙

1. 모바일 신분증 재인증과 DID 재연결이 완료된 회원만 재발급할 수 있다.
2. 영상의 기존 `issuerDid`, Merkle Root, 트랜잭션 해시는 변경하지 않는다.
3. 새 VC가 Wallet에 저장되고 서버 확인까지 끝나기 전에는 기존 VC를 폐기하지 않는다.
4. 재발급은 영상 단위로 멱등 처리하며 동일 영상에 활성 재발급 세션은 하나만 허용한다.
5. 서버는 VC 원문을 저장하지 않고 `vcId`와 상태 전이만 기록한다.

## 사용자 흐름

```text
DID 재연결 완료
  → 복구 가능한 기존 영상 조회
  → 사용자가 재발급할 영상 선택
  → 서버가 영상별 Open DID Issue Offer 생성
  → 앱이 Offer를 Wallet SDK에 전달
  → 사용자가 PIN/생체인증으로 새 DID 서명
  → Wallet SDK가 VC 수령 및 로컬 저장
  → 앱이 저장된 vcId로 서버에 수령 확인
  → 서버가 신규 VC 유효성 확인
  → 기존 VC 폐기
  → 신규 VC를 현재 인증서로 확정
```

기본 UX는 복구 대상 전체 선택이며 사용자가 개별 영상을 제외할 수 있다. 실패한 영상만 다시 시도할 수 있어야 한다.

## 상태 모델

`vc_reissuances`

| 필드 | 설명 |
|---|---|
| `id` | 재발급 작업 ID(UUID) |
| `member_id` | 소유 회원 |
| `video_id` | 대상 영상 |
| `old_holder_did` | 기존 Holder DID |
| `new_holder_did` | 재연결된 현재 DID |
| `old_vc_id` | 기존 VC ID, 없을 수 있음 |
| `new_vc_id` | Wallet 수령 후 기록 |
| `offer_id` | Open DID Offer ID |
| `issuer_tx_id` | 발급 트랜잭션 ID |
| `status` | 아래 상태 값 |
| `failure_code` | 실패 코드 |
| `expires_at` | Offer 만료 시각 |
| `created_at`, `updated_at`, `completed_at` | 감사 시각 |

상태 전이:

```text
ELIGIBLE
  → OFFER_CREATED
  → WALLET_PROCESSING
  → WALLET_CONFIRMED
  → NEW_VC_VERIFIED
  → OLD_VC_REVOKED
  → COMPLETED

OFFER_CREATED/WALLET_PROCESSING → EXPIRED
각 단계 → FAILED
FAILED/EXPIRED → 새 작업 생성 또는 안전한 단계부터 재시도
```

`WALLET_CONFIRMED` 이전에는 기존 VC를 절대 폐기하지 않는다. 기존 VC 폐기 API가 실패하면 `NEW_VC_VERIFIED`에서 재시도하며 신규 VC와 기존 VC가 잠시 동시에 활성인 것은 허용한다.

## API

### 1. 복구 대상 조회

`GET /api/vc-reissuances/eligible`

응답:

```json
{
  "videos": [
    {
      "videoId": 10,
      "title": "원본 영상",
      "registeredAt": "2026-07-14T20:00:00",
      "oldVcId": "vc-old",
      "oldVcStatus": "ACTIVE",
      "reissuable": true
    }
  ]
}
```

판정 조건:

- 로그인 회원이 `member_id`로 소유한 영상
- 회원의 현재 DID가 존재
- 영상 등록 당시 DID와 현재 DID가 다르거나 현재 Wallet에 인증서가 없음
- 진행 중인 동일 영상 작업이 없음

### 2. 재발급 작업 생성

`POST /api/vc-reissuances`

```json
{
  "videoIds": [10, 11],
  "didRebindReceipt": "short-lived-signed-receipt"
}
```

서버 처리:

1. 회원·영상 소유권과 DID 재연결 영수증 검증
2. 영상별 멱등키 `memberId:videoId:newDid` 확인
3. `request-offer` 호출
4. `OFFER_CREATED` 저장
5. 앱이 사용할 Issue Offer 반환

```json
{
  "items": [
    {
      "reissuanceId": "uuid",
      "videoId": 10,
      "offer": {
        "type": "IssueOffer",
        "vcPlanId": "...",
        "issuer": "did:omn:issuer...",
        "offerId": "...",
        "validUntil": "2026-07-14T21:00:00"
      }
    }
  ]
}
```

### 3. Wallet 처리 시작

`POST /api/vc-reissuances/{id}/wallet-started`

앱이 `IssueVcProtocol.preProcess()`를 시작하기 직전에 호출한다. 서버는 작업 소유권과 Offer 만료 여부를 검사하고 `WALLET_PROCESSING`으로 전이한다.

### 4. Wallet 저장 확인

`POST /api/vc-reissuances/{id}/confirm`

```json
{
  "newVcId": "vc-new",
  "holderDid": "did:omn:new-holder..."
}
```

앱은 `IssueVcProtocol.process()`와 `confirm-issue-vc`가 성공하고, `WalletAPI.getAllCredentials()`에서 동일 `vcId`가 실제 조회된 뒤 호출한다.

서버 처리 순서:

1. 작업 소유자, 현재 회원 DID, 요청 `holderDid` 일치 확인
2. Open DID Verifier로 신규 VC 상태·서명·Issuer·Holder·스키마 확인
3. 영상 ID/Merkle Root 등 진본 클레임이 대상 영상과 일치하는지 확인
4. `NEW_VC_VERIFIED` 저장
5. 기존 VC가 있으면 폐기 요청
6. 폐기 성공 시 `OLD_VC_REVOKED`, 없거나 이미 폐기 상태면 통과
7. `videos.vc_id`를 신규 VC ID로 교체하고 `COMPLETED`

응답:

```json
{
  "status": "COMPLETED",
  "videoId": 10,
  "oldVcId": "vc-old",
  "newVcId": "vc-new"
}
```

### 5. 작업 상태/재시도

- `GET /api/vc-reissuances/{id}`
- `POST /api/vc-reissuances/{id}/retry-revoke`
- `DELETE /api/vc-reissuances/{id}`: Wallet 처리 전 작업만 취소 가능

## iOS 처리

1. DID 재연결 완료 화면에서 복구 대상 API를 호출한다.
2. 선택 화면에서 영상 제목, 등록일, 기존 VC 상태를 보여준다.
3. 서버에서 받은 Offer를 `IssueOfferPayload`로 변환한다.
4. 영상별로 `IssueVcProtocol.preProcess(vcPlanId:issuer:offerId:)`를 호출한다.
5. PIN/생체인증 후 `IssueVcProtocol.process()`를 호출한다.
6. Wallet의 전체 Credential을 다시 읽어 반환된 vcId가 저장됐는지 검증한다.
7. 서버 confirm API를 호출한다.
8. 완료된 항목은 인증서 탭에 표시하고 실패한 항목만 재시도한다.

발급은 SDK 공유 상태(`IssueVcProtocol.shared`) 때문에 한 번에 하나씩 직렬 처리한다. 앱 종료 후에는 서버 작업 상태를 조회해 `OFFER_CREATED`부터 다시 시작하며, Wallet에 신규 VC가 이미 있으면 confirm만 재호출한다.

## 보안 및 정합성

- 재발급 작업 생성에는 로그인 JWT와 DID 재연결 직후 발급된 1회용 영수증을 모두 요구한다.
- `videoIds`는 요청자의 `member_id` 소유권으로 검증한다.
- Offer는 짧게 만료하고 다른 회원/DID에서 사용할 수 없게 한다.
- confirm 요청의 `newVcId`를 신뢰하지 않고 Verifier에서 직접 검증한다.
- 신규 VC 클레임에는 최소 `videoId`, `merkleRoot`, `originalTxHash`, `originalIssuerDid`, `currentHolderDid`, `reissuedAt`, `previousVcId`를 포함한다.
- 기존 블록체인 등록과 `originalIssuerDid`는 수정하지 않는다.
- 모든 상태 전이와 폐기 결과는 감사 로그로 남긴다.
- `memberId + videoId + newHolderDid`에 유니크 제약을 둔다.

## 실패 정책

| 실패 지점 | 처리 |
|---|---|
| Offer 생성 실패 | DB 작업을 FAILED로 기록, 기존 VC 유지 |
| 사용자 인증 취소 | WALLET_PROCESSING 유지 또는 만료, 기존 VC 유지 |
| VC 수령 실패 | 재시도 가능, 기존 VC 유지 |
| Wallet 저장 성공 후 앱 종료 | 재실행 시 Wallet vcId 탐색 후 confirm 재호출 |
| 신규 VC 검증 실패 | 신규 VC를 현재 인증서로 채택하지 않음, 기존 VC 유지 |
| 기존 VC 폐기 실패 | 신규 VC 검증 상태로 보관하고 서버가 재시도, 둘 다 임시 활성 |
| confirm 중복 호출 | 동일 newVcId면 기존 성공 응답 반환 |

## 구현 순서

1. 영상 VC 클레임에 영상 식별 정보 포함 및 검증 가능하게 변경
2. `vc_reissuances` 엔티티·상태 머신·멱등 제약 추가
3. Offer 생성/상태/confirm API 구현
4. Issuer의 폐기 API와 Verifier 상세 검증 API 연동
5. iOS 복구 대상 선택 화면과 직렬 발급 큐 구현
6. Wallet 저장 확인 후 confirm 연결
7. 앱 종료·만료·폐기 실패 복구 테스트
