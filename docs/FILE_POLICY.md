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
- **SFTP / S3는 본 정책 적용 안 됨** — 별도 backend의 자체 권한 모델. 본 정책은 `file://` 한정. SFTP/S3는 [#296](https://github.com/devyoon91/station8/issues/296) / [#297](https://github.com/devyoon91/station8/issues/297)에서 같은 패턴으로 추가될 예정

## 관련 파일

- 정책 구현: [`station8-engine/.../core/builtin/file/FilePathPolicy.java`](../station8-engine/src/main/java/com/station8/engine/core/builtin/file/FilePathPolicy.java)
- 호출 자리: [`LocalFileSystem.read/write`](../station8-engine/src/main/java/com/station8/engine/core/builtin/file/LocalFileSystem.java) — `Files.readAllBytes`/`Files.write` 직전
- 회귀 가드: [`FilePathPolicyTest`](../station8-engine/src/test/java/com/station8/engine/core/builtin/file/FilePathPolicyTest.java) — 각 차단 카테고리 / canonical path / 미존재 path
- 비슷한 패턴 — HTTP쪽: [`HTTP_POLICY.md`](HTTP_POLICY.md)
