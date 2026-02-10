package com.example.workflow.engine.core;

import java.util.Map;

/**
 * 워크플로우 실행을 담당하는 핵심 인터페이스
 */
public interface WorkflowExecutor {
    
    /**
     * 새로운 워크플로우 인스턴스를 시작합니다.
     * 
     * @param workflowName 워크플로우 이름 (@Workflow 어노테이션의 value 또는 클래스명)
     * @param input 입력 데이터 (JSON 직렬화 가능한 객체)
     * @return 생성된 인스턴스 ID
     */
    String startWorkflow(String workflowName, Object input);
    
    /**
     * 중단된 워크플로우를 특정 지점부터 재개합니다.
     * 
     * @param instanceId 워크플로우 인스턴스 ID
     */
    void resumeWorkflow(String instanceId);
}
