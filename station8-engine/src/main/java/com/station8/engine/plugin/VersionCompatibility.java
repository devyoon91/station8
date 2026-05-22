package com.station8.engine.plugin;

import com.station8.engine.annotation.Activity;

import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * 플러그인 jar과 호스트의 {@code station8-engine-api} SDK 버전 호환성 검증 (#320).
 *
 * <p>플러그인은 호스트 JVM과 같은 클래스로더 패밀리에 로드되므로 SDK jar이 단일 source of truth.
 * 플러그인이 호스트보다 높은 SDK 버전을 사용하면 런타임에 {@code NoSuchMethodError}가
 * 떨어질 수 있다 — 그 전에 jar 업로드 시점에서 친절한 거부 메시지로 막는다.</p>
 *
 * <h3>호환성 규칙 (RFC engine-artifact-distribution.md)</h3>
 * <ul>
 *   <li>플러그인 MANIFEST의 {@code Station8-Engine-Api-Version} 헤더 부재 → {@link Result#UNSPECIFIED}.
 *     기존 plugin jar 호환성을 위해 거부하지 않고 WARN 후 통과.</li>
 *   <li>버전 파싱 실패 → {@link Result#UNSPECIFIED}.</li>
 *   <li>호스트 버전이 {@code 0.0.x} (분리 전 best-effort 영역) → {@link Result#UNSPECIFIED}.</li>
 *   <li>플러그인 major ≠ 호스트 major → {@link Result#REJECTED} (breaking).</li>
 *   <li>플러그인 minor &gt; 호스트 minor (같은 major) → {@link Result#REJECTED} (forward incompat).</li>
 *   <li>그 외 → {@link Result#COMPATIBLE}.</li>
 * </ul>
 *
 * <h3>호스트 버전 조회</h3>
 *
 * <p>{@link Activity} 클래스는 {@code station8-engine-api} jar에 속하므로
 * {@code Activity.class.getPackage().getImplementationVersion()} 으로 SDK jar의 MANIFEST에서
 * {@code Implementation-Version}을 읽는다. api 모듈 {@code build.gradle}에서 박는 값.</p>
 *
 * <p>본 클래스는 stateless utility — 인스턴스 생성 불요.</p>
 */
public final class VersionCompatibility {

    /** 플러그인 jar MANIFEST에서 읽는 헤더 이름. */
    public static final String PLUGIN_API_VERSION_ATTR = "Station8-Engine-Api-Version";

    private VersionCompatibility() {
        // util — 인스턴스 생성 금지
    }

    /**
     * 호환성 검증 결과 분류.
     */
    public enum Result {
        /** 안전하게 로드 가능. */
        COMPATIBLE,
        /** 검증 생략 — 헤더 없음 / 버전 파싱 실패 / 호스트가 0.0.x. WARN 후 통과 권장. */
        UNSPECIFIED,
        /** 비호환 — 호스트 부팅 시 NoSuchMethodError 가능성. 로드 거부 권장. */
        REJECTED
    }

    /**
     * 호스트의 station8-engine-api 버전. SDK 모듈 분리 전 또는 jar이 아닌 경로(예: IDE
     * exploded classes)로 실행될 때 null 가능.
     *
     * @return SDK 버전 문자열 (예: {@code "0.1.0"}), 조회 실패 시 null
     */
    public static String hostApiVersion() {
        Package p = Activity.class.getPackage();
        if (p == null) {
            return null;
        }
        return p.getImplementationVersion();
    }

    /**
     * jar MANIFEST에서 플러그인이 빌드된 SDK 버전 읽기.
     *
     * @param jar 열린 JarFile
     * @return 플러그인이 선언한 SDK 버전, 헤더 부재 시 null
     * @throws IOException MANIFEST 읽기 실패 시
     */
    public static String readPluginApiVersion(JarFile jar) throws IOException {
        Manifest mf = jar.getManifest();
        if (mf == null) {
            return null;
        }
        Attributes attrs = mf.getMainAttributes();
        return attrs.getValue(PLUGIN_API_VERSION_ATTR);
    }

    /**
     * 두 버전 문자열을 비교해 호환성 판단.
     *
     * @param hostVersion   호스트 SDK 버전 ({@link #hostApiVersion()})
     * @param pluginVersion 플러그인이 선언한 SDK 버전 ({@link #readPluginApiVersion(JarFile)})
     * @return 분류된 결과 + 사람용 메시지
     */
    public static Check check(String hostVersion, String pluginVersion) {
        if (pluginVersion == null) {
            return new Check(Result.UNSPECIFIED, hostVersion, null,
                    "플러그인 MANIFEST.MF에 " + PLUGIN_API_VERSION_ATTR
                            + " 헤더 미선언 — 호환성 검증 생략");
        }
        Sem host = Sem.parse(hostVersion);
        Sem plugin = Sem.parse(pluginVersion);
        if (host == null || plugin == null) {
            return new Check(Result.UNSPECIFIED, hostVersion, pluginVersion,
                    "버전 파싱 실패 (host=" + hostVersion + ", plugin=" + pluginVersion
                            + ") — 호환성 검증 생략");
        }
        if (host.major == 0 && host.minor == 0) {
            return new Check(Result.UNSPECIFIED, hostVersion, pluginVersion,
                    "호스트 SDK가 0.0.x — semver 보증 없음, 호환성 검증 생략");
        }
        if (plugin.major != host.major) {
            return new Check(Result.REJECTED, hostVersion, pluginVersion,
                    "SDK major 버전 불일치 — 플러그인 재빌드 필요 (호스트 "
                            + host + " ↔ 플러그인 " + plugin + ")");
        }
        if (plugin.minor > host.minor) {
            return new Check(Result.REJECTED, hostVersion, pluginVersion,
                    "플러그인이 호스트보다 새 SDK를 요구 — 호스트 업그레이드 필요 (호스트 "
                            + host + " ↔ 플러그인 " + plugin + ")");
        }
        return new Check(Result.COMPATIBLE, hostVersion, pluginVersion,
                "호환 (호스트 " + host + " ↔ 플러그인 " + plugin + ")");
    }

    /**
     * {@link #check} 결과 record.
     *
     * @param result        분류
     * @param hostVersion   원본 호스트 버전 문자열 (null 가능)
     * @param pluginVersion 원본 플러그인 버전 문자열 (null 가능)
     * @param message       사람용 메시지 (로그/UI 노출용)
     */
    public record Check(Result result, String hostVersion, String pluginVersion, String message) {}

    /**
     * 단순화된 semver 파싱. pre-release / build metadata는 무시 (예: {@code 0.1.0-SNAPSHOT} → {@code 0.1.0}).
     *
     * @param major MAJOR
     * @param minor MINOR
     * @param patch PATCH
     */
    record Sem(int major, int minor, int patch) {

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }

        /**
         * {@code "MAJOR.MINOR[.PATCH][-pre|+build]"} 형식 파싱. 실패 시 null.
         *
         * @param v 버전 문자열
         * @return Sem 또는 null
         */
        static Sem parse(String v) {
            if (v == null || v.isBlank()) {
                return null;
            }
            String clean = v.split("[-+]", 2)[0];
            String[] parts = clean.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            try {
                int maj = Integer.parseInt(parts[0]);
                int min = Integer.parseInt(parts[1]);
                int patch = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
                return new Sem(maj, min, patch);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
