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
 * @Workflow 諛?@Activity媛 遺숈? 鍮덇낵 硫붿꽌?쒕? ?ㅼ틪?섏뿬 愿由ы븯???덉??ㅽ듃由?
 * Spring 而⑦뀓?ㅽ듃 濡쒕뵫???꾨즺????鍮덈뱾???먯깋?⑸땲??
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
            // ?꾨줉??媛앹껜??寃쎌슦 ?먮낯 ?대옒?ㅻ? 媛?몄샂
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

    /**
     * ?≫떚鍮꾪떚 ?ㅽ뻾???꾩슂??硫뷀??곗씠??(???鍮? ?ㅽ뻾 硫붿꽌?? ?대끂?뚯씠???뺣낫)
     */
    public record ActivityMetadata(Object bean, Method method, Activity annotation) {
    }
}

