package com.bangrang.workflow.app.catalog;

import com.bangrang.workflow.app.Application;
import com.bangrang.workflow.engine.util.JsonUtil;
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
        mvc.perform(get("/api/workflow/activities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.activityName == 'RUN_BATCH_JOB')]").exists())
                .andExpect(jsonPath("$[?(@.activityName == 'MIGRATION_WRITE')]").exists());
    }

    @Test
    void getByName_RUN_BATCH_JOB_returns_metadata() throws Exception {
        mvc.perform(get("/api/workflow/activities/RUN_BATCH_JOB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityName").value("RUN_BATCH_JOB"))
                .andExpect(jsonPath("$.methodName").value("runJob"))
                .andExpect(jsonPath("$.retryCount").value(3))
                .andExpect(jsonPath("$.backoffSeconds").value(60))
                .andExpect(jsonPath("$.parameterTypes[0]").value("java.lang.String"))
                .andExpect(jsonPath("$.returnType").value("java.lang.String"))
                // 프록시 클래스가 아닌 원본 클래스명이 노출되어야 함
                .andExpect(jsonPath("$.beanClass").value("com.bangrang.workflow.app.adapter.SpringBatchActivityAdapter"));
    }

    @Test
    void getByName_unknown_returns_404() throws Exception {
        mvc.perform(get("/api/workflow/activities/NO_SUCH_ACTIVITY"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("NO_SUCH_ACTIVITY")));
    }
}
