# API 문서

`station8-app`의 컨트롤러에서 추출한 엔드포인트 레퍼런스. REST API(JSON 응답)와 페이지 뷰(Mustache 템플릿 렌더)를 함께 정리한다. 각 표의 "요청/응답" 열은 코드에서 확인된 DTO·파라미터·반환 타입만 적었고, 확인되지 않은 부분은 "코드상 미확인"으로 표기한다.

에러 코드는 [에러 코드 카탈로그](../../ERROR_CODES.md) 참고. REST 예외는 대부분 `GlobalRestExceptionHandler`가 표준 `ErrorResponse`로 변환한다.

## 인증 · 권한

인증·인가는 [`SecurityConfig`](../../../station8-app/src/main/java/com/station8/app/security/SecurityConfig.java)와 `@PreAuthorize` 메서드 시큐리티(`@lineAcl` bean)로 이중 구성된다.

**폼 로그인** — `/login` 페이지 + Spring Security `UsernamePasswordAuthenticationFilter`. 성공 시 `/line/dashboard`로 이동, 로그아웃 시 `/login?logout`. 비밀번호는 BCrypt.

**경로 정책** (`SecurityConfig`):

| 경로 | 정책 |
|------|------|
| `/login`, `/css/**`, `/js/**`, `/favicon.svg`, `/error` | permitAll |
| `/admin/**` | `hasRole('ADMIN')` |
| `/me/**` | 인증 필요 |
| 그 외 (`/line/**`, `/api/**` 등) | permitAll (#159 점진 적용 — 1차에선 열림) |

`/api/**`는 CSRF 면제(fetch 호출용). 그 외 폼 POST는 `_csrf` 토큰 필요.

**라인 정의 ACL** — [`LineAclService`](../../../station8-app/src/main/java/com/station8/app/security/LineAclService.java) (`@Service("lineAcl")`). 정의 단위 권한을 SpEL로 평가한다. `SecurityConfig`가 `/line/**`·`/api/**`를 열어두므로(#159), 실질 인가는 아래 메서드 레벨 `@PreAuthorize`가 담당한다.

| SpEL | 권한 |
|------|------|
| `@lineAcl.canRead(#id)` | READ |
| `@lineAcl.canWrite(#id)` | WRITE |
| `@lineAcl.canExecute(#id)` | EXECUTE |
| `@lineAcl.canSchedule(#id)` / `canScheduleByScheduleId(#id)` | SCHEDULE |
| `@lineAcl.canAdmin(#id)` | ADMIN |
| `@lineAcl.canExecuteInstance(#instanceId)` | 인스턴스 → 정의로 역해석 후 EXECUTE |

인증만 요구하는 엔드포인트는 `isAuthenticated()`, ADMIN 전용은 `hasRole('ADMIN')`을 쓴다. `@PreAuthorize`가 없는 엔드포인트는 위 경로 정책만 적용된다(대부분 permitAll).

**열린 경로(#159)** — `/line/**` 모니터링·실행 경로와 `/api/**`는 1차에서 permitAll. 개별 실행/변경 액션은 `@PreAuthorize` ACL로 가드하고, 조회성 뷰·목록은 대체로 무인증으로 열려 있다.

---

## 라인 정의 (Definition)

REST CRUD·실행은 [`LineDefinitionController`](../../../station8-app/src/main/java/com/station8/app/definition/LineDefinitionController.java), 뷰·ACL 관리는 [`LineDefinitionPageController`](../../../station8-app/src/main/java/com/station8/app/definition/LineDefinitionPageController.java), 빌더 UI는 [`BuilderController`](../../../station8-app/src/main/java/com/station8/app/definition/BuilderController.java), 프로젝트 CRUD는 [`LineProjectController`](../../../station8-app/src/main/java/com/station8/app/definition/LineProjectController.java).

### REST — `LineDefinitionController`

| 메서드 | 경로 | 설명 | 권한 | 요청/응답 |
|--------|------|------|------|-----------|
| POST | `/api/line/definitions` | 신규 정의 등록 (검증 + 저장) | `isAuthenticated()` | `@Valid DagDefinitionRequest` → 201 `{definitionId}` |
| GET | `/api/line/definitions/{id}` | 단건 조회 | (경로 정책) | → `DagDefinitionResponse` |
| GET | `/api/line/definitions/{id}/export` | 정의 JSON export (attachment 다운로드) | `@lineAcl.canRead(#id)` | → `DefinitionExportPayload` |
| POST | `/api/line/definitions/import` | 정의 import | `isAuthenticated()` | `DefinitionExportPayload` + query `onConflict=newVersion\|rename\|reject` → 201 `{definitionId, definitionNm, appliedPolicy}` |
| PUT | `/api/line/definitions/{id}` | 노드/엣지 + 메타 통째 교체 | `@lineAcl.canWrite(#id)` | `@Valid DagDefinitionRequest` → 204 |
| DELETE | `/api/line/definitions/{id}` | 소프트 삭제 | `@lineAcl.canWrite(#id)` | → 204 |
| POST | `/api/line/definitions/{id}/run` | 즉시 실행 (인스턴스 생성) | `@lineAcl.canExecute(#id)` | body `{input?, options?}` (`RunOptions`) → 201 `{instanceId}` / SKIP 시 200 `{skipped, reason, conflictingInstanceId}` |

### 뷰 · ACL — `LineDefinitionPageController`

| 메서드 | 경로 | 설명 | 권한 | 요청/응답 |
|--------|------|------|------|-----------|
| GET | `/line/definitions` | 활성 정의 목록 (뷰) | (경로 정책) | query `page,size,name,tag` → `definitions` |
| GET | `/line/definitions/{id}` | 노선도 미리보기 + 최근 실행 + ACL 관리 (뷰) | (경로 정책) | → `definition-preview` |
| POST | `/line/definitions/{id}/acl/grant` | 권한 부여 (뷰 액션) | `@lineAcl.canAdmin(#id)` | form `username,permission` → redirect |
| POST | `/line/definitions/{id}/acl/revoke` | 권한 회수 (뷰 액션) | `@lineAcl.canAdmin(#id)` | form `userId,permission` → redirect |

### 빌더 · 프로젝트

| 메서드 | 경로 | 설명 | 권한 | 요청/응답 | 컨트롤러 |
|--------|------|------|------|-----------|----------|
| GET | `/line/builder` | DAG 빌더 UI (신규/편집 `?id=`) (뷰) | (경로 정책) | → `builder` | `BuilderController` |
| POST | `/api/line/projects` | 프로젝트 생성 | `isAuthenticated()` | `@Valid LineProjectRequest` → 201 `{projectId}` | `LineProjectController` |
| GET | `/api/line/projects` | 프로젝트 목록 | (경로 정책) | → `List<LineProjectResponse>` | `LineProjectController` |
| GET | `/api/line/projects/{id}` | 단건 조회 | (경로 정책) | → `LineProjectResponse` | `LineProjectController` |
| PUT | `/api/line/projects/{id}` | 이름/설명 수정 | `isAuthenticated()` | `@Valid LineProjectRequest` → 204 | `LineProjectController` |
| DELETE | `/api/line/projects/{id}` | 소프트 삭제 (default 보호) | `isAuthenticated()` | → 204 | `LineProjectController` |

---

## 인스턴스 · 모니터링

인스턴스 운영 REST는 [`LineInstanceController`](../../../station8-app/src/main/java/com/station8/app/controller/LineInstanceController.java), 대시보드·타임라인·DLQ 뷰 + 실행 제어 액션은 [`LineMonitoringController`](../../../station8-app/src/main/java/com/station8/app/controller/LineMonitoringController.java).

### REST — `LineInstanceController` (`/api/line/instances`)

| 메서드 | 경로 | 설명 | 권한 | 요청/응답 |
|--------|------|------|------|-----------|
| POST | `/api/line/instances/{id}/terminate` | 인스턴스 강제 종료 (자동화/스크립트용) | `@lineAcl.canExecuteInstance(#instanceId)` | → 200 `{status, instanceId}` / 404 / 409 |
| GET | `/api/line/instances/{id}/state` | 인스턴스+활동+노선도 상태 스냅샷 (timeline 폴링) | (경로 정책) | → `InstanceStateDto` / 404 |

### 뷰 · 실행 제어 — `LineMonitoringController` (`/line`)

| 메서드 | 경로 | 설명 | 권한 | 요청/응답 |
|--------|------|------|------|-----------|
| GET | `/line/dashboard` | 인스턴스 목록 대시보드 (뷰) | (경로 정책) | query 다수(`workflowName,statusSt,instanceId,startDtFrom/To,sort*,page,size,auto`) → `dashboard` |
| GET | `/line/instance/{id}` | 인스턴스 상세 타임라인 (뷰) | (경로 정책) | 미존재 시 404 → `timeline` |
| POST | `/line/instance/{id}/resume` | 실패 라인 재개 (뷰 액션) | `@lineAcl.canExecuteInstance(#instanceId)` | → redirect |
| POST | `/line/instance/{id}/terminate` | RUNNING 인스턴스 강제 종료 (뷰 액션) | `@lineAcl.canExecuteInstance(#instanceId)` | → redirect |
| POST | `/line/instance/{id}/pause` | 인스턴스 일시 정지 (뷰 액션) | `@lineAcl.canExecuteInstance(#instanceId)` | → redirect |
| POST | `/line/instance/{id}/unpause` | 일시 정지 재개 (뷰 액션) | `@lineAcl.canExecuteInstance(#instanceId)` | → redirect |
| POST | `/line/instance/{id}/activity/{execId}/retry` | 단일 FAILED 활동 retry (뷰 액션) | `@lineAcl.canExecuteInstance(#instanceId)` | → redirect |
| GET | `/line/dlq` | DLQ 목록 (뷰) | (경로 정책) | query 다수(`workflowName,activityName,errorMessage,dlqStatusSt,failedAtFrom/To,sort*,page,size`) → `dlq` |
| GET | `/line/dlq/{id}` | DLQ 항목 상세 (뷰) | (경로 정책) | → `dlq-detail` |
| POST | `/line/dlq/{id}/requeue` | DLQ 재처리 (뷰 액션) | (경로 정책) | → redirect |
| POST | `/line/dlq/{id}/discard` | DLQ 폐기 (뷰 액션) | (경로 정책) | → redirect |

---

## 스케줄 (Schedule)

REST + 목록 뷰 모두 [`ScheduleController`](../../../station8-app/src/main/java/com/station8/app/schedule/ScheduleController.java).

| 메서드 | 경로 | 설명 | 권한 | 요청/응답 |
|--------|------|------|------|-----------|
| GET | `/line/schedules` | 스케줄 목록 (뷰, ACL READ 가시성 필터) | (경로 정책) | query `page,size` → `schedules` |
| POST | `/api/line/schedules` | 신규 스케줄 등록 | `@lineAcl.canSchedule(#req.definitionId())` | `@Valid CreateRequest{definitionId,cronExpr,inputData?}` → 201 `{scheduleId}` |
| GET | `/api/line/schedules` | 목록 (JSON) | (경로 정책) | → `List<LineSchedule>` |
| GET | `/api/line/schedules/{id}` | 단건 | (경로 정책) | → `LineSchedule` |
| PUT | `/api/line/schedules/{id}` | cron 변경 | `@lineAcl.canScheduleByScheduleId(#id)` | `@Valid UpdateCronRequest{cronExpr}` → 204 |
| DELETE | `/api/line/schedules/{id}` | 소프트 삭제 | `@lineAcl.canScheduleByScheduleId(#id)` | → 204 |
| PUT | `/api/line/schedules/{id}/pause` | 일시중지 | `@lineAcl.canScheduleByScheduleId(#id)` | → 204 |
| PUT | `/api/line/schedules/{id}/resume` | 재개 | `@lineAcl.canScheduleByScheduleId(#id)` | → 204 |
| POST | `/api/line/schedules/{id}/run-now` | 즉시 실행 | `@lineAcl.canScheduleByScheduleId(#id)` | → 201 `{instanceId}` |
| GET | `/api/line/schedules/preview-cron` | cron 다음 매칭 시각 미리보기 | (경로 정책) | query `cronExpr` → `{valid, next1..3}` / 400 `{valid:false, message}` |

관련 에러: `WF-E401` `SCHEDULE_NOT_FOUND`, `WF-E402` `CRON_INVALID`, `WF-E403` `SCHEDULE_TRIGGER_FAILED`.

---

## 트리거 · 웹훅 (Trigger)

Trigger CRUD + 목록 뷰는 [`LineTriggerController`](../../../station8-app/src/main/java/com/station8/app/triggers/LineTriggerController.java), 외부 웹훅 수신은 [`WebhookTriggerController`](../../../station8-app/src/main/java/com/station8/app/triggers/WebhookTriggerController.java).

### CRUD · 뷰 — `LineTriggerController`

| 메서드 | 경로 | 설명 | 권한 | 요청/응답 |
|--------|------|------|------|-----------|
| GET | `/line/triggers` | 트리거 목록 + 등록 폼 + webhook URL/curl 프리뷰 (뷰) | (경로 정책) | → `triggers` |
| POST | `/api/line/triggers` | 신규 등록 | `hasRole('ADMIN')` | `@Valid TriggerRequest` → 201 `TriggerResponse` |
| GET | `/api/line/triggers` | 목록 | `isAuthenticated()` | → `List<TriggerResponse>` |
| GET | `/api/line/triggers/{id}` | 단건 | `isAuthenticated()` | → `TriggerResponse` / 404 |
| PUT | `/api/line/triggers/{id}` | 갱신 | `hasRole('ADMIN')` | `@Valid TriggerRequest` → `TriggerResponse` / 404 |
| DELETE | `/api/line/triggers/{id}` | soft delete | `hasRole('ADMIN')` | → 200 `{id, status}` / 404 |

`TriggerRequest`: `definitionId`(필수), `triggerType`(현재 `webhook`만), `triggerKey`(lowercase/숫자/dash/underscore, 1~128자), `configJson`(webhook은 `hmacSecret` 필수), `activeFl?`.

### 웹훅 수신 — `WebhookTriggerController`

| 메서드 | 경로 | 설명 | 권한 | 요청/응답 |
|--------|------|------|------|-----------|
| POST | `/api/triggers/webhook/{key}` | 외부 시스템 웹훅 수신 → 라인 시작 | permitAll (HMAC이 인증 역할) | headers `X-Signature`, `X-Timestamp`(replay 방어 시), body(raw bytes) → 200 `{started, instanceId, definitionId, workflowName}` |

응답/상태 코드: 미존재·비활성·비-webhook 타입 404, POST 미허용 405, rate limit 초과 429, 서명/타임스탬프 문제 401·400, config malformed·시크릿 미존재·launch 실패 500, SKIP(정책) 202 `{started:false, skipReason, conflictingInstanceId}`. HMAC은 `HMAC-SHA256(payload, secret)` hex — replay 방어 활성 시 payload는 `timestamp + "\n" + body`. secret은 vault credential(`webhook_hmac` type) 이름으로 lookup.

---

## 액티비티 카탈로그 (Catalog)

[`ActivityCatalogController`](../../../station8-app/src/main/java/com/station8/app/catalog/ActivityCatalogController.java) — REST + 카탈로그 뷰. 빌더의 역 팔레트가 REST API를 소비한다.

| 메서드 | 경로 | 설명 | 권한 | 요청/응답 |
|--------|------|------|------|-----------|
| GET | `/line/activities` | 액티비티 카탈로그 페이지 (뷰) | (경로 정책) | → `activities` |
| GET | `/api/line/activities` | 등록된 모든 액티비티 목록 (JSON) | (경로 정책) | → `List<ActivityCatalogEntry>` |
| GET | `/api/line/activities/{name}` | 단건 상세 | (경로 정책) | → `ActivityCatalogEntry` / 404 `{message}` |

`ActivityCatalogEntry`는 activityName·beanClass·method·retryCount·backoffSeconds·paramTypes·returnType·description + `@ActivityParam` 스키마(`ParamSchema`)를 담는다. 관련 에러: `WF-E202` `ACTIVITY_NOT_FOUND`, `WF-E307` `DAG_UNKNOWN_ACTIVITY`.

---

## 표현식 (Expression)

[`ExpressionEvaluationController`](../../../station8-app/src/main/java/com/station8/app/expressions/ExpressionEvaluationController.java) — 빌더의 "Test inputParams" dry-run.

| 메서드 | 경로 | 설명 | 권한 | 요청/응답 |
|--------|------|------|------|-----------|
| POST | `/api/line/expressions/_evaluate` | 표현식 dry-run (5초 timeout, sandbox) | `isAuthenticated()` | `EvaluateRequest{expression, prev?, ctx?, credentials?}` → `EvaluateResponse{ok, result, resultType, error, durationMs}` |

`expression` 누락 시 400. 평가 실패·timeout은 200 + `ok:false`로 응답한다. 샌드박스(`HostAccess.NONE`, ProxyObject wrapping)는 `ExpressionEvaluator`가 적용.

---

## 크리덴셜 (Credential)

REST CRUD는 [`CredentialController`](../../../station8-app/src/main/java/com/station8/app/credential/CredentialController.java), 어드민 vault 관리 뷰는 [`AdminCredentialController`](../../../station8-app/src/main/java/com/station8/app/controller/AdminCredentialController.java). 평문 value는 응답·화면에 절대 노출되지 않는다.

### REST — `CredentialController` (`/api/line/credentials`)

| 메서드 | 경로 | 설명 | 권한 | 요청/응답 |
|--------|------|------|------|-----------|
| POST | `/api/line/credentials` | 생성 (value 즉시 암호화) | `hasRole('ADMIN')` | `@Valid CredentialRequest` → 201 `CredentialResponse` (value 없음) |
| GET | `/api/line/credentials` | 목록 (메타만) | `isAuthenticated()` | → `List<CredentialResponse>` |
| GET | `/api/line/credentials/{id}` | 단건 (메타만) | `isAuthenticated()` | → `CredentialResponse` / 404 |
| PUT | `/api/line/credentials/{id}` | 갱신 (value 있으면 rotate) | `hasRole('ADMIN')` | `@Valid CredentialRequest` → `CredentialResponse` / 404 |
| DELETE | `/api/line/credentials/{id}` | soft delete | `hasRole('ADMIN')` | → 200 `{id, status}` / 404 |

지원 type(`SUPPORTED_TYPES`): `http_basic`, `http_bearer`, `api_key`, `generic`, `sftp_password`, `sftp_key`, `s3_access_key`, `webhook_hmac`, `openai_compatible`, `anthropic`. 미지원 type은 400.

### 뷰 — `AdminCredentialController` (`/admin/credentials`)

`@PreAuthorize("hasRole('ADMIN')")` (클래스 레벨) + `/admin/**` 경로 정책으로 이중 가드.

| 메서드 | 경로 | 설명 | 요청/응답 |
|--------|------|------|-----------|
| GET | `/admin/credentials` | 목록 (뷰) | → `admin-credentials` |
| GET | `/admin/credentials/new` | 신규 폼 (뷰) | → `admin-credential-form` |
| POST | `/admin/credentials` | 생성 (폼) | form `name,type,value,schema*` → redirect |
| GET | `/admin/credentials/{id}/edit` | 편집 폼 (뷰) | → `admin-credential-form` |
| POST | `/admin/credentials/{id}` | 갱신 (폼) | form `name,type,value?,schema*` → redirect |
| POST | `/admin/credentials/{id}/delete` | soft delete (폼) | → redirect |

---

## 관리자 (Admin)

`/admin/**` 경로는 `SecurityConfig`가 `hasRole('ADMIN')`으로 보호한다. 크리덴셜 관리(`AdminCredentialController`)는 위 크리덴셜 섹션 참고.

### 데이터소스 — [`AdminDataSourceController`](../../../station8-app/src/main/java/com/station8/app/controller/AdminDataSourceController.java) (`/admin/datasources`)

| 메서드 | 경로 | 설명 | 요청/응답 |
|--------|------|------|-----------|
| GET | `/admin/datasources` | 목록 + 풀 헬스 (뷰) | → `admin-datasources` |
| GET | `/admin/datasources/new` | 신규 폼 (뷰) | → `admin-datasource-form` |
| POST | `/admin/datasources` | 생성 (폼) | form `name,jdbcUrl,username?,password?,driverClass?,dialect?,hikariOptionsJson?,enabled?` → redirect |
| GET | `/admin/datasources/{name}/edit` | 편집 폼 (뷰) | → `admin-datasource-form` |
| POST | `/admin/datasources/{name}` | 갱신 (폼) | form 동일 → redirect |
| POST | `/admin/datasources/{name}/test` | 연결 테스트 (SELECT 1) (폼) | → redirect |
| POST | `/admin/datasources/{name}/delete` | soft delete + 풀 close (폼) | → redirect |
| POST | `/admin/datasources/{name}/toggle-enabled` | 활성/비활성 토글 (폼) | → redirect |

DYNAMIC(`U_LINE_DATASOURCE`) 출처만 Edit/Toggle/Delete 가능. PRIMARY·STATIC은 Test connection만.

### 플러그인 — [`AdminPluginController`](../../../station8-app/src/main/java/com/station8/app/controller/AdminPluginController.java) (`/admin/plugins`)

| 메서드 | 경로 | 설명 | 요청/응답 |
|--------|------|------|-----------|
| GET | `/admin/plugins` | 플러그인 jar 디렉토리 목록 (뷰) | → `admin-plugins` |
| POST | `/admin/plugins/reload` | 핫 리로드 (폼) | → redirect |
| POST | `/admin/plugins` | jar 업로드 (multipart, 50MB 상한, zip 매직바이트 검증) | `MultipartFile file` → redirect |

### 사용자 — [`AdminUserController`](../../../station8-app/src/main/java/com/station8/app/security/AdminUserController.java) (`/admin/users`)

| 메서드 | 경로 | 설명 | 요청/응답 |
|--------|------|------|-----------|
| GET | `/admin/users` | 사용자 목록 (뷰) | → `admin-users` |
| POST | `/admin/users` | 사용자 생성 (폼) | form `username,displayNm?,password,isAdmin?` → redirect |
| POST | `/admin/users/{id}/reset-password` | 비밀번호 재설정 (폼) | form `newPassword` → redirect |
| POST | `/admin/users/{id}/toggle-enabled` | 활성/비활성 토글 (본인 제외) (폼) | → redirect |
| POST | `/admin/users/{id}/delete` | soft delete (본인 제외) (폼) | → redirect |

---

## 인증 · 계정 · 랜딩

폼 로그인 페이지는 [`LoginController`](../../../station8-app/src/main/java/com/station8/app/security/LoginController.java), 본인 비밀번호 변경은 [`PasswordChangeController`](../../../station8-app/src/main/java/com/station8/app/security/PasswordChangeController.java), 루트 랜딩은 [`LandingController`](../../../station8-app/src/main/java/com/station8/app/controller/LandingController.java).

| 메서드 | 경로 | 설명 | 권한 | 요청/응답 |
|--------|------|------|------|-----------|
| GET | `/` | 루트 랜딩 페이지 (뷰) | permitAll | → `landing` |
| GET | `/login` | 로그인 페이지 (뷰). POST는 Security 필터가 처리 | permitAll | query `error?,logout?` → `login` |
| GET | `/me/password` | 본인 비밀번호 변경 폼 (뷰) | 인증 필요 | → `me-password` |
| POST | `/me/password` | 본인 비밀번호 변경 (폼) | 인증 필요 | form `currentPassword,newPassword,confirmPassword` → redirect |

---

## 데모 (demo 프로파일 전용)

[`DemoChatController`](../../../station8-app/src/main/java/com/station8/app/demo/DemoChatController.java)·[`DemoEchoController`](../../../station8-app/src/main/java/com/station8/app/demo/DemoEchoController.java) — `@Profile("demo")`에서만 활성. 외부 의존 없이 self-contained 데모를 돌리기 위한 가짜 LLM·HTTP endpoint.

| 메서드 | 경로 | 설명 | 요청/응답 |
|--------|------|------|-----------|
| POST | `/api/demo/llm/v1/chat/completions` | OpenAI Chat Completions 흉내 (tool_call ↔ 최종 답변) | body(OpenAI 요청) → OpenAI 응답 shape |
| GET | `/api/demo/echo/post` | jsonplaceholder 응답 흉내 (고정 JSON) | → `{id, userId, title, body}` |
| POST | `/api/demo/echo/sink` | 받은 본문 echo + 타임스탬프 | body(임의 JSON) → `{receivedAt, echo}` |
