# e2e-tests

REST Assured + JUnit5 + Awaitility 기반 정식 회귀 테스트 모듈.

## 활성화 조건

기본 비활성화 — `-PdockerHost` 또는 `SWE_E2E_HOST` 환경변수가 있어야 `:e2e-tests:test`가 실행된다. 미지정 시 `onlyIf`가 false를 반환해 스킵.

## 실행 방법

### 1) docker compose로 station8-app 띄우기

```bash
./gradlew composeUpApp
# (헬스체크 통과까지 대기 — 첫 부팅은 30~60초 소요)
```

### 2) e2e 테스트 실행

```bash
# Gradle property 방식
./gradlew :e2e-tests:test -PdockerHost=localhost:8080

# 환경변수 방식
SWE_E2E_HOST=localhost:8080 ./gradlew :e2e-tests:test
```

### 3) 정리

```bash
./gradlew composeDown
```

## 테스트 클래스

| 클래스 | 검증 시나리오 |
|--------|--------------|
| `CreateDagTest` | DAG 정의 등록 → GET 조회 (POST 201, 역 1개) |
| `RunNowTest` | 정의 즉시 실행 → 인스턴스 생성 (POST 201) |
| `CronFlowTest` | 스케줄 등록 + run-now (nextRunDt 미래값) |
| `DlqTest` | `/line/dlq` 200 응답 |
| `ValidationErrorsTest` | 사이클 → WF-E305, 미등록 액티비티 → WF-E307 |

## bash 시나리오와의 관계

`scripts/scenarios/01-05*.sh`의 동일 시나리오를 JUnit 클래스로 포팅한 것. bash는 빠른 수동 점검, e2e-tests는 CI 통합 회귀에 쓴다.

| 측면 | bash + curl | REST Assured |
|------|-------------|-------------|
| 빠른 수동 점검 | OK | △ (gradle 부팅) |
| 복잡한 응답 검증 | △ jq | OK (JsonPath) |
| 재시도/타이밍 | △ sleep | OK (Awaitility) |
| CI XML 보고서 | △ stdout | OK (JUnit XML) |

## 헬스체크

`E2EBaseTest.@BeforeAll`에서 `/actuator/health`를 30초간 폴링한다. station8-app이 부팅되지 않은 경우 `ConditionTimeoutException`으로 빠르게 실패.

## 부하 테스트 (k6)

본 모듈은 **기능 회귀** 검증용. **부하/처리량/backpressure** 측정은 [k6/](k6/) 디렉토리의 JS 스크립트 사용.

```bash
k6 run k6/smoke.js     # 핵심 경로 1회
k6 run k6/load.js      # 100 VU × 30s
k6 run k6/stress.js    # ramp 50→200 VU
```

## 관련 문서

- [docs/QUICKSTART.md](../docs/QUICKSTART.md) — docker compose 셋업
- [scripts/scenarios/](../scripts/scenarios/) — bash 회귀 스크립트
- [docs/HOWTO.md](../docs/HOWTO.md) — 라인 정의 가이드
- [k6/README.md](k6/README.md) — 부하 테스트 시나리오
