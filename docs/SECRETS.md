# Credential Vault — 운영 가이드

라인 활동이 외부 시스템에 접근할 때 쓰는 secret을 보호한다. AES-GCM 256-bit으로 암호화해 `U_LINE_CREDENTIAL.VALUE_ENC`에 저장, 활동 실행 시 `{{ $credentials.<name>.value }}` 표현식으로 평문 1회 해소. 마스터 키(`STATION8_CREDENTIAL_KEY`)만 외부에서 주입한다.

> DataSource 비밀번호 보안은 별도 layer다. 둘 다 "secret"이지만 라이프사이클이 다르다 — 자세한 비교는 #112.

---

## 1. 마스터 키 — `STATION8_CREDENTIAL_KEY`

### 키 생성

```bash
openssl rand -base64 32
```

Base64 인코딩된 정확히 32 bytes (256-bit). 길이가 다르면 부팅 WARN + vault 호출 시 `IllegalStateException`.

### 키 주입

| 실행 형태 | 방법 |
|---|---|
| local jar | `STATION8_CREDENTIAL_KEY=... java -jar station8-app.jar` |
| docker compose | `./.env` 또는 `docker/.env`에 `STATION8_CREDENTIAL_KEY=...` (env_file이 컨테이너에 전달) |
| k8s | `Secret` 리소스 생성 후 `envFrom` 또는 single `env.valueFrom.secretKeyRef`로 매핑 |
| 폐쇄망 sealed envelope | 부팅 직전 운영자가 envFile에 한 줄 추가, 부팅 후 디스크에서 제거 (다음 부팅 시 재주입 필요) |

### 부팅 동작

| 상태 | 로그 | vault API |
|---|---|---|
| 키 정상 (32 bytes Base64) | `INFO CredentialCrypto initialized — AES-GCM 256-bit key loaded` | 정상 |
| 키 미설정 | `WARN STATION8_CREDENTIAL_KEY 미설정 — credential vault 호출 시 실패함` | 500 (`IllegalStateException`) |
| 길이 / Base64 오류 | `WARN STATION8_CREDENTIAL_KEY 길이 오류` 또는 `valid Base64가 아님` | 500 (위와 동일) |

키가 없어도 앱 자체는 부팅 — credential vault를 안 쓰는 환경/테스트에서 boot 차단 안 되게 한 의도적 선택.

---

## 2. 키 rotation 절차

### 현재 구현 상태

코드에는 **단일 키 (`STATION8_CREDENTIAL_KEY`) 로딩만** 구현됨. 무중단 hot rotation은 `STATION8_CREDENTIAL_KEY_NEXT` dual-key 지원 + 일괄 재암호화 endpoint 도입 후 가능 — 별도 sub-issue로 정리.

본 섹션은 **target 절차 (A)** 와 **다운타임 fallback (B)** 둘 다 기재한다.

### A. 무중단 hot rotation — target

dual-key 지원이 도입되면 (별도 sub-issue):

1. **새 키 생성**
   ```bash
   openssl rand -base64 32 > /run/secrets/credential.key.new
   ```
2. **`STATION8_CREDENTIAL_KEY_NEXT` 환경변수 셋, 앱 재시작 (또는 hot reload)**
   - 양 키 메모리 동시 보유. decrypt는 `KEY` 먼저 시도 → 실패 시 `KEY_NEXT`. encrypt는 `KEY`만 사용
3. **일괄 재암호화**
   - `POST /api/admin/credentials/_rotate-keys` (dry-run 옵션으로 영향 row 미리 확인 가능)
   - 또는 CLI: `./gradlew :station8-app:credentialRotate`
   - 모든 active 행에 대해 decrypt(KEY) → encrypt(KEY_NEXT) → update
4. **키 swap**
   - `STATION8_CREDENTIAL_KEY` ← 새 키, `STATION8_CREDENTIAL_KEY_NEXT` 제거. 앱 재시작
5. **검증**
   ```bash
   bash scripts/scenarios/10-credential-vault-audit.sh
   # 새 credential 등록 + 기존 credential 해소가 정상 동작하는지
   ```

### B. 다운타임 cold rotation — 현재 가능한 fallback

dual-key 미도입 시점에 유일한 옵션. 실행 중인 라인 인스턴스가 없을 때만 안전.

1. **drain** — 실행 중 활동 0 확인
   ```bash
   curl "$BASE_URL/api/line/instances?status=RUNNING" | jq '. | length'
   # 0이 될 때까지 대기 (혹은 cron 비활성 후 in-flight 자연 종료)
   ```
2. **앱 shutdown**
3. **DB 직접 재암호화** — 임시 utility로 모든 active credential에 대해 `decrypt(OLD_KEY)` → `encrypt(NEW_KEY)` → UPDATE
   > 본 PR에 utility 미포함. 도입 전 운영 데이터 0인 환경에서만 안전하게 키 fix 가능.
4. **`STATION8_CREDENTIAL_KEY` 교체, 앱 재시작**
5. **검증** — 위 (A.5)와 동일

### 변경 이력 추적

매 rotation에 운영 로그를 남길 것 — 키 자체는 아니지만 "언제 누가 rotation 했나"는 BCP 추적에 필수. `H_LINE_AUDIT` (별도 sub-issue) 도입 전에는 운영팀 외부 도구 (Slack pin / Confluence 등) 활용.

---

## 3. BCP — 키 분실 시

마스터 키 분실 = **모든 credential 영구 복구 불가**. AES-GCM은 키 없이 ciphertext에서 평문 복원 불가능 — 이건 알고리즘의 설계 의도라 우회로 없다.

### 분실 발생 시 복구 절차

1. **외부에서 secret 재발급** — Slack token, AWS API key 등 외부 서비스에서 새 값 받아옴
2. **vault에 같은 이름으로 재등록** — credential은 이름으로 참조되므로 라인 정의는 변경 불필요
3. **이전 row soft delete** — `DELETE /api/line/credentials/{id}` (DEL_FL='Y'). 평문 복구 불가라 archive 의미 없음

### 분실 방지

| 방법 | 비고 |
|---|---|
| 별도 매체 backup | vault 데이터(`U_LINE_CREDENTIAL`)와 마스터 키를 **다른 위치**에 보관. DB 백업에 키 포함 금지 |
| 다중 운영자 보관 | Shamir Secret Sharing 등으로 키를 N분할, K명 동의 시 복원 |
| sealed envelope | 키 적힌 봉인 봉투를 금고에 — 폐쇄망 사이트의 클래식 패턴 |
| paper backup | QR / hex 인쇄본을 별도 위치에 (디지털 사본 분실 대비) |

**git에 키 평문을 절대 두지 말 것.** `.env`는 `.gitignore`에 있지만 실수로 commit 가능 — `.env.example`은 placeholder만 (`STATION8_CREDENTIAL_KEY=`).

---

## 4. 폐쇄망 환경 — secret 운반

폐쇄망 사이트는 AWS Secrets Manager / GCP Secret Manager / Azure Key Vault 같은 SaaS를 못 씀. 운반 옵션:

| 방법 | 운영 부담 | 자동화 가능 |
|---|---|---|
| **마운트 파일** | 낮음 | k8s/docker secret으로 마운트, entrypoint가 `export STATION8_CREDENTIAL_KEY=$(cat /run/secrets/key)` |
| **사내 Vault (self-hosted)** | 중간 | HashiCorp Vault on-prem 등, init container/sidecar가 부팅 시 fetch |
| **K8s Secret + envFrom** | 낮음 | etcd 암호화 + RBAC로 보호. backup은 etcd snapshot |
| **sealed envelope + 수동 주입** | 높음 | 부팅 직전 envFile 추가, 부팅 후 제거. 자동화 0 |
| **SOPS / age** | 중간 | git-ops 친화 — 암호화된 secret만 commit, 부팅 시 decrypt |

### 폐쇄망 체크리스트

- [ ] 이미지에 키 절대 박지 않음 (Dockerfile / git / S3 등)
- [ ] 로그에 키 출력 안 함 (InitialAdmin 같은 부팅 로그에 평문 출력 금지 — `CredentialCrypto` 코드에서 이미 보장)
- [ ] backup은 키와 데이터를 **분리** — DB dump에 키가 안 들어가게 (env 별도 보관)
- [ ] rotation 절차를 사이트 runbook에 명시 — 위 §2.A 또는 §2.B
- [ ] BCP 키 분실 시나리오 drill 1회 — 가짜 키로 분실 → 재발급 → 재등록 cycle을 dry-run

### DataSource 비밀번호와의 관계 (#112)

본 vault의 마스터 키는 DataSource (`U_LINE_DATASOURCE.PASSWORD`) 비밀번호와 **다른 layer**다.

| | credential vault | DataSource 비번 |
|---|---|---|
| 저장 위치 | `U_LINE_CREDENTIAL.VALUE_ENC` (AES-GCM) | `U_LINE_DATASOURCE.PASSWORD` (plain text 현재) |
| 사용 시점 | 활동 실행 중 표현식 평가 | 앱 부팅 + DataSource pool 생성 |
| 보호 메커니즘 | M17 vault (본 문서) | 별도 — #112에서 통합 가이드 |
| 분실 영향 | credential 재발급 가능 | DataSource 재설정 (외부 DB 비번 교체) |

DataSource 비번 통합 가이드는 #112에서 다룬다. 폐쇄망 운영자는 두 layer 모두 위 4.1의 운반 방법을 적용 — 통일된 secret 운반 채널이 가장 운영 부담이 낮다.

---

## 5. 응답 / 로그 무누출 보장

### 코드 레벨

| 경로 | 보장 메커니즘 |
|---|---|
| `POST /api/line/credentials` 응답 | `CredentialResponse` record에 `valueEnc` / `value` 필드 자체가 없음 — 직렬화 시 노출 불가 |
| `GET` 응답 (list / single) | 위와 동일 |
| 표현식 평가 중 평문 | `ProxyObject` 내부 호출 스택에 잠시 존재 → JVM GC가 정리 (Java String은 명시적 wipe 불가 — 구조적 한계) |
| 평가 실패 errorMessage | `CredentialCryptoException` message에 ciphertext / plaintext 포함 안 함. cause는 보존하되 표시는 generic |
| Sandbox escape | `ExpressionEvaluator`의 `HostAccess.NONE` — `$credentials.x.getClass()` 등 Java reflection 차단 |

### 회귀 가드

`scripts/scenarios/10-credential-vault-audit.sh` — POST/GET 응답 + 활동 실행 outputData + errorMessage에 등록한 평문이 등장 0건임을 grep 기반으로 검증. 매 릴리즈 전 1회 실행 권장.

### 비범위

- **inputData**: 표현식이 `{{ $credentials.x.value }}`를 inputParams에 쓰면 평문이 `U_LINE_ACTIVITY.INPUT_DATA`에 그대로 박힘. 이건 expression engine 설계 의도 — 활동 메서드가 받는 input은 평문이어야 동작 가능. 운영 측면에서는 inputData가 노출되는 채널(DB 접근, 운영 UI)에 별도 ACL 필요
- **앱 stdout/파일 로그**: 컨테이너/실행 형태마다 위치가 달라 audit 스크립트가 직접 scan 안 함. 운영자가 사이트별로 `grep <sentinel> /var/log/...`로 확인
- **메모리 dump**: heap dump에 평문 존재 — JVM 표준 한계. dump 채취 권한을 vault 운영자와 분리하는 ACL이 우선

---

## 관련 이슈

- [#248](https://github.com/devyoon91/station8/issues/248) — M17 epic
- [#270](https://github.com/devyoon91/station8/issues/270) — Credential 엔티티 + AES-GCM crypto
- [#271](https://github.com/devyoon91/station8/issues/271) — CRUD API
- [#272](https://github.com/devyoon91/station8/issues/272) — `$credentials` polyglot binding
- [#273](https://github.com/devyoon91/station8/issues/273) — 본 문서 + audit 스크립트
- [#112](https://github.com/devyoon91/station8/issues/112) — DataSource 비번 보안 (별도 layer)
