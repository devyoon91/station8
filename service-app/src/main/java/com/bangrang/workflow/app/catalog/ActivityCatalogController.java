package com.bangrang.workflow.app.catalog;

import com.bangrang.workflow.engine.core.WorkflowRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 액티비티 카탈로그 REST API.
 * <ul>
 *   <li>GET /api/workflow/activities       — 등록된 모든 액티비티 목록</li>
 *   <li>GET /api/workflow/activities/{name} — 단건 상세 (없으면 404)</li>
 * </ul>
 *
 * M3 그래프 빌더(#12)의 노드 팔레트가 본 API를 소비한다.
 */
@RestController
@RequestMapping("/api/workflow/activities")
public class ActivityCatalogController {

    private final WorkflowRegistry workflowRegistry;

    public ActivityCatalogController(WorkflowRegistry workflowRegistry) {
        this.workflowRegistry = workflowRegistry;
    }

    @GetMapping
    public List<ActivityCatalogEntry> list() {
        return workflowRegistry.getActivities().entrySet().stream()
                .map(e -> toEntry(e.getKey(), e.getValue()))
                .sorted((a, b) -> a.activityName().compareToIgnoreCase(b.activityName()))
                .toList();
    }

    @GetMapping("/{name}")
    public ResponseEntity<?> getByName(@PathVariable("name") String name) {
        WorkflowRegistry.ActivityMetadata meta = workflowRegistry.getActivity(name);
        if (meta == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("message", "등록되지 않은 activityName: " + name));
        }
        return ResponseEntity.ok(toEntry(name, meta));
    }

    private ActivityCatalogEntry toEntry(String activityName, WorkflowRegistry.ActivityMetadata meta) {
        Method m = meta.method();
        Class<?> beanClass = meta.bean().getClass();
        // Spring AOP 프록시인 경우 원본 클래스명을 사용
        String beanClassName = ClassUtils.getUserClass(beanClass).getName();
        List<String> paramTypes = Arrays.stream(m.getParameterTypes())
                .map(Class::getName)
                .toList();
        return new ActivityCatalogEntry(
                activityName,
                beanClassName,
                m.getName(),
                meta.annotation().retryCount(),
                meta.annotation().backoffSeconds(),
                paramTypes,
                m.getReturnType().getName()
        );
    }
}
