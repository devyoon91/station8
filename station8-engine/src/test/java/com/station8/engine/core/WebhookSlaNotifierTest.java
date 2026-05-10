package com.station8.engine.core;

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
 * #138 — {@link WebhookSlaNotifier} 발송/override/fallback 검증.
 */
class WebhookSlaNotifierTest {

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

    private SlaViolation sample() {
        return new SlaViolation("inst-1", "FlowA",
                LocalDateTime.of(2026, 5, 10, 10, 0),
                3700, 3600, SlaAction.AUTO_TERMINATE);
    }

    @Test
    void notify_withOverrideUrl_sendsToOverride() {
        WebhookSlaNotifier notifier = new WebhookSlaNotifier(urlOf(globalServer), jsonUtil);
        notifier.notify(sample(), urlOf(overrideServer));

        assertThat(overrideReceived.get()).isNotNull()
                .contains("SLA_VIOLATION").contains("inst-1").contains("AUTO_TERMINATE")
                .contains("3700").contains("3600");
        assertThat(globalReceived.get()).as("global 서버에는 도달하지 않아야 함").isNull();
    }

    @Test
    void notify_withNullOverride_fallsBackToGlobal() {
        WebhookSlaNotifier notifier = new WebhookSlaNotifier(urlOf(globalServer), jsonUtil);
        notifier.notify(sample(), null);

        assertThat(globalReceived.get()).isNotNull().contains("SLA_VIOLATION");
        assertThat(overrideReceived.get()).isNull();
    }

    @Test
    void notify_withBlankOverride_fallsBackToGlobal() {
        WebhookSlaNotifier notifier = new WebhookSlaNotifier(urlOf(globalServer), jsonUtil);
        notifier.notify(sample(), "   ");

        assertThat(globalReceived.get()).isNotNull();
        assertThat(overrideReceived.get()).isNull();
    }

    @Test
    void notify_neitherUrlConfigured_swallowsAndLogs() {
        WebhookSlaNotifier notifier = new WebhookSlaNotifier(null, jsonUtil);
        assertThatNoException().isThrownBy(() -> notifier.notify(sample(), null));
        assertThatNoException().isThrownBy(() -> notifier.notify(sample(), ""));
    }

    @Test
    void payload_includesAllFields() {
        WebhookSlaNotifier notifier = new WebhookSlaNotifier(urlOf(globalServer), jsonUtil);
        notifier.notify(sample(), null);

        String body = globalReceived.get();
        assertThat(body)
                .contains("\"type\":\"SLA_VIOLATION\"")
                .contains("\"instanceId\":\"inst-1\"")
                .contains("\"workflowName\":\"FlowA\"")
                .contains("\"elapsedSeconds\":3700")
                .contains("\"thresholdSeconds\":3600")
                .contains("\"action\":\"AUTO_TERMINATE\"");
    }
}
