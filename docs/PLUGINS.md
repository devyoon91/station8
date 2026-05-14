# Plugins — Operator Guide

외부 jar로 제공되는 액티비티의 **업로드 / 활성화 / 디버깅 운영 매뉴얼** (#105 D1=b로 분리). 본 문서는 호스트를 운영하는 사람 대상.

> **플러그인 코드를 작성하는 분이라면** → [PLUGIN_DEVELOPMENT.md](PLUGIN_DEVELOPMENT.md) 참조 (`@Activity` 스펙 + 스타터 + 단위 테스트 패턴).

## 1. 빠른 시작

### 1) 플러그인 활성화

`application.properties`:

```properties
engine.plugins.enabled=true
engine.plugins.dir=plugins
```

### 2) 플러그인 jar 배포

```
work-dir/
├── station8-app.jar
├── application.properties
└── plugins/
    ├── notification-plugin.jar
    └── analytics-plugin.jar
```

### 3) 앱 재시작

부팅 로그에서 다음 메시지 확인:

```
Plugin loaded: notification-plugin.jar (3 activities)
Registered plugin activity: SEND_SLACK -> NotificationPlugin.sendSlack
Registered plugin activity: SEND_EMAIL -> NotificationPlugin.sendEmail
Plugin scan complete: 2 loaded, 0 failed
```

`/line/activities`에서 등록된 액티비티를 확인할 수 있다.

## 2. 플러그인 jar 작성

플러그인 코드를 직접 작성하실 경우 [PLUGIN_DEVELOPMENT.md](PLUGIN_DEVELOPMENT.md)로 이동.

본 문서는 **이미 빌드된 jar를 받았다**는 가정 하에 호스트 적용 절차만 다룹니다.

요약 — jar가 만족해야 하는 최소 요건 (받은 jar를 검증할 때):
- `@Activity("NAME")` 메서드가 있는 public 클래스 1개 이상
- 해당 클래스의 **public no-arg 생성자**
- 매직 바이트 `PK\x03\x04`로 시작 (정상 zip/jar)
- 크기 ≤ 50MB (어드민 업로드 한도)
- 코어/Spring 의존성 미포함 (호스트가 제공 — 포함되면 충돌 가능)

## 3. ClassLoader 정책

- 각 jar는 부모(앱 ClassLoader)를 위임 부모로 갖는 `URLClassLoader`로 격리 로드.
- 플러그인이 **코어/Spring 클래스를 참조하면 부모 위임으로 해결** → 충돌 없음.
- **플러그인끼리 동일 라이브러리의 다른 버전을 사용하면 한쪽이 우선**됨 (현재는 isolation 미지원).
- 의존 격리가 필요하면 `shadow`(Maven shade) 패턴으로 의존성을 relocate하여 충돌 회피.

## 4. 충돌 / 우선순위

- 동일 `@Activity("NAME")` 이름이 코어 또는 다른 플러그인에 이미 등록되어 있으면 **나중에 등록되는 쪽이 무시**됨.
- 부팅 로그에서 다음 경고 확인:

```
WARN  c.b.w.engine.core.LineRegistry - Activity name conflict: SEND_SLACK already registered → 플러그인 등록 스킵
```

운영자 액션:
- 충돌 액티비티의 이름을 플러그인 jar에서 변경하거나, 코어 빈을 비활성화.

## 5. 운영 시나리오

### 5-1. 새 플러그인 추가

**(a) 어드민 웹 업로드 (#102)** — 호스트 파일시스템 접근 불필요:

1. `/admin/plugins` 접속 → **Upload jar** 폼에서 jar 선택 → Upload.
2. 검증 통과 시(확장자 `.jar` + 매직 바이트 + 50MB 이하) `engine.plugins.dir`에 저장. 같은 이름이 있으면 기존은 `.bak`로 백업.
3. `systemctl restart workflow-engine` (또는 `docker compose restart app`) — 본 이슈 비범위, hot reload는 [#103](https://github.com/devyoon91/station8/issues/103).
4. `/line/activities`에서 등록 확인 + 빌더에 노출.

> 인증: ADMIN 역할 필요 (#121). 로그인 후 nav의 **Plugins** 메뉴 또는 직접 URL 접근.

**(b) 호스트 파일시스템 직접 (전통)** — ssh/docker cp 권한 보유 시:

1. 운영 호스트의 `plugins/` 디렉토리에 jar 업로드 (`scp` / `docker cp` / 마운트).
2. `systemctl restart workflow-engine` (또는 `docker compose restart app`).
3. 부팅 로그에서 등록 확인 + `/line/activities` 노출.

### 5-2. 플러그인 비활성화

```properties
engine.plugins.enabled=false
```

또는 jar를 `plugins/` 외부로 이동 후 재시작.

### 5-3. 핫 리로드 (#103)

`/admin/plugins`의 **↻ Reload now** 버튼 또는 `POST /admin/plugins/reload`로 앱 재시작 없이 `plugins/` 디렉토리를 다시 스캔.

**Add only 모드 (D1=a):**
- 새로 발견된 `@Activity`만 등록. 기존 등록은 변경 없음.
- 같은 이름이 이미 등록되어 있으면 conflict로 분류 + WARN 로그 + skip.
- "v2 → v3 교체"는 본 모드에서 지원 안 함 — 재시작 또는 별개 후속 이슈에서.

**ClassLoader 라이프사이클 (D2=b):**
- 매 reload마다 모든 jar에 새 `URLClassLoader` 생성.
- 새 등록이 0건인 jar(이미 등록 끝났거나 conflict로 모두 skip된 경우)는 즉시 close → 메모리 누적 최소화.
- 새로 등록된 jar의 ClassLoader는 활동 인스턴스가 reference하므로 JVM 종료까지 살아있음.

**응답 분류 (D6):**
- `added` — 이번 reload에서 새로 등록된 액티비티 이름
- `conflicts` — 같은 이름이 이미 있어 무시된 액티비티
- `skippedJars` — 새 등록 0건이었던 jar (다음 reload에도 동일)
- `failedJars` — 로드/스캔 실패 jar + 사유

**동시성:** reload는 `synchronized`로 직렬화. 동시 호출 시 두 번째는 첫 번째 종료까지 대기.

**자동 트리거 미지원:** 명시적 버튼/API 호출만. 파일시스템 watcher는 비범위.

## 6. 디버깅

### 플러그인이 인식 안 됨

- `engine.plugins.enabled=true` 확인.
- `plugins/` 경로가 작업 디렉토리 기준 상대 경로 또는 절대 경로인지 확인 (디폴트는 `plugins`).
- jar 안에 `@Activity` 메서드가 있는 public 클래스가 있는지 확인.
- 부팅 로그에서 `Failed to load plugin: ...` WARN 메시지 확인 → 의존 누락 / 클래스 로드 실패 원인.

### NoClassDefFoundError

플러그인이 사용하는 라이브러리가 코어에 없을 때 발생. 해결책:
- jar 안에 의존성을 포함(uber/shadow jar).
- 또는 해당 라이브러리를 `station8-app/build.gradle`에 추가.

### 동일 라이브러리 버전 충돌

플러그인 A는 v2, 플러그인 B는 v3 사용 시 한쪽이 우선됨. 해결책:
- shadow jar로 패키지 relocation (`com.example.shaded.guava` 등).
- 또는 두 플러그인이 호환되는 동일 버전 사용.

## 7. 보안

- 플러그인은 호스트와 동일한 권한으로 실행됨 → **신뢰할 수 있는 jar만 배포**.
- 외부 출처 jar는 SHA-256 검증 후 배포 권장.
- SecurityManager 기반 샌드박싱은 현재 미지원 (Java 17+에서 deprecated).

## 8. 관련 문서

- [PLUGIN_DEVELOPMENT.md](PLUGIN_DEVELOPMENT.md) — **플러그인 개발자**용 (`@Activity` 스펙 + 스타터)
- [HOWTO.md](HOWTO.md) — 액티비티 작성 일반 가이드
- [line-engine-spec.md](line-engine-spec.md) — `PluginLoader` 아키텍처
- [`examples/plugin-starter/`](../examples/plugin-starter/) — 컴파일 가능한 최소 스켈레톤
