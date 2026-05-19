# 파일 접근 정책 — Path Traversal 방어

라인이 `file.read` / `file.write` 활동으로 로컬 파일을 만질 때, 라인 작성자가 의도적이든 실수든 `/etc/passwd` 같은 시스템 경로나 운영자 의도 밖 디렉토리를 건드리지 못하게 막는 layer. SSRF에서 `NetworkPolicy`가 하는 역할의 파일 버전이다.

이 검증을 `FilePathPolicy`에 모았다. 활동이 read/write 직전에 `policy.check(path)`를 호출, 위반이면 `FilePathPolicyViolationException`을 던져 활동이 즉시 final-fail로 간다 (재시도 안 됨).

## 기본 동작 — fail-closed

`station8.file.local.allowed-roots` 비어있으면 **local file 활동 자체가 비활성**. 어떤 `file://` URI를 줘도 차단된다. 운영자가 의식적으로 디렉토리를 명시하기 전에는 아무것도 못 만지게 한 default다.

부팅 로그에 현재 상태가 INFO 한 줄로 찍힌다:

```
FilePathPolicy: allowed-roots=(empty) — local file 활동 모두 차단됨. station8.file.local.allowed-roots 로 디렉토리 지정 필요.
```

또는 설정이 있을 때:

```
FilePathPolicy: allowed-roots=[/var/station8/inbox, /var/station8/outbox]
```

## 허용 디렉토리 지정

```properties
station8.file.local.allowed-roots=/var/station8/inbox,/var/station8/outbox
```

csv. trim 처리되고 빈 항목은 무시. 각 경로는 절대 path여야 한다. 라인이 부르는 모든 read/write path가 위 디렉토리들 중 하나 안에 있어야 통과.

## 차단되는 패턴

`check(path)`가 위반으로 보는 케이스:

- **allowed-roots 밖**: `/etc/passwd`, `/var/log/...` 등이 root에 없으면 차단
- **`../` escape**: `/var/station8/inbox/../../etc/passwd` 같이 traversal로 root 밖을 노리는 경우. canonical path 비교로 잡힘
- **symlink escape**: `/var/station8/inbox/evil-link → /etc/shadow` 같은 symlink. `toRealPath()`로 해소 후 root 안인지 다시 검사

존재하지 않는 path(write target)도 검사된다 — 가장 가까운 존재하는 ancestor를 canonical 해소한 뒤 그 결과가 root 안인지로 판단. 그래서 운영자가 root 안의 새 하위 디렉토리에 write하는 정상 시나리오는 통과한다.

## 운영 가이드

- **사이트별로 inbox / outbox 두 디렉토리만 두는 패턴** 권장. 라인이 read는 inbox에서, write는 outbox에. 운영자가 cron이나 다른 수단으로 inbox에 파일을 떨어뜨리고 outbox에서 결과를 꺼냄
- **여러 라인이 같은 디렉토리를 공유하면 파일명 충돌**에 주의. `result-{{ $ctx.run.id }}.json` 처럼 인스턴스 ID를 path에 박는 표현식이 흔한 패턴
- **DB dump처럼 큰 파일**은 본 layer로 다루지 말 것. M22 item-level streaming이 들어오기 전엔 메모리에 통째로 올린다 (`Files.readAllBytes`) — 큰 파일은 OOM 위험
- **권한 모델**: 본 정책은 *경로* 만 본다. OS 사용자 권한(파일 소유자 / 그룹)은 별도. 컨테이너에서 app이 root나 unprivileged user로 도는지에 따라 실제 접근이 또 막힐 수 있음

## 알려진 잔여 위험

- **TOCTOU**: 검증 시점에 path가 안전해도 그 직후 누군가가 root 안에 symlink를 만들어 외부로 가리키면 우회 가능. 운영 환경의 디렉토리 권한 관리로 방어 (root만 쓰기 가능 + sticky bit 등)
- **상대 경로 모호함**: 본 정책은 절대 path만 받는다. 라인 정의에서 `subdir/file.txt` 같은 상대 path를 주면 `FileSystemRegistry`가 거부 (어떤 디렉토리 기준인지 ambiguous)
- **SFTP / S3는 본 정책(`file://` 한정) 적용 안 됨** — 각자 자기 backend의 권한/인증 모델. SFTP는 아래 별도 섹션, S3는 후속 sub-issue

## SFTP backend

`sftp://user@host:port/abs/path` 형태. 인증은 활동 입력의 `credentialId`로 vault에서 조회 — URI에 평문 password 절대 박지 말 것 (라인 정의, 로그, inputData 등 여러 경로에 URI가 노출됨).

### credential type

두 가지:

- **`sftp_password`** — schema는 비어도 됨 (또는 `{"username":"..."}` 로 URI user override). credential value = password
- **`sftp_key`** — schema에 `{"privateKey":"-----BEGIN OPENSSH PRIVATE KEY-----..."}`. credential value = passphrase (선택 — unencrypted key면 빈 값)

URI user → schema.username 순서로 SSH 사용자 결정. 둘 다 없으면 거부.

### known_hosts — fail-closed default

서버 host key를 검증하는 OpenSSH 표준 파일 위치를 `station8.file.sftp.known-hosts` 로 명시. 빈 값이면 **모든 SFTP 연결 거부** — TOFU (Trust On First Use) 우회를 막기 위해 명시적으로 fail-closed.

운영자가 사이트별 SFTP 서버 fingerprint를 미리 박아둬야 한다:

```
[sftp.internal.com]:22 ssh-rsa AAAAB3NzaC1yc2EAAAAD...
```

서버 fingerprint를 모르면 한 번 수동으로 `ssh-keyscan -p 22 sftp.internal.com` 같이 떠서 박을 것. fingerprint가 바뀌면 (서버 교체 / 키 교체) 본 정책상 자동으로 차단된다 — 운영자가 의식하지 않은 서버 교체는 보통 사고이므로 의도된 차단.

### timeout

- `station8.file.sftp.connect-timeout-ms` — TCP 연결 + SSH 핸드셰이크. default 10000
- `station8.file.sftp.auth-timeout-ms` — 인증 완료까지. default 10000

network slow / 큰 SFTP 서버에서는 늘리는 게 안전. 큰 파일 transfer 자체에는 SftpClient가 자체 timeout 관리.

### 연결 lifecycle

현재 활동 호출마다 connect + auth + transfer + disconnect. 작은 파일에는 충분하지만 잦은 호출에서는 connection 비용이 보임. connection pooling은 별도 follow-up.

### 잔여 위험

- **passphrase가 메모리에 평문**: vault에서 해소 → `FilePasswordProvider` 람다에 들어감 → MINA SSHD가 키 로드에 사용. Java String은 명시적 wipe 불가 (M17과 같은 한계)
- **SFTP 서버측 권한**: 본 layer는 인증과 host key만. 실제 read/write 권한은 서버 OS 사용자 권한에 좌우. 서버에 chroot jail이나 사용자 격리가 별도로 필요한 사이트는 SFTP 서버 측에서 설정
- **HostKey 알고리즘 정책**: 현재 SSHD default (RSA / ECDSA / Ed25519 등 다 허용). 사이트가 알고리즘 제한이 필요하면 SshClient 생성 시 정책 주입 — 현재는 노출 안 함, 운영 보고 후 도입 검토

## S3 backend (S3-compatible 포함)

`s3://bucket/key/path` 형태. AWS S3 외에 같은 API를 구현한 self-hosted 제품(MinIO, Ceph RadosGW 등)도 같은 코드로 cover — credential schema의 `endpoint` 만 사이트 endpoint로 override 하면 된다. 폐쇄망 사이트에서는 거의 항상 MinIO 같은 사내 객체저장소를 가리킨다.

### credential type

**`s3_access_key`** 한 가지. schema 필드:

| 필드 | 필수 | 비고 |
|---|---|---|
| `accessKeyId` | yes | AWS / MinIO Access Key ID |
| `endpoint` | (MinIO/Ceph) | `https://minio.internal:9000` 같은 사이트 endpoint. 비우면 AWS 표준 endpoint (region 기반) |
| `region` | recommended | `us-east-1` default. MinIO에선 임의 region OK (보통 `us-east-1`로) |
| `pathStyle` | (MinIO/Ceph) | true면 `endpoint/bucket/key` 형식. virtual-host style 안 받는 self-hosted는 반드시 true |

credential value = SecretAccessKey.

AWS EC2 instance profile / EKS Pod Identity로 자동 인증하는 시나리오(access key를 vault에 안 둠)는 본 sub-issue 비범위. 별도 follow-up.

### URI 처리

- `s3://bucket/key/path` — bucket은 URI host, key는 path (leading slash 제거)
- bucket 또는 key 누락 시 즉시 `NoRetryException`
- S3 key는 prefix 개념 — `s3://bucket/inbox/orders/2025-01-15.json` 같이 깊은 path OK. 디렉토리 list는 본 sub-issue 비범위 (단일 객체만)

### 재시도 / 예외 분류

- HTTP 4xx (`NoSuchBucket`, `NoSuchKey`, `AccessDenied` 등) → `NoRetryException`
- HTTP 5xx → 일반 RuntimeException, 엔진 재시도
- 네트워크 timeout / connection failure → 일반 RuntimeException, 재시도 대상

### 잔여 위험

- **S3Client per call**: 활동 호출마다 새 `S3Client` 생성 + close. 작은 객체는 OK지만 잦은 호출은 client lifecycle 비용 있음. credential별 캐싱은 별도 follow-up
- **multipart upload**: 큰 파일(>100MB)은 `RequestBody.fromBytes`로 통째로 메모리에 들어가서 OOM 위험. multipart streaming은 M22와 묶여서 별도 sub-issue
- **객체 list / prefix dispatch**: `s3://bucket/inbox/` (trailing slash)로 prefix 아래 list하는 모드 미구현. 사용자는 외부에서 key 목록을 받아 단일 객체 호출을 반복하는 게 현재 패턴
- **server-side encryption / KMS**: 별도 sub-issue. 현재 객체는 sever-side default 정책 따름 (보통 AES256)

## 관련 파일

- 정책 구현: [`station8-engine/.../core/builtin/file/FilePathPolicy.java`](../station8-engine/src/main/java/com/station8/engine/core/builtin/file/FilePathPolicy.java)
- 호출 자리: [`LocalFileSystem.read/write`](../station8-engine/src/main/java/com/station8/engine/core/builtin/file/LocalFileSystem.java) — `Files.readAllBytes`/`Files.write` 직전
- 회귀 가드: [`FilePathPolicyTest`](../station8-engine/src/test/java/com/station8/engine/core/builtin/file/FilePathPolicyTest.java) — 각 차단 카테고리 / canonical path / 미존재 path
- 비슷한 패턴 — HTTP쪽: [`HTTP_POLICY.md`](HTTP_POLICY.md)
