package com.station8.engine.core;

import com.station8.engine.util.JsonUtil;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * LineContext의 기본 구현체.
 */
public class DefaultLineContext implements LineContext {

    private final String instanceId;
    private final String workflowName;
    private final String currentActivityName;
    private final String nodeId;             // #278 — DAG 노드 ID (legacy mode = null)
    private final int attempt;
    private final Object input;
    private final Object previousOutput;
    private final Map<String, Object> attributes = new HashMap<>();
    private final JsonUtil jsonUtil;
    /** 인스턴스 단위 runtime params (#134). 변경 가능 — null safe. */
    private Map<String, String> runtimeParams = Map.of();

    /** M22 item-level streaming — fan-out 레인 인덱스/원소/전체 배열. 비-fan-out은 0/null/null. */
    private int itemIndex = 0;
    private Object item;
    private Object items;

    private String nextActivityName;
    private Object nextActivityInput;
    private String stateSnapshotJson;

    public DefaultLineContext(String instanceId,
                                  String workflowName,
                                  String currentActivityName,
                                  int attempt,
                                  Object input,
                                  Object previousOutput,
                                  JsonUtil jsonUtil) {
        this(instanceId, workflowName, currentActivityName, null, attempt, input, previousOutput, jsonUtil);
    }

    /** #278 — nodeId 포함 생성자 (DAG 모드 retry 시 nodeId 보존). */
    public DefaultLineContext(String instanceId,
                                  String workflowName,
                                  String currentActivityName,
                                  String nodeId,
                                  int attempt,
                                  Object input,
                                  Object previousOutput,
                                  JsonUtil jsonUtil) {
        this.instanceId = instanceId;
        this.workflowName = workflowName;
        this.currentActivityName = currentActivityName;
        this.nodeId = nodeId;
        this.attempt = attempt;
        this.input = input;
        this.previousOutput = previousOutput;
        this.jsonUtil = jsonUtil;
    }

    @Override
    public String instanceId() {
        return instanceId;
    }

    @Override
    public String workflowName() {
        return workflowName;
    }

    @Override
    public String currentActivityName() {
        return currentActivityName;
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    @Override
    public int attempt() {
        return attempt;
    }

    @Override
    public Object input() {
        return input;
    }

    @Override
    public Optional<Object> previousOutput() {
        return Optional.ofNullable(previousOutput);
    }

    @Override
    public Map<String, Object> attributes() {
        return attributes;
    }

    @Override
    public Map<String, String> runtimeParams() {
        return runtimeParams;
    }

    /** #134 — 인스턴스 RUN_OPTIONS에서 파싱한 runtime params를 주입한다. */
    public void setRuntimeParams(Map<String, String> params) {
        this.runtimeParams = params == null ? Map.of() : Map.copyOf(params);
    }

    @Override
    public int itemIndex() {
        return itemIndex;
    }

    @Override
    public Optional<Object> item() {
        return Optional.ofNullable(item);
    }

    @Override
    public Optional<Object> items() {
        return Optional.ofNullable(items);
    }

    /**
     * M22 — fan-out 레인 정보를 주입한다. {@code index}는 레인 인덱스, {@code item}은 현재 원소,
     * {@code items}는 레인이 비롯된 전체 배열. 호스트의 fan-out materialize(#369)가 호출한다.
     */
    public void setItemContext(int index, Object item, Object items) {
        this.itemIndex = index;
        this.item = item;
        this.items = items;
    }

    @Override
    public void setNext(String activityName, Object input) {
        this.nextActivityName = activityName;
        this.nextActivityInput = input;
    }

    @Override
    public Optional<String> nextActivityName() {
        return Optional.ofNullable(nextActivityName);
    }

    @Override
    public Optional<Object> nextActivityInput() {
        return Optional.ofNullable(nextActivityInput);
    }

    @Override
    public void saveState(Object stateSnapshot) {
        this.stateSnapshotJson = jsonUtil.toJson(stateSnapshot);
    }

    @Override
    public Optional<Object> loadState() {
        // 실제 구현에서는 DB에서 읽어온 JSON을 역직렬화해야 하지만,
        // 여기서는 현재 메모리에 저장된 snapshot을 반환하거나
        // 생성 시 주입받은 snapshot을 관리하도록 설계한다.
        // 현재 인터페이스 구조상 특정 타입으로 역직렬화하는 정보가 부족하므로
        // 일단 String 또는 Raw Map 형태를 고려한다.
        return Optional.ofNullable(stateSnapshotJson);
    }

    /**
     * 초기 상태 데이터를 주입하기 위한 메서드.
     */
    public void setStateData(String stateDataJson) {
        this.stateSnapshotJson = stateDataJson;
    }

    public String getStateSnapshotJson() {
        return stateSnapshotJson;
    }
}

