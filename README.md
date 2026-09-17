# 진본 (JinBon) Backend

블록체인 기반 영상 진본 인증 서비스 백엔드

> 2026 블록체인 & AI 해커톤 - Track 2 (MVP 개발)

## 서비스 개요

**진본**은 영상 콘텐츠의 원본 여부를 블록체인과 DID 기술로 증명하는 플랫폼입니다.
공인 등록자(모바일 신분증으로 본인확인을 완료한 진본 등록자)가 영상을 등록하면 해시와 블록체인으로 영상 무결성을 기록하고, 사용자가 Wallet에서 VC(Verifiable Credential)를 발급받아 등록 사실을 증명할 수 있습니다. 기관 소속·공식 발행 권한 인증은 별도입니다.

## 핵심 프로세스

### 시스템 구성도

![시스템 구성도](docs/system_diagram.png)

### 영상 등록 플로우

```
공인(Issuer) → 진본 백엔드
  │
  ├─ 1. 모바일 신분증 로그인 (OmniOne CX)
  ├─ 2. 영상 업로드
  ├─ 3. SHA-256 (fineHash) 생성 → 중복 영상 확인
  ├─ 4. 지각해시 (DCT 기반 pHash, 프레임별 — 재인코딩 내성) 생성
  ├─ 5. 두 해시의 결합 SHA-256 생성 (Merkle Root 필드) + 서버 HMAC 서명
  │
  ├─ 6. [선택과제 2] OmniOne Chain 기록
  │     → Merkle Root + Issuer DID + Signature → 블록체인 트랜잭션
  │
  ├─ 7. DB 저장 (영상정보, 해시, Merkle Path, txHash)
  │
  └─ 8. [선택과제 1] Open DID VC 보증서 발급 준비
        → 서버가 온체인 등록을 재확인
        → Holder DID와 Merkle Root, 체인·컨트랙트·트랜잭션 증거를 Issuer에 등록
        → 진본 Issuer가 "블록체인 등록 사실"을 보증하는 발급 Offer 생성
        → 앱이 Wallet 프로토콜로 사용자 동의·PIN 인증 후 VC 수령 및 저장
        → 발급 완료 API로 vcId 제출
        → 서버가 VC 상태·issuer/subject DID·영상 블록체인 claim을 검증한 뒤 연결
```

영상 등록과 VC 발급은 별도 단계입니다. 영상의 블록체인 등록이 완료되면 VC 발급을 취소하거나 일시적으로 실패해도 영상 등록 결과는 유지되며, 이후 Wallet에서 다시 발급받을 수 있습니다.

### 회원가입 / 로그인 플로우

회원가입과 로그인은 명확히 분리됩니다. 로그인 과정에서는 회원을 자동 생성하지 않습니다.

```
회원가입: 모바일 신분증 인증 → PENDING 회원 생성 → Wallet/DID 생성
        → DID Document 등록 → 서버 DID 연결 → ACTIVE 전환 → JWT 발급

로그인:   모바일 신분증 인증 → CI로 ACTIVE 회원 조회 → JWT 발급
        (미가입: 거부 / PENDING: 가입 완료 안내)
```

CI 원문은 저장하지 않습니다. 인증 직후 서버 전용 비밀키로 `HMAC-SHA256` 처리한 식별자만 회원 중복 확인·로그인·DID 복구에 사용하며, VC·DID Document·블록체인에는 포함하지 않습니다. `CI_HMAC_SECRET`은 32자 이상의 고정값으로 별도 보관하고 변경 또는 분실하지 않아야 합니다.

### 영상 검증 플로우

```
검증 요청자 (크롬 확장 등) → 진본 백엔드
  │
  ├─ 1. SHA-256 해시 재계산 → 캐시/DB 정확 매칭 시도
  │     ├─ 캐시 HIT → 즉시 반환 (DB/블록체인 조회 생략)
  │     ├─ DB HIT → 블록체인 검증 후 결과 캐싱
  │     └─ MISS ↓
  ├─ 2. 지각해시(pHash) 생성 → 유사도 검색 (재인코딩 영상 대응)
  │     → 영상 길이 + 같은 상대 시점의 16개 프레임 비교 → 콘텐츠 유사/부분 유사 판정
  │
  ├─ 3. [선택과제 2] OmniOne Chain 검증
  │     → Merkle Root로 온체인 Issuer DID + Signature 조회
  │     → 서명 재계산 결과와 온체인 데이터 비교 → 무결성 확인
  │
  ├─ 4. [선택과제 1] Open DID VC 검증
  │     → VC 상태(ACTIVE), issuer/subject DID, 영상 commitment·트랜잭션 claim 일치 확인
  │
  └─ 5. 원본 일치와 등록 증거 구분 + 검증 결과 캐싱 (미등록·유사 후보·장애 제외)
```

## 해커톤 과제별 활용

### 필수과제: 모바일 신분증 (OmniOne CX)

| 항목 | 내용 |
|------|------|
| 용도 | 공인(Issuer) 로그인 및 본인확인 |
| 연동 방식 | OmniOne CX OACX API (trans / authen/app) |
| 인증 흐름 | WebToApp (token 발급 → 딥링크 생성 → 앱 호출) → OmniOne CX 신원 검증 → CI 기반 회원 처리 → JWT 발급 |

### 선택과제 1: Open DID

| 항목 | 내용 |
|------|------|
| 용도 | 영상 블록체인 등록 보증서(VC) 발급 및 검증 |
| 역할 | **진본 Issuer가 "누가, 언제, 어떤 온체인 기록으로 이 영상을 등록했는가"를 확인했다는 증명** |
| 구성 | Open DID Orchestrator로 TAS, Issuer, Verifier, CA, Wallet, API 서버 일괄 관리 |
| 블록체인 | Hyperledger Besu (로컬 Docker) — DID Document 앵커링용 |
| VC 발급 흐름 | 백엔드가 Holder/Claim 등록 및 발급 Offer 생성 → 앱 Wallet이 offerId로 사용자 동의·PIN 인증 → issue-vc → confirm → 로컬 저장 → 백엔드에 vcId 제출 및 claim 결속 검증 |
| 검증 시 | VC 상태(ACTIVE), 발급 기관·등록자 DID, 영상 commitment·온체인 claim 결속 확인 |

### 선택과제 2: OmniOne Chain

| 항목 | 내용 |
|------|------|
| 용도 | 영상 해시의 블록체인 기록 및 검증 |
| 역할 | **"이 영상이 변조되지 않았는가"** 에 대한 무결성 증명 |
| 체인 | OmniOne Chain (BESU 기반) |
| 연동 방식 | REST API (`test.stage-chainapi.omnione.net`) + API Token 인증 |
| 기록 데이터 | Merkle Root, Issuer DID, Digital Signature |
| 스마트 컨트랙트 | Solidity 기반 JinBon.sol (register / getRecord / deactivate), 배포 지갑만 변경 가능 |
| 검증 시 | 해시 재계산 → 온체인 Merkle Root 비교 → 무결성 판정 |

### 두 과제의 조합

![시스템 구성도](docs/PPT/jinbon_selection_task.png.png)

## 기술 스택

| 구분 | 기술 |
|------|------|
| Language | Java 21 |
| Framework | Spring Boot 4.1.0 |
| Database | PostgreSQL 16.4 |
| Cache | Redis 7 |
| Blockchain | OmniOne Chain (BESU / Solidity) |
| DID | Open DID (Orchestrator + Hyperledger Besu) |
| 모바일 신분증 | OmniOne CX (VC-Verifier) |
| Build | Gradle 8.14 |

## 프로젝트 구조

```
src/main/java/com/jinbon/
├── JinbonApplication.java
├── domain/
│   ├── auth/              # 인증 (OmniOne CX + JWT)
│   │   ├── controller/
│   │   ├── dto/
│   │   ├── port/          # 외부 인증·토큰 저장소 경계
│   │   └── service/
│   ├── member/            # 회원 관리
│   │   ├── entity/
│   │   └── repository/
│   └── video/             # 영상 등록/검증
│       ├── controller/
│       ├── dto/
│       ├── entity/
│       ├── port/          # VC·블록체인·캐시 외부 연동 경계
│       ├── repository/
│       └── service/
├── global/                # 공통
│   ├── common/            #   응답 포맷
│   ├── config/            #   Security, JWT Filter, 외부 연동 설정
│   └── error/             #   예외 처리
└── infra/                 # 외부 연동
    ├── omnione/           #   OmniOne CX 클라이언트
    ├── opendid/           #   Open DID Issuer 연동 + Wallet VC 발급 준비/검증
    ├── blockchain/        #   OmniOne Chain 클라이언트
    └── redis/              #   Redis 설정·토큰 저장소·검증 캐시
```

## 인프라 구성

```
docker-compose.yml (진본 인프라)
├── jinbon-postgres (5432)    # 진본 백엔드 DB
└── jinbon-redis (6380)       # 검증 캐시

Open DID Orchestrator (localhost:9001)
├── Hyperledger Besu (Docker)  # DID Document 앵커링용 블록체인
├── PostgreSQL (5430)          # Open DID 서버 DB
├── TAS (8090)                 # Trust Agent Server
├── Issuer (8091)              # VC 발급 서버
├── Verifier (8092)            # VC 검증 서버
├── CA (8094)                  # Certificate Authority
├── Wallet (8095)              # 지갑 서버
├── API Gateway (8093)         # API 게이트웨이
└── Demo (8099)                # 데모 서버
```

## 실행 방법

### 사전 요구사항

- JDK 21
- Docker 및 Docker Compose
- URL 영상 검증을 사용할 경우 `yt-dlp`
- 별도로 설치한 Open DID Orchestrator 2.0.0
- 배포된 `JinBon.sol` 컨트랙트와 배포 지갑 keystore

### 1. 진본 인프라 기동

```bash
# PostgreSQL + Redis
docker compose up -d
```

### 2. Open DID Orchestrator 기동 (별도 저장소)

```bash
cd /path/to/did-orchestrator-server

# 서버 JAR 다운로드 (최초 1회)
sh download.sh 2.0.0

# 빌드
./gradlew clean build -x test

# 실행
java -jar did-orchestrator-server-2.0.0.jar
```

브라우저에서 `http://localhost:9001` 접속 후:
1. Repository 선택 (Hyperledger Besu)
2. **Generate All** → Wallet/DID Document 생성
3. **Start All** → 전체 서버 기동

Open DID를 사용하지 않을 때는 `.env`에서 `OPENDID_ENABLED=false`로 설정합니다.
이 경우 영상 등록은 가능하지만 VC 준비·완료·검증은 수행되지 않으며 `vcVerified`는 `false`입니다.

### 3. 진본 백엔드 실행

```bash
# 환경변수 설정
cp .env.example .env

# contracts/JinBon.sol을 배포한 뒤 CONTRACT_ADDRESS를 설정하고,
# 배포에 사용한 지갑 keystore를 아래 경로에 둡니다.
# src/main/resources/keystore/omnione-chain-keystore.json

# 빌드 & 실행
./gradlew bootRun
```

## API 엔드포인트

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| POST | `/api/auth/token` | OmniOne CX 토큰 생성 | X |
| POST | `/api/auth/app/request` | WebToApp 인증 요청 (Deep Link 생성) | X |
| POST | `/api/auth/app/verify` | WebToApp 검증 + 로그인 | X |
| POST | `/api/auth/did/rebind` | 앱 재설치 후 Wallet DID 재연결 | X (재연결 토큰) |
| POST | `/api/auth/refresh` | JWT 토큰 갱신 | X |
| POST | `/api/auth/logout` | 로그아웃 (Refresh Token 폐기) | X |
| POST | `/api/signup/token` | 회원가입용 OmniOne CX 토큰 생성 | X |
| POST | `/api/signup/app/request` | 회원가입용 WebToApp 인증 요청 | X |
| POST | `/api/signup/app/verify` | 본인확인 + PENDING 회원 생성 | X |
| POST | `/api/signup/did/complete` | DID 연결 + 회원가입 완료 + JWT 발급 | X (가입 토큰) |
| POST | `/api/videos` | 영상 등록 (해시 + 블록체인) 및 Wallet VC 발급 준비 | O (ISSUER) |
| POST | `/api/videos/{id}/vc/complete` | Wallet VC 발급 완료 후 `vcId`와 등록 응답의 `offerId` 연결 | O (ISSUER, 본인 영상) |
| GET | `/api/videos` | 내 영상 목록 조회 | O |
| GET | `/api/videos/{id}` | 영상 상세 조회 | O |
| PATCH | `/api/videos/{id}/deactivate` | 영상 비활성화 | O (ISSUER) |
| POST | `/api/verify` | 영상 진본 검증 (파일 업로드) | X |
| POST | `/api/verify/url` | 영상 진본 검증 (URL 기반, 서버 다운로드 후 전체 분석) | X |

### 영상 검증 판정

검증 API의 `authentic=true`는 파일 정확 일치 또는 영상·음성 유사도 기준 통과에 더해 블록체인·VC 검증을 모두 통과한 경우 반환합니다.
유사도 경로는 영상 커버리지 95%, 음성 커버리지 90% 이상 및 순서 보존과 동일한 원본 대응 시간 오프셋을 요구합니다. 이 수치는 탐지 정확도가 아닙니다. 반복 장면 등으로 최적 오프셋이 다르면 보수적으로 확인을 보류합니다.
앱·웹·확장은 `진본 확인 완료` 표시를 유지하고 확인 방식을 `원본 파일 정확 일치` 또는 `영상·음성 비교`로 구분합니다. 유사 후보만 있는 `CONTENT_SIMILAR`·`PARTIAL_MATCH`는 진본 확인 보류입니다.
등록 증거는 `blockchainVerified`, `vcVerified`, `vcClaimsBound`로 별도 표시합니다.

| verdict | 의미 |
|---------|------|
| `EXACT_MATCH` | 등록된 원본 파일과 SHA-256이 정확히 일치 |
| `SIMILAR_MATCH` | 영상·음성 유사도와 동일한 원본 대응 시간 오프셋 기준 통과 (`AUTHENTICATED`, 등록 증거 유효 시) |
| `CONTENT_SIMILAR` | 후보는 찾았지만 비교 기준 미달, 시간 오프셋 불일치 또는 정보 부족 |
| `PARTIAL_MATCH` | 일부 프레임 유사, 길이·순서·구간 차이 또는 비교 정보 부족 (`PARTIAL_SIMILAR`) |
| `CERTIFICATE_MISSING` | 보증서 미발급 |
| `CERTIFICATE_INVALID` | 보증서 검증 실패 |
| `REGISTERED_BUT_REVOKED` | 등록 기록은 있으나 이후 비활성화됨 |
| `NOT_REGISTERED` | 일치하거나 유사한 등록 기록을 찾지 못함 |
| `VERIFICATION_UNAVAILABLE` | 블록체인 또는 VC 외부 검증 장애, 혹은 등록 무결성을 확인할 수 없는 상태 |

`NOT_REGISTERED`는 해당 영상이 조작되었다는 의미가 아닙니다. 등록 이력이 없다는 의미만
가지며 응답의 `notice`에도 같은 안내가 포함됩니다. `SIMILAR_MATCH` 응답에는
`similarityDistance`가 포함됩니다. `SAME_CONTENT`는 더 이상 생성하지 않습니다.

판정 기준·측정 결과·기존 영상 호환 범위는 [영상 검증 기준](docs/video-verification.md)을 참고하세요.

```json
{
  "verdict": "NOT_REGISTERED",
  "similarityDistance": null,
  "authentic": false,
  "videoId": null,
  "issuerDid": null,
  "registeredAt": null,
  "blockchainVerified": false,
  "vcVerified": false,
  "active": false,
  "message": "진본에 등록된 기록을 찾지 못했습니다.",
  "notice": "미등록은 영상이 조작되었다는 의미가 아닙니다."
}
```

`NOT_REGISTERED`, 유사 검색 결과, `VERIFICATION_UNAVAILABLE`는 캐시하지 않습니다.
등록 직후 재검증에서 이전 미등록/유사 후보가 남지 않으며, 장애 복구 후에도 즉시 재검증합니다.
캐시 키는 `verify:v3:`로 변경하여 이전 판정의 캐시를 사용하지 않습니다.

### 영상 VC 발급 상태

| 상태 | 의미 |
|------|------|
| `NOT_REQUESTED` | VC 발급을 아직 준비하지 않았거나 준비에 실패한 상태 |
| `PENDING_WALLET` | 서버 준비가 끝나 Wallet에서 사용자 발급을 기다리는 상태 |
| `ISSUED` | Wallet 발급이 완료되어 vcId가 영상에 연결된 상태 |

VC 완료 요청의 `offerId`는 해당 영상의 가장 최근 VC 발급 준비 응답에 포함된 값과 일치해야 합니다.

## 블록체인 배포 주의사항

`JinBon.sol`의 `register`와 `deactivate`는 컨트랙트를 배포한 지갑만 호출할 수 있습니다.
백엔드의 `WALLET_ADDRESS`와 keystore는 이 배포 지갑을 사용해야 합니다. 컨트랙트 조회 ABI에
signature가 추가되어 이전 버전 컨트랙트와 호환되지 않으므로, 기존 배포본을 사용하는 환경은
새 컨트랙트를 배포하고 `CONTRACT_ADDRESS`를 갱신해야 합니다.
