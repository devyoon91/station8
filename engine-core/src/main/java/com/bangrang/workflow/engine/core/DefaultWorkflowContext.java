package com.bangrang.workflow.engine.core;

import com.bangrang.workflow.engine.util.JsonUtil;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * WorkflowContext??湲곕낯 援ы쁽泥?
 */
public class DefaultWorkflowContext implements WorkflowContext {

    private final String instanceId;
    private final String workflowName;
    private final String currentActivityName;
    private final int attempt;
    private final Object input;
    private final Object previousOutput;
    private final Map<String, Object> attributes = new HashMap<>();
    private final JsonUtil jsonUtil;

    private String nextActivityName;
    private Object nextActivityInput;
    private String stateSnapshotJson;

    public DefaultWorkflowContext(String instanceId, 
                                  String workflowName, 
                                  String currentActivityName, 
                                  int attempt, 
                                  Object input, 
                                  Object previousOutput,
                                  JsonUtil jsonUtil) {
        this.instanceId = instanceId;
        this.workflowName = workflowName;
        this.currentActivityName = currentActivityName;
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
        // ?ㅼ젣 援ы쁽?먯꽌??DB?먯꽌 ?쎌뼱??JSON????쭅?ы솕?댁빞 ?섏?留? 
        // ?ш린?쒕뒗 ?꾩옱 硫붾え由ъ뿉 ??λ맂 snapshot??諛섑솚?섍굅??
        // ?앹꽦 ??二쇱엯諛쏆? snapshot??愿由ы븯?꾨줉 ?ㅺ퀎?쒕떎.
        // ?꾩옱 ?명꽣?섏씠??援ъ“???뱀젙 ??낆쑝濡???쭅?ы솕?섎뒗 ?뺣낫媛 遺議깊븯誘濡?
        // ?쇰떒 String ?먮뒗 Raw Map ?뺥깭瑜?怨좊젮?쒕떎.
        return Optional.ofNullable(stateSnapshotJson);
    }

    /**
     * 珥덇린 ?곹깭 ?곗씠?곕? 二쇱엯?섍린 ?꾪븳 硫붿꽌??
     */
    public void setStateData(String stateDataJson) {
        this.stateSnapshotJson = stateDataJson;
    }

    public String getStateSnapshotJson() {
        return stateSnapshotJson;
    }
}

