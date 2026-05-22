package com.station8.engine.plugin;

import com.station8.engine.annotation.Activity;
import com.station8.engine.core.LineRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
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
 * <p>운영자는 {@code plugins/} 디렉토리에 jar를 떨어뜨리고 앱 재시작 또는
 * <strong>핫 리로드(#103)</strong>로 추가 액티비티를 활성화할 수 있다.</p>
 *
 * <h3>핫 리로드 정책 (#103)</h3>
 * <ul>
 *   <li>D1=(a) Add only — 새로 발견된 ``@Activity``만 추가, 기존은 변경 없음.</li>
 *   <li>D2=(b) 매번 모든 jar에 새 ``URLClassLoader`` 생성. 새 등록이 0건인 jar의 로더는 즉시 close해
 *       메모리 누적 최소화. 신규 등록된 jar의 로더는 활동 인스턴스가 reference하므로 살아남음.</li>
 *   <li>D3=(a) 명시적 트리거만 (``POST /admin/plugins/reload``).</li>
 *   <li>D5 — 같은 이름 충돌 시 새 등록 skip + WARN ({@link LineRegistry} 기존 정책).</li>
 *   <li>D8=(a) reload 호출은 ``synchronized``로 직렬화.</li>
 * </ul>
 *
 * <h3>ClassLoader 정책</h3>
 * <ul>
 *   <li>각 jar는 부모(앱 ClassLoader)를 위임 부모로 갖는 자식 {@link URLClassLoader}로 로드.</li>
 *   <li>플러그인 클래스가 코어/Spring 클래스를 참조하면 부모 위임으로 해결되어 충돌 회피.</li>
 *   <li>플러그인끼리 공유 의존성 충돌은 본 단계에서 다루지 않음.</li>
 * </ul>
 *
 * <h3>실패 처리</h3>
 * <ul>
 *   <li>특정 jar 로드 실패는 WARN 로그 후 다음 jar로 진행. reload 응답에 사유 포함.</li>
 *   <li>플러그인 클래스 인스턴스화 실패 시도 동일.</li>
 * </ul>
 */
@Component
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    private final LineRegistry registry;

    @Value("${engine.plugins.enabled:false}")
    private boolean enabled;

    @Value("${engine.plugins.dir:plugins}")
    private String pluginsDir;

    /** D8 — reload 직렬화. */
    private final Object reloadLock = new Object();

    public PluginLoader(LineRegistry registry) {
        this.registry = registry;
    }

    /**
     * Spring 컨텍스트가 모두 준비된 ApplicationReady 시점에 플러그인 스캔.
     * (LineRegistry가 ContextRefreshed에서 빈 스캔을 끝내야 하므로 이후 단계에서 수행)
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadPlugins() {
        if (!enabled) {
            log.debug("Plugin loading disabled (engine.plugins.enabled=false)");
            return;
        }
        ReloadResult r = reload();
        log.info("Plugin scan (boot) complete: added={}, conflicts={}, skippedJars={}, failedJars={}",
                r.added().size(), r.conflicts().size(), r.skippedJars().size(), r.failedJars().size());
    }

    /**
     * #103 — 멱등 호출 가능한 핫 리로드. 재호출 시 D1=(a) Add only 정책으로 동작.
     *
     * @return 분류된 결과 (added / conflicts / skippedJars / failedJars)
     */
    public ReloadResult reload() {
        synchronized (reloadLock) {
            return reloadInternal();
        }
    }

    private ReloadResult reloadInternal() {
        List<String> added = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        List<String> skippedJars = new ArrayList<>();
        List<FailedJar> failedJars = new ArrayList<>();

        if (!enabled) {
            log.info("Plugin reload requested but engine.plugins.enabled=false");
            return new ReloadResult(added, conflicts, skippedJars, failedJars);
        }

        File dir = new File(pluginsDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log.info("Plugins directory not found: {} (reload no-op)", dir.getAbsolutePath());
            return new ReloadResult(added, conflicts, skippedJars, failedJars);
        }

        File[] jars = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            log.info("No plugin jars found in {}", dir.getAbsolutePath());
            return new ReloadResult(added, conflicts, skippedJars, failedJars);
        }

        String hostApiVersion = VersionCompatibility.hostApiVersion();
        for (File jar : jars) {
            try {
                // #320 — scanAndRegister 진입 전에 SDK 버전 호환성 검사. REJECTED면 친절한 거부 메시지로
                // failedJars에 분류, NoSuchMethodError가 런타임에 떨어지기 전에 차단.
                VersionCompatibility.Check check = checkVersion(jar, hostApiVersion);
                if (check.result() == VersionCompatibility.Result.REJECTED) {
                    log.warn("Plugin {} 거부 (SDK 비호환): {}", jar.getName(), check.message());
                    failedJars.add(new FailedJar(jar.getName(), "SDK 비호환: " + check.message()));
                    continue;
                }
                if (check.result() == VersionCompatibility.Result.UNSPECIFIED) {
                    log.warn("Plugin {} SDK 호환성 미확정: {}", jar.getName(), check.message());
                }

                JarScanResult r = scanAndRegister(jar);
                added.addAll(r.added);
                conflicts.addAll(r.conflicts);
                if (r.added.isEmpty() && r.conflicts.isEmpty()) {
                    // jar에 @Activity가 아예 없었거나 모든 클래스 인스턴스화 실패
                    skippedJars.add(jar.getName());
                } else if (r.added.isEmpty()) {
                    // 새 등록 없음 — 이미 모두 등록된 jar (다음 reload에서도 같은 결과)
                    skippedJars.add(jar.getName());
                }
                log.info("Plugin reload jar {}: added={}, conflicts={}",
                        jar.getName(), r.added.size(), r.conflicts.size());
            } catch (Exception e) {
                log.warn("Failed to load plugin: {} ({}: {})",
                        jar.getName(), e.getClass().getSimpleName(), e.getMessage());
                failedJars.add(new FailedJar(jar.getName(),
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return new ReloadResult(
                Collections.unmodifiableList(added),
                Collections.unmodifiableList(conflicts),
                Collections.unmodifiableList(skippedJars),
                Collections.unmodifiableList(failedJars));
    }

    /**
     * 단일 jar에서 ``@Activity`` 어노테이션이 붙은 메서드를 찾아 레지스트리에 등록.
     * D2=(b) — 매번 새 URLClassLoader. 새 등록 0건이면 로더 close (메모리 leak 최소화).
     */
    JarScanResult scanAndRegister(File jar) throws Exception {
        URL[] urls = { jar.toURI().toURL() };
        URLClassLoader loader = new URLClassLoader(urls, PluginLoader.class.getClassLoader());
        List<String> added = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        boolean keepLoaderOpen = false;

        try {
            List<String> classNames = listClassNames(jar);
            for (String className : classNames) {
                Class<?> clazz;
                try {
                    clazz = Class.forName(className, true, loader);
                } catch (Throwable t) {
                    log.debug("Skip class load failure {}: {}", className, t.getMessage());
                    continue;
                }

                Method[] methods;
                try {
                    methods = clazz.getDeclaredMethods();
                } catch (Throwable t) {
                    continue;
                }

                Object instance = null;
                for (Method m : methods) {
                    Activity ann = m.getAnnotation(Activity.class);
                    if (ann == null) continue;

                    String name = ann.value().isEmpty() ? m.getName() : ann.value();
                    if (registry.getActivity(name) != null) {
                        // D5 — 충돌은 무시 + WARN, 결과에 분류만
                        conflicts.add(name);
                        log.warn("Plugin {} activity '{}' already registered — skipping (Add-only mode)",
                                jar.getName(), name);
                        continue;
                    }

                    if (instance == null) {
                        try {
                            instance = clazz.getDeclaredConstructor().newInstance();
                        } catch (Throwable t) {
                            log.warn("Plugin class {} has @Activity but cannot be instantiated ({}: {}) — skipping",
                                    className, t.getClass().getSimpleName(), t.getMessage());
                            break;
                        }
                    }
                    registry.registerActivity(name, instance, m, ann);
                    added.add(name);
                    keepLoaderOpen = true;
                    log.info("Registered plugin activity: {} -> {}.{}", name, clazz.getSimpleName(), m.getName());
                }
            }
        } finally {
            if (!keepLoaderOpen) {
                // D2=(b) 메모리 leak 최소화 — 새 등록 없는 jar의 ClassLoader는 즉시 close
                try { loader.close(); } catch (Exception ignore) { }
            }
            // keepLoaderOpen=true면 loader는 등록된 활동 인스턴스가 reference 유지 → JVM 종료까지 살아있음
        }
        return new JarScanResult(added, conflicts);
    }

    /**
     * #320 — jar의 MANIFEST.MF에서 SDK 버전 헤더 읽어 호스트와 호환성 검증.
     *
     * @param jar            플러그인 jar 파일
     * @param hostApiVersion 호스트 SDK 버전 (null 가능)
     * @return 호환성 결과
     * @throws IOException MANIFEST 읽기 실패 시
     */
    VersionCompatibility.Check checkVersion(File jar, String hostApiVersion) throws IOException {
        try (JarFile jf = new JarFile(jar)) {
            String pluginVersion = VersionCompatibility.readPluginApiVersion(jf);
            return VersionCompatibility.check(hostApiVersion, pluginVersion);
        }
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

    // ---- result types ----

    /**
     * #103 reload 결과.
     *
     * @param added       새로 등록된 액티비티 이름
     * @param conflicts   같은 이름이 이미 등록되어 무시된 액티비티 이름 (다음 reload에도 동일)
     * @param skippedJars 새 등록 0건이었던 jar 이름 (이미 처리 끝났거나 @Activity 없음)
     * @param failedJars  로드/스캔 실패한 jar + 사유
     */
    public record ReloadResult(
            List<String> added,
            List<String> conflicts,
            List<String> skippedJars,
            List<FailedJar> failedJars
    ) {}

    public record FailedJar(String name, String error) {}

    /** 단일 jar 스캔의 분류 결과. PluginLoader 내부용. */
    record JarScanResult(List<String> added, List<String> conflicts) {}
}
