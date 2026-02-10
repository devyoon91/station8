# OPERATIONS

이 문서는 ESP API 프로젝트의 빌드, 테스트 실행, 배포 파이프라인 명령어 등 'DevOps' 관련 가이드를 제공합니다.

## 1. 빌드 및 실행

- **프로젝트 빌드**:
  ```powershell
  ./gradlew clean build
  ```
  이 명령은 프로젝트를 빌드하고 QueryDSL의 QFile들을 생성합니다.

- **로컬 실행**:
  ```powershell
  ./gradlew bootRun
  ```

## 2. 테스트 실행

- **전체 테스트 실행**:
  ```powershell
  ./gradlew test
  ```

- **특정 테스트 클래스 실행**:
  ```powershell
  ./gradlew test --tests ecs.esp.core.domain.auth.service.InternalSystemIntegrationServiceTest
  ```

- **주의사항**:
  IDE(IntelliJ 등)의 내장 테스트 실행 도구 대신 반드시 Gradle 명령어를 사용하여 테스트를 수행해야 합니다. 이는 환경 설정 및 의존성 일관성을 보장하기 위함입니다.

## 3. 배포 파이프라인

- **Jenkins**: `develop` 브랜치 경우 CI/CD 파이프라인이 자동으로 실행됩니다. `main` 브랜치는 수동으로 실행해야 합니다.
- **환경 설정**: 시스템 설정 및 보안 키는 `application-*.yml` 프로파일을 통해 환경별로 관리됩니다.

---
[← AGENTS.md로 돌아가기](../AGENTS.md)

