package com.bangrang.workflow.engine.util;

import com.bangrang.workflow.engine.exception.ErrorCodes;
import com.bangrang.workflow.engine.exception.WorkflowEngineException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

@Component
public class JsonUtil {
    private final ObjectMapper objectMapper;

    public JsonUtil() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public String toJson(Object object) {
        if (object == null) return null;
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new WorkflowEngineException(ErrorCodes.JSON_SERIALIZATION_ERROR, "JSON serialization error", e);
        }
    }

    public <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new WorkflowEngineException(ErrorCodes.JSON_DESERIALIZATION_ERROR, "JSON deserialization error", e);
        }
    }
}

