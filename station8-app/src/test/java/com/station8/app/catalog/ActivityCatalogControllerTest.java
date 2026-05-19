package com.station8.app.catalog;

import com.station8.app.Application;
import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class ActivityCatalogControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JsonUtil jsonUtil;

    @Test
    void list_returns_registered_activities_including_RUN_BATCH_JOB_and_MIGRATION_WRITE() throws Exception {
        mvc.perform(get("/api/line/activities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.activityName == 'RUN_BATCH_JOB')]").exists())
                .andExpect(jsonPath("$[?(@.activityName == 'MIGRATION_WRITE')]").exists());
    }

    @Test
    void getByName_RUN_BATCH_JOB_returns_metadata() throws Exception {
        mvc.perform(get("/api/line/activities/RUN_BATCH_JOB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityName").value("RUN_BATCH_JOB"))
                .andExpect(jsonPath("$.methodName").value("runJob"))
                .andExpect(jsonPath("$.retryCount").value(3))
                .andExpect(jsonPath("$.backoffSeconds").value(60))
                .andExpect(jsonPath("$.parameterTypes[0]").value("java.lang.String"))
                .andExpect(jsonPath("$.returnType").value("java.lang.String"))
                // 프록시 클래스가 아닌 원본 클래스명이 노출되어야 함
                .andExpect(jsonPath("$.beanClass").value("com.station8.app.adapter.SpringBatchActivityAdapter"));
    }

    @Test
    void getByName_unknown_returns_404() throws Exception {
        mvc.perform(get("/api/line/activities/NO_SUCH_ACTIVITY"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("NO_SUCH_ACTIVITY")));
    }

    // ============ #304 — @ActivityParam schema 응답 ============

    @Test
    void getByName_httpRequest_exposesParamSchema() throws Exception {
        mvc.perform(get("/api/line/activities/http.request"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.params").isArray())
                // method 필드 — SELECT kind + options
                .andExpect(jsonPath("$.params[?(@.name == 'method')].kind").value("SELECT"))
                .andExpect(jsonPath("$.params[?(@.name == 'method')].required").value(true))
                .andExpect(jsonPath("$.params[?(@.name == 'method')].options[*]",
                        org.hamcrest.Matchers.hasItems("GET", "POST", "PUT", "DELETE", "PATCH")))
                // url 필드 — STRING + required
                .andExpect(jsonPath("$.params[?(@.name == 'url')].kind").value("STRING"))
                .andExpect(jsonPath("$.params[?(@.name == 'url')].required").value(true))
                // credentialId 필드 — CREDENTIAL kind + type 화이트리스트
                .andExpect(jsonPath("$.params[?(@.name == 'credentialId')].kind").value("CREDENTIAL"))
                .andExpect(jsonPath("$.params[?(@.name == 'credentialId')].options[*]",
                        org.hamcrest.Matchers.hasItem("http_bearer")));
    }

    @Test
    void getByName_fileRead_exposesParamSchema() throws Exception {
        mvc.perform(get("/api/line/activities/file.read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.params[?(@.name == 'uri')].required").value(true))
                .andExpect(jsonPath("$.params[?(@.name == 'format')].kind").value("SELECT"))
                .andExpect(jsonPath("$.params[?(@.name == 'format')].defaultValue").value("text"));
    }

    @Test
    void getByName_noSchemaActivity_returnsEmptyParams() throws Exception {
        // NOOP 같이 schema 안 박은 활동은 빈 params — 빌더에서 textarea fallback 발동
        mvc.perform(get("/api/line/activities/NOOP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.params").isArray())
                .andExpect(jsonPath("$.params.length()").value(0));
    }
}
