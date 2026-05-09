package com.station8.engine.util;

import com.station8.engine.exception.ErrorCodes;
import com.station8.engine.exception.LineEngineException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

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
            throw new LineEngineException(ErrorCodes.JSON_SERIALIZATION_ERROR, "JSON serialization error", e);
        }
    }

    public <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new LineEngineException(ErrorCodes.JSON_DESERIALIZATION_ERROR, "JSON deserialization error", e);
        }
    }

    /** flat ``Map<String, String>`` 역직렬화. null/blank이면 빈 LinkedHashMap. */
    public Map<String, String> fromJsonToStringMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new LineEngineException(ErrorCodes.JSON_DESERIALIZATION_ERROR, "JSON deserialization error", e);
        }
    }
}

