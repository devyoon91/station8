# Quickstart

5분 안에 station8을 띄우고 시나리오를 실행해본다.

## 1. 사전 요구사항

| 도구 | 최소 버전 | 비고 |
|------|----------|------|
| **Docker Desktop** | 24+ | Windows/macOS는 필수. Linux는 docker engine + compose v2 |
| **Java** (선택) | 21+ | `./gradlew bootRun` 직접 띄울 때만 필요. Docker만 사용하면 불필요 |
| **bash + curl + jq** (선택) | — | 시나리오 자동화 (`scripts/run-all.sh`) 용. Windows는 **Git Bash** + `winget install jqlang.jq` |

> 본 프로젝트는 Java 21 / Spring Boot 3.4 / MariaDB 11 기반. `gradlew`이 Gradle 9.1을 자동 다운로드.

## 2. 한 줄로 띄우기

```bash
git clone git@github.com:devyoon91/station8.git
cd station8

# 한 번의 명령으로: app 빌드 + MariaDB 기동 + 데모 시드 자동 등록
docker compose -f docker/docker-compose.yml up --build -d
```

또는 Gradle 헬퍼:

```bash
./gradlew composeUpApp        # app + DB 빌드 + 백그라운드 기동
./gradlew composeLogsApp      # app 컨테이너 로그 tail
./gradlew composeDown         # 정리
```

처음 빌드 시 5~7분(gradle wrapper + 의존성 다운로드), 이후 코드만 변경 시 ~30초.

## 3. 만져보기

기동 직후 약 25초 기다린 뒤 브라우저로 접속:

| URL | 설명 |
|-----|------|
| http://localhost:8080/line/dashboard | 인스턴스 목록 + 통계 |
| http://localhost:8080/line/schedules | Cron 스케줄 관리 (자동 시드 1건 + Add) |
| http://localhost:8080/line/dlq | Dead Letter Queue (재시도 초과 항목) |
| http://localhost:8080/api/line/activities | 등록된 `@Activity` 목록 (JSON) |
| http://localhost:8080/api/line/definitions | DAG 정의 등록 API (POST) |

`demo` 프로파일이 활성되어 있어 부팅 직후:
- `DemoMigrationFlow` DAG 정의 1건 자동 등록
- 위 정의를 5분마다 실행하는 cron 스케줄 1건 자동 등록
- `MigrationInitializer`가 `SRC_DATA` 3건 시드 + `MIGRATION_WRITE` 액티비티 3건 PENDING

## 4. 시나리오 자동 검증

REST API + 핵심 흐름을 한 번에 점검:

```bash
./scripts/run-all.sh
```

5건 시나리오:
1. DAG 정의 등록 + 조회
2. 즉시 실행 → 인스턴스 생성
3. cron 스케줄 등록 + run-now + 정리
4. DLQ 페이지 접근성
5. 검증 거부 (사이클 / 미등록 액티비티)

기대 결과: `Passed: 5 / 5`

> Windows에서는 **Git Bash**에서 실행. `jq` 미설치 시 `winget install jqlang.jq` 후 새 셸 열기.

## 5. DAG 직접 등록 예시

REST API로 단일 역 DAG를 등록하고 즉시 실행:

```bash
# 1) 정의 등록
DEF_ID=$(curl -sX POST http://localhost:8080/api/line/definitions \
  -H "Content-Type: application/json" \
  -d '{
    "definitionNm": "MyFirstFlow",
    "description": "한 번 만들어 보기",
    "nodes": [{"nodeId":"n1","nodeNm":"Migrate","activityNm":"MIGRATION_WRITE","inputParams":"{\"id\":\"99\",\"content\":\"My data\"}","posX":100,"posY":100}],
    "edges": []
  }' | jq -r '.definitionId')
echo "Created: $DEF_ID"

# 2) 즉시 실행
curl -X POST "http://localhost:8080/api/line/definitions/$DEF_ID/run"

# 3) Dashboard에서 결과 확인
open http://localhost:8080/line/dashboard   # macOS
# 또는 Windows: start http://localhost:8080/line/dashboard
```

## 6. 고유 액티비티 직접 만들기

`@Line` + `@Activity` 어노테이션 1쌍이면 끝:

```java
@Line("MyOrder")
@Component
public class OrderLine {

    @Activity(value = "VALIDATE_ORDER", retryCount = 3, backoffSeconds = 5)
    public String validate(String inputJson) {
        // ... 비즈니스 로직 ...
        if (somethingWrong) throw new RuntimeException("invalid");
        return "{\"status\":\"valid\"}";
    }
}
```

부팅 시 `LineRegistry`가 자동 스캔 → `/api/line/activities`에 즉시 가시화 → DAG 역의 `activityNm`으로 사용 가능.

## 7. 트러블슈팅

| 증상 | 원인 / 해결 |
|------|-----------|
| `port 8080 already in use` | 다른 앱이 점유. `docker compose down` 또는 `docker-compose.yml`에서 port 변경 |
| `port 3307 already in use` | MariaDB 포트 충돌. compose에서 호스트 포트 변경 |
| **컨테이너 빌드 실패** `./gradlew: not found` | Windows git의 CRLF 변환. `.gitattributes`로 해결되어 있으나, 직접 클론한 환경에서 `git config core.autocrlf input` |
| **`/line/dashboard` 빈 화면** | `MigrationInitializer`가 시드되기 전. 25초 기다린 후 새로고침 |
| **DLQ가 비어있음** | 'Second Data' 액티비티가 5회 재시도 + backoff 누적 ~155초 후에 적재. 기다리거나 직접 정의 등록 |
| **`docker compose` 실행 시 mariadb만 뜸** | `./gradlew composeUp` (DB only)을 실행한 경우. **`composeUpApp`** (전체) 사용 |
| **시나리오 5/5가 4/5로 떨어짐** | 시간 누적된 mariadb data. `docker compose down -v` 후 재기동 |

## 8. 정리

```bash
./gradlew composeDown
# 또는: docker compose -f docker/docker-compose.yml down -v
```

`-v` 플래그로 mariadb volume까지 wipe (다음 기동 시 fresh schema).

---

## 다음 단계

- 모듈 흐름과 런타임 시퀀스: [ARCHITECTURE.md](ARCHITECTURE.md)
- 사용자 가이드 (각 페이지 상세): [HOWTO.md](HOWTO.md)
- 데이터베이스 명명 규칙: [DATABASE_RULE.md](DATABASE_RULE.md)
- 엔진 명세: [line-engine-spec.md](line-engine-spec.md)
- 협업 규정: [CONTRIBUTING.md](CONTRIBUTING.md)
- 작업 추적: [GitHub Issues](https://github.com/devyoon91/station8/issues) · [Milestones](https://github.com/devyoon91/station8/milestones)
