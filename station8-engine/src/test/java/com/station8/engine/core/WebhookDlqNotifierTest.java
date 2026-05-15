package com.station8.engine.core;

import com.station8.engine.entity.DlqEntry;
import com.station8.engine.util.JsonUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * #134 D8 — {@link WebhookDlqNotifier#notify(DlqEntry, String)} override 동작 검증.
 *
 * <p>로컬 HTTP server 두 개(global / override)를 띄워서 요청이 어디로 갔는지 확인.</p>
 */
class WebhookDlqNotifierTest {

    private final JsonUtil jsonUtil = new JsonUtil();
    private HttpServer globalServer;
    private HttpServer overrideServer;
    private final AtomicReference<String> globalReceived = new AtomicReference<>();
    private final AtomicReference<String> overrideReceived = new AtomicReference<>();

    @BeforeEach
    void startServers() throws IOException {
        globalServer = startServer(globalReceived);
        overrideServer = startServer(overrideReceived);
    }

    @AfterEach
    void stopServers() {
        globalServer.stop(0);
        overrideServer.stop(0);
    }

    private HttpServer startServer(AtomicReference<String> sink) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            sink.set(new String(body));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private String urlOf(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private DlqEntry sample() {
        return new DlqEntry(
                "dlq-1", "inst-1", "exec-1", "wf", "act",
                "NEW", "boom", "stack...", 3, 3, LocalDateTime.now(),
                null, null, null, null, null);
    }

    @Test
    void notify_withOverrideUrl_sendsToOverride() {
        WebhookDlqNotifier notifier = new WebhookDlqNotifier(urlOf(globalServer), jsonUtil);
        notifier.notify(sample(), urlOf(overrideServer));

        assertThat(overrideReceived.get()).isNotNull().contains("dlq-1");
        assertThat(globalReceived.get()).as("global 서버에는 도달하지 않아야 함").isNull();
    }

    @Test
    void notify_withNullOverride_fallsBackToGlobal() {
        WebhookDlqNotifier notifier = new WebhookDlqNotifier(urlOf(globalServer), jsonUtil);
        notifier.notify(sample(), null);

        assertThat(globalReceived.get()).isNotNull().contains("dlq-1");
        assertThat(overrideReceived.get()).isNull();
    }

    @Test
    void notify_withBlankOverride_fallsBackToGlobal() {
        WebhookDlqNotifier notifier = new WebhookDlqNotifier(urlOf(globalServer), jsonUtil);
        notifier.notify(sample(), "   ");

        assertThat(globalReceived.get()).isNotNull().contains("dlq-1");
        assertThat(overrideReceived.get()).isNull();
    }

    @Test
    void notify_singleArg_compatibilityWithGlobal() {
        // 기존 시그니처 (override 없는) — 전역 URL 사용
        WebhookDlqNotifier notifier = new WebhookDlqNotifier(urlOf(globalServer), jsonUtil);
        notifier.notify(sample());

        assertThat(globalReceived.get()).isNotNull().contains("dlq-1");
    }

    @Test
    void notify_neitherUrlConfigured_swallowsAndLogs() {
        // 전역도 null, override도 null → 예외 없이 로깅만
        WebhookDlqNotifier notifier = new WebhookDlqNotifier(null, jsonUtil);
        assertThatNoException().isThrownBy(() -> notifier.notify(sample(), null));
        assertThatNoException().isThrownBy(() -> notifier.notify(sample(), ""));
    }
}
