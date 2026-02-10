# 🐞 Issue: 패키지명 변경 후 빌드 환경 문제 (JAVA_HOME)

## 개요
- 패키지명을 `com.example.*` → `com.bangrang.*`으로 일괄 변경 완료하였습니다.
- 디렉토리 구조도 `src/main/java/com/example/...` → `src/main/java/com/bangrang/...`으로 이동/정리 완료했습니다.
- 모든 Java 소스/설정/문서 내 참조를 업데이트했습니다.

## 현재 문제
- Gradle Wrapper(9.1.0)로 빌드를 시도했으나, 현재 세션의 `JAVA_HOME`이 Java 8로 설정되어 있어 Gradle이 구동되지 않습니다.
- 로그 발췌:
  - `Gradle requires JVM 17 or later to run. Your build is currently configured to use JVM 8.`

## 영향도
- 로컬/CI 환경에서 Java 17 이상(권장: 25)으로 설정되기 전까지 CI 빌드가 실패합니다.

## 재현 방법
1. 현재 환경(Windows)에서 `JAVA_HOME`이 `E:\develop\java\java8`로 지정됨
2. `./gradlew.bat clean build` 실행
3. Gradle JVM 버전 미스매치로 실패

## 기대 동작
- `JAVA_HOME`이 Java 17+ (권장 25)로 설정된 환경에서 `./gradlew clean build`가 정상 완료되어야 합니다.

## 해결 방안 제안
- 단기: 빌드 실행 전 `JAVA_HOME`을 17+로 교체
  - 예) PowerShell
    - `$env:JAVA_HOME="C:\\Program Files\\Java\\jdk-25"`
    - `$env:Path="${env:JAVA_HOME}\\bin;${env:Path}"`
- 장기: CI 파이프라인에서 JDK 25 런타임 이미지 사용 지정 (예: GitHub Actions/ Jenkins 에이전트 이미지)

## 참고
- Gradle Wrapper: 9.1.0 (이미 저장소에 jar/properties 포함)
- 프로젝트 기본 Java 타겟: 25

## 상태
- [ ] JAVA_HOME 교체 및 빌드 재시도
- [x] 패키지명 일괄 변경/디렉토리 이동/소스 참조 업데이트
