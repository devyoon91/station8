package com.bangrang.workflow.engine.plugin;

import com.bangrang.workflow.engine.annotation.Activity;
import com.bangrang.workflow.engine.core.WorkflowRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 외부 ``plugins/*.jar``를 부팅 후 스캔하여 `@Activity` 메서드를 동적으로 등록하는 로더.
 *
 * <p>사용법:</p>
 * <pre>{@code
 * # application.properties
 * engine.plugins.dir=plugins
 * engine.plugins.enabled=true
 * }</pre>
 *
 * <p>운영자는 {@code plugins/} 디렉토리에 jar를 떨어뜨리고 앱을 재시작하면 추가 액티비티가
 * 자동 등록된다. (재시작 없는 핫 리로드는 #20 후속 이슈에서 다룸)</p>
 *
 * <h3>ClassLoader 정책</h3>
 * <ul>
 *   <li>각 jar는 부모(앱 ClassLoader)를 위임 부모로 갖는 자식 {@link URLClassLoader}로 로드.</li>
 *   <li>플러그인 클래스가 코어/Spring 클래스를 참조하면 부모 위임으로 해결되어 충돌 회피.</li>
 *   <li>플러그인끼리 공유 의존성 충돌은 본 단계에서 다루지 않음 (#20에서 격리 정책 추가).</li>
 * </ul>
 *
 * <h3>실패 처리</h3>
 * <ul>
 *   <li>특정 jar 로드 실패는 WARN 로그 후 다음 jar로 진행.</li>
 *   <li>플러그인 클래스 인스턴스화 실패 시도 동일.</li>
 * </ul>
 */
@Component
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    private final WorkflowRegistry registry;

    @Value("${engine.plugins.enabled:false}")
    private boolean enabled;

    @Value("${engine.plugins.dir:plugins}")
    private String pluginsDir;

    public PluginLoader(WorkflowRegistry registry) {
        this.registry = registry;
    }

    /**
     * Spring 컨텍스트가 모두 준비된 ApplicationReady 시점에 플러그인 스캔.
     * (WorkflowRegistry가 ContextRefreshed에서 빈 스캔을 끝내야 하므로 이후 단계에서 수행)
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadPlugins() {
        if (!enabled) {
            log.debug("Plugin loading disabled (engine.plugins.enabled=false)");
            return;
        }

        File dir = new File(pluginsDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log.info("Plugins directory not found: {} (skipping plugin scan)", dir.getAbsolutePath());
            return;
        }

        File[] jars = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            log.info("No plugin jars found in {}", dir.getAbsolutePath());
            return;
        }

        int loaded = 0;
        int failed = 0;
        for (File jar : jars) {
            try {
                int registered = scanAndRegister(jar);
                log.info("Plugin loaded: {} ({} activities)", jar.getName(), registered);
                loaded++;
            } catch (Exception e) {
                log.warn("Failed to load plugin: {} ({}: {})",
                        jar.getName(), e.getClass().getSimpleName(), e.getMessage());
                failed++;
            }
        }
        log.info("Plugin scan complete: {} loaded, {} failed", loaded, failed);
    }

    /**
     * 단일 jar에서 ``@Activity`` 어노테이션이 붙은 메서드를 찾아 레지스트리에 등록.
     *
     * @return 등록된 액티비티 개수
     */
    int scanAndRegister(File jar) throws Exception {
        URL[] urls = { jar.toURI().toURL() };
        // 부모 위임 → 코어 + Spring 클래스 공유. 자식 로더는 jar 내부 클래스만 로드.
        URLClassLoader loader = new URLClassLoader(urls, PluginLoader.class.getClassLoader());

        List<String> classNames = listClassNames(jar);
        int count = 0;
        for (String className : classNames) {
            Class<?> clazz;
            try {
                clazz = Class.forName(className, true, loader);
            } catch (Throwable t) {
                log.debug("Skip class load failure {}: {}", className, t.getMessage());
                continue;
            }

            // @Activity 붙은 메서드 발견 시 인스턴스 생성 + 등록
            Method[] methods;
            try {
                methods = clazz.getDeclaredMethods();
            } catch (Throwable t) {
                continue; // 의존 누락 등으로 메서드 메타 추출 실패 시 스킵
            }

            Object instance = null;
            for (Method m : methods) {
                Activity ann = m.getAnnotation(Activity.class);
                if (ann == null) continue;

                if (instance == null) {
                    try {
                        instance = clazz.getDeclaredConstructor().newInstance();
                    } catch (Throwable t) {
                        // 운영 트러블슈팅을 위해 cause 포함
                        log.warn("Plugin class {} has @Activity but cannot be instantiated ({}: {}) — skipping",
                                className, t.getClass().getSimpleName(), t.getMessage());
                        break;
                    }
                }
                String name = ann.value().isEmpty() ? m.getName() : ann.value();
                registry.registerActivity(name, instance, m, ann);
                log.info("Registered plugin activity: {} -> {}.{}", name, clazz.getSimpleName(), m.getName());
                count++;
            }
        }
        return count;
    }

    private List<String> listClassNames(File jar) throws Exception {
        List<String> names = new ArrayList<>();
        try (JarFile jf = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                if (name.endsWith(".class") && !name.contains("$")) {
                    names.add(name.replace('/', '.').substring(0, name.length() - 6));
                }
            }
        }
        return names;
    }
}
