# Credential Vault — 운영 가이드

라인 활동이 외부 시스템에 접근할 때 쓰는 secret을 보관하는 작은 vault다. 평문을 그대로 DB에 두지 않고, 마스터 키 하나로 AES-GCM(Galois/Counter Mode — 변조 감지가 결합된 표준 대칭 암호) 256비트로 암호화해서 `U_LINE_CREDENTIAL.VALUE_ENC`에 저장한다. 활동이 실행되면 표현식 `{{ $credentials.<name>.value }}` 평가 시점에 한 번 복호화돼서 활동 메서드에 전달된다.

마스터 키 자체는 앱 안에 없다. 환경변수 `STATION8_CREDENTIAL_KEY`로 외부에서 주입한다. 이 한 줄이 vault의 안전성을 전부 결정하므로 키 관리 = vault 관리다.

DataSource 비밀번호와는 다른 영역이다. 라인 활동이 실행 *도중* 쓰는 secret(API 토큰 등)이 vault에 들어가고, 부팅 시 DB pool을 만들기 위한 DataSource 비번은 별도 layer다. 두 secret이 같은 단어를 공유하지만 라이프사이클과 해소 시점이 다르다. DataSource 쪽은 [#112](https://github.com/devyoon91/station8/issues/112)에서 별도 정리.

## 마스터 키 다루기

키를 만들 때:

```
openssl rand -base64 32
```

Base64로 인코딩된 32바이트가 나온다. 다른 길이를 넣으면 부팅 시 경고 로그가 찍히고, vault API를 호출하는 순간 `IllegalStateException`으로 실패한다. 앱 자체는 산다 — vault를 안 쓰는 환경에서 boot를 막지 않으려는 의도다.

주입 방법은 실행 형태에 따라:

- 로컬 jar: `STATION8_CREDENTIAL_KEY=... java -jar station8-app.jar`
- docker compose: `.env` 또는 `docker/.env`에 `STATION8_CREDENTIAL_KEY=...` 한 줄. compose의 `env_file`이 컨테이너에 넘긴다
- Kubernetes: `Secret` 리소스에 키를 두고 Pod spec의 `envFrom`이나 `env.valueFrom.secretKeyRef`로 매핑. 자세한 패턴은 K8s 공식 [Secret 가이드](https://kubernetes.io/docs/concepts/configuration/secret/#using-secrets-as-environment-variables) 참조

부팅 로그로 상태를 확인할 수 있다. 키가 정상이면 INFO 한 줄: `CredentialCrypto initialized — AES-GCM 256-bit key loaded`. 미설정이면 WARN: `STATION8_CREDENTIAL_KEY 미설정 — credential vault 호출 시 실패함`. 길이나 인코딩이 깨졌으면 WARN 메시지가 그 사유를 알려준다.

## 키 rotation

지금 코드는 키 하나만 본다. 무중단 rotation(앱이 떠있는 채로 키 교체)은 두 키 동시 보유와 일괄 재암호화 도구가 추가로 필요해서 별도 작업으로 빠져있다. 그래서 운영 절차는 두 가지로 나눠 정리한다.

### 무중단 — 향후 가능해질 절차

dual-key 도구가 추가된 뒤(별도 sub-issue로 등록 예정):

1. 새 키 생성. `openssl rand -base64 32`
2. `STATION8_CREDENTIAL_KEY_NEXT`에 새 키를 넣고 앱 재시작. 양쪽 키를 둘 다 메모리에 들고 있고, 복호화는 두 키 모두 시도하고 새 암호화는 새 키만 사용
3. 재암호화 endpoint 호출. 모든 활성 credential을 한꺼번에 옛 키로 복호화 → 새 키로 재암호화 후 저장. 영향 행 수를 미리 보는 dry-run 옵션을 같이 둘 것
4. `STATION8_CREDENTIAL_KEY`를 새 키로 교체, `STATION8_CREDENTIAL_KEY_NEXT`를 비우고 재시작
5. [`scripts/scenarios/10-credential-vault-audit.sh`](../scripts/scenarios/10-credential-vault-audit.sh)를 돌려 등록·해소가 정상인지 확인

### 다운타임 — 지금 가능한 방법

dual-key 도구가 없는 동안은 cold swap(앱을 내렸다가 키 바꿔 다시 띄우는 방식)이 유일한 안전한 길이다. 실행 중인 라인 인스턴스가 없을 때 해야 한다.

1. 실행 중인 활동을 모두 비운다. cron 비활성, in-flight 인스턴스가 자연 종료될 때까지 대기. `curl /api/line/instances?status=RUNNING`이 빈 배열인지 확인
2. 앱을 내린다
3. 임시 utility로 모든 active credential을 옛 키로 복호화 → 새 키로 재암호화. 본 PR에는 도구 미포함이라 — 운영 데이터가 거의 없을 때 키를 고정해두는 것이 가장 안전하다
4. `STATION8_CREDENTIAL_KEY`를 교체하고 앱 재시작
5. audit 스크립트로 확인 (위 §무중단의 step 5와 동일)

언제 누가 rotation 했는지는 운영팀의 외부 도구(Slack pin, Confluence 등)로 남긴다. 앱 안에는 아직 audit 로그 테이블이 없다.

## 키를 잃어버리면

복구 불가능하다. AES-GCM은 키 없이는 ciphertext에서 평문을 얻을 방법이 없다. 우회로가 있는 게 아니라 알고리즘의 설계 의도다.

복구 절차 자체는 단순하다 — 대신 다른 곳이 아프다:

1. 외부 서비스(Slack, AWS 등)에서 secret을 *재발급* 받는다. 옛 secret 자체를 폐기하는 효과가 있어 보안 측면에서도 권장
2. vault에 같은 이름으로 다시 등록한다. credential은 이름으로 참조되니까 라인 정의는 손대지 않아도 된다
3. 옛 행은 soft delete. 어차피 못 푼다

분실 방지는 키 자체를 한 군데에만 두지 않는 것이다. 흔한 패턴 몇 가지:

- 키와 DB 백업을 *다른 위치*에 둔다. DB dump에 키가 같이 들어가지 않게 분리
- 여러 운영자가 키 일부씩 나눠 갖는다. 수학적으로 N분할 후 K명 동의로 복원하는 방법이 표준화돼 있다 — [Shamir Secret Sharing](https://en.wikipedia.org/wiki/Shamir%27s_secret_sharing) 키워드로 찾아볼 수 있다
- 봉인 봉투 + 금고. 폐쇄망 사이트에서 흔한 클래식 방식
- QR이나 hex 인쇄본을 별도 위치에. 디지털 사본이 다 날아가도 종이가 남는다

`.env`는 `.gitignore`에 들어가 있긴 한데 실수로 commit하는 사고가 잦다. [`.env.example`](../.env.example)엔 `STATION8_CREDENTIAL_KEY=` 같이 빈 값만 두는 게 안전하다.

## 폐쇄망에서 키 운반

대부분 운영 사이트가 폐쇄망이라 AWS Secrets Manager 같은 SaaS 보안 저장소를 못 쓴다. 사이트 사정에 맞는 걸 고르면 된다:

- **컨테이너에 파일로 마운트.** Kubernetes `Secret`이나 Docker secret을 `/run/secrets/...`에 마운트하고 entrypoint 스크립트에서 `export STATION8_CREDENTIAL_KEY=$(cat /run/secrets/key)`. 운영 부담이 가장 적다
- **사내 Vault.** HashiCorp Vault self-hosted 같은 사내 시크릿 서버에서 부팅 시 fetch. init container나 sidecar가 envFile을 만들어 주입
- **Kubernetes Secret + envFrom.** etcd 자체 암호화 + RBAC로 보호한다. 백업은 etcd snapshot으로 따로
- **SOPS / age.** git에 *암호화된* secret만 commit하고 부팅 시 복호화. git-ops 친화적인 방식. SOPS는 Mozilla의 secret 관리 도구, age는 modern PGP 대체
- **수동 봉인 봉투.** 자동화는 0인 대신 키 노출면이 가장 작다. 부팅 직전 운영자가 envFile에 한 줄 넣고 부팅 후 디스크에서 지운다. 다음 부팅 시 다시 필요

사이트별로 점검할 것들:

- 이미지(Dockerfile / git / 산출물 S3 등) 어디에도 키가 박혀있지 않은가
- 부팅 로그에 키 평문이 찍히지 않는가. `CredentialCrypto`는 길이만 찍고 키 자체는 안 찍지만, 운영자가 만든 entrypoint나 다른 init 스크립트가 `echo $STATION8_CREDENTIAL_KEY` 같이 쓰지 않도록
- DB 백업과 키 백업이 분리돼 있는가. 같은 매체에 같이 두면 분실/유출이 함께 일어난다
- BCP drill을 1회는 해봤는가. 가짜 키로 분실 시나리오를 dry-run

## 응답·로그에 평문이 안 나가는 것

응답 쪽은 코드에서 보장한다.

POST/GET 응답은 `CredentialResponse` record로 직렬화되는데 이 record 자체에 `value`나 `valueEnc` 필드가 없다. 직렬화될 수 있는 형태가 애초에 존재하지 않는 것이다.

표현식 평가 중 복호화된 평문은 `ProxyObject`(GraalVM JS 호출이 닿을 수 있게 Java 객체를 감싼 래퍼) 안 호출 스택에 잠시 머물다가 JVM GC가 정리한다. Java String을 명시적으로 wipe할 방법은 없는데, 이건 JVM 표준 한계다.

평가 실패 시 errorMessage는 `CredentialCryptoException`의 generic 메시지만 들어간다. ciphertext나 평문 일부는 메시지에 포함되지 않는다.

Sandbox는 `HostAccess.NONE`. 표현식이 `$credentials.x.getClass()` 같이 Java reflection으로 빠져나가는 길을 막아둔다. 자세한 sandbox 정책은 [`docs/decisions/m16-expression-engine.md`](decisions/m16-expression-engine.md).

회귀 가드는 [`scripts/scenarios/10-credential-vault-audit.sh`](../scripts/scenarios/10-credential-vault-audit.sh). 매 릴리즈 전 한 번 돌리면 위 보장이 깨졌는지 잡힌다.

빠진 부분은 정직하게 적어둔다.

- inputData에는 평문이 박힌다. 표현식이 `{{ $credentials.x.value }}`를 inputParams에 쓰면 평가 결과가 `U_LINE_ACTIVITY.INPUT_DATA`에 그대로 저장된다. 이건 expression engine의 설계 의도다 — 활동 메서드가 받는 input은 평문이어야 동작 가능. 운영 측면에서는 inputData에 접근하는 채널(DB, 운영 UI)에 별도 ACL이 필요하다
- 앱 표준출력과 파일 로그는 audit 스크립트가 직접 검사하지 않는다. 컨테이너냐 jar 직접 실행이냐에 따라 위치가 달라서 — 사이트별로 운영자가 `grep <sentinel> /var/log/...` 같이 확인
- 메모리 dump(heap dump)에는 평문이 존재할 수 있다. 위 GC 한계와 같은 이유다. dump 채취 권한을 vault 운영 권한과 분리하는 ACL이 우선이다
