package com.bangrang.workflow.engine.core;

import com.bangrang.workflow.engine.annotation.Activity;
import com.bangrang.workflow.engine.annotation.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Workflow 및 @Activity가 붙은 빈과 메서드를 스캔하여 관리하는 레지스트리.
 * Spring 컨텍스트 로딩이 완료된 후 빈들을 탐색합니다.
 */
@Component
public class WorkflowRegistry implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);

    // key: workflowName, value: Bean Object
    private final Map<String, Object> workflowBeans = new ConcurrentHashMap<>();
    
    // key: activityName, value: ActivityMetadata (Bean + Method)
    private final Map<String, ActivityMetadata> activityMap = new ConcurrentHashMap<>();

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        ApplicationContext context = event.getApplicationContext();
        scanWorkflows(context);
        scanActivities(context);
    }

    private void scanWorkflows(ApplicationContext context) {
        Map<String, Object> beans = context.getBeansWithAnnotation(Workflow.class);
        for (Object bean : beans.values()) {
            Workflow workflow = AnnotationUtils.findAnnotation(bean.getClass(), Workflow.class);
            if (workflow != null) {
                String name = workflow.value().isEmpty() ? bean.getClass().getSimpleName() : workflow.value();
                workflowBeans.put(name, bean);
                log.info("Registered Workflow: {} -> {}", name, bean.getClass().getName());
            }
        }
    }

    private void scanActivities(ApplicationContext context) {
        String[] beanNames = context.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            Object bean = context.getBean(beanName);
            // 프록시 객체인 경우 원본 클래스를 가져옴
            Class<?> targetClass = bean.getClass();
            
            ReflectionUtils.doWithMethods(targetClass, method -> {
                Activity activity = AnnotationUtils.findAnnotation(method, Activity.class);
                if (activity != null) {
                    String name = activity.value().isEmpty() ? method.getName() : activity.value();
                    activityMap.put(name, new ActivityMetadata(bean, method, activity));
                    log.info("Registered Activity: {} -> {}.{}", name, targetClass.getSimpleName(), method.getName());
                }
            });
        }
    }

    public Object getWorkflowBean(String name) {
        return workflowBeans.get(name);
    }

    public ActivityMetadata getActivity(String name) {
        return activityMap.get(name);
    }

    /** 등록된 액티비티 이름 전체 (DAG 검증/카탈로그용 읽기 전용 뷰). */
    public java.util.Set<String> getActivityNames() {
        return java.util.Collections.unmodifiableSet(activityMap.keySet());
    }

    /** 등록된 액티비티 메타데이터 전체 (카탈로그 API용 읽기 전용 뷰). */
    public java.util.Map<String, ActivityMetadata> getActivities() {
        return java.util.Collections.unmodifiableMap(activityMap);
    }

    /**
     * 액티비티 실행에 필요한 메타데이터 (대상 빈, 실행 메서드, 어노테이션 정보)
     */
    public record ActivityMetadata(Object bean, Method method, Activity annotation) {
    }
}

