package com.station8.engine.core;

/**
 * 라인 실행을 담당하는 상위 인터페이스.
 */
public interface LineExecutor {
    
    /**
     * 새로운 라인 인스턴스를 시작합니다.
     * 
     * @param workflowName 라인 이름 (@Line 어노테이션의 value 또는 클래스명)
     * @param input 입력 객체(JSON 직렬화 가능한 객체)
     * @return 생성된 인스턴스 ID
     */
    String startLine(String workflowName, Object input);
    
    /**
     * 중단된 라인을 특정 인스턴스 기준으로 재개합니다.
     * 
     * @param instanceId 라인 인스턴스 ID
     */
    void resumeLine(String instanceId);
}

