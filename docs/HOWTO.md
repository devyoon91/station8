# 📖 Simple Workflow Engine User Guide

이 문서는 `simple-workflow-engine`을 사용하여 비즈니스 워크플로우를 정의하고 실행하는 방법을 설명합니다.

## 1. 워크플로우 정의하기

워크플로우는 하나 이상의 **Activity**로 구성된 비즈니스 프로세스입니다.

### 1.1. @Workflow 선언
워크플로우를 정의할 클래스에 `@Workflow` 어노테이션을 선언합니다. 클래스는 Spring Bean이어야 합니다.

```java
@Component
@Workflow("UserSignupWorkflow")
public class UserSignupWorkflow {
    // ...
}
```

### 1.2. @Activity 정의
워크플로우 내에서 실행될 개별 작업 메서드에 `@Activity`를 선언합니다.

```java
@Activity(name = "SEND_WELCOME_EMAIL", retryCount = 3, backoffSeconds = 10)
public String sendWelcomeEmail(String userEmail) {
    // 비즈니스 로직
    return "SUCCESS";
}
```

## 2. 워크플로우 오케스트레이션

워크플로우의 흐름 제어는 `WorkflowContext`를 통해 수행됩니다.

### 2.1. 흐름 제어 (Next Activity)
현재 액티비티가 종료된 후 다음에 실행할 액티비티를 지정합니다.

```java
@Activity(name = "VALIDATE_USER")
public void validateUser(WorkflowContext context, User user) {
    if (user.isValid()) {
        context.setNext("SEND_WELCOME_EMAIL", user.getEmail());
    } else {
        context.setNext("NOTIFY_ADMIN", user);
    }
}
```

### 2.2. 상태 저장 (Checkpoint)
워크플로우 실행 중 중간 상태를 저장하여 장애 발생 시 해당 지점부터 재개할 수 있습니다.

```java
@Activity(name = "LONG_RUNNING_TASK")
public void heavyTask(WorkflowContext context, Object input) {
    // ... 중간 작업 완료
    context.saveState(currentProgressObject);
}
```

## 3. 워크플로우 실행 및 관리

### 3.1. 최초 실행 시작
`ActivityRepository`를 통해 첫 번째 액티비티를 `PENDING` 상태로 생성하거나, `WorkflowExecutor`를 사용하여 시작할 수 있습니다. (현재 예제 앱은 `MigrationInitializer` 참조)

### 3.2. 모니터링 대시보드
애플리케이션 실행 후 `http://localhost:8080/workflow/dashboard`에 접속하여 실행 상태를 확인하고 관리할 수 있습니다.
- **Auto Refresh**: 5초마다 상태를 자동으로 갱신합니다.
- **Filter**: ID, 이름, 상태별로 인스턴스를 검색합니다.
- **Resume**: 실패한 워크플로우를 마지막 실패 지점부터 다시 시작합니다.
- **DLQ**: 최종 실패한 작업들을 재처리(Requeue)하거나 폐기(Discard)합니다.

## 4. 설정 (application.properties)

```properties
# DB 설정 (MariaDB 예시)
spring.datasource.url=jdbc:mariadb://localhost:3306/wfdb
spring.datasource.username=wfuser
spring.datasource.password=wfpw

# 엔진 설정
engine.dlq.enabled=true
engine.dlq.webhook-url=https://hooks.slack.com/services/...
```
