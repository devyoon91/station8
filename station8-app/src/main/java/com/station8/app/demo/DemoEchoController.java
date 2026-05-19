package com.station8.app.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 데모 HTTP 호출의 endpoint — `demo` 프로파일에서만 활성. 외부 의존 없이 자체 호출 데모를
 * 가능하게 한다.
 *
 * <p>{@code DemoSeedRunner}가 시드하는 두 데모 라인이 이쪽을 부른다:</p>
 * <ul>
 *   <li>{@code GET /api/demo/echo/post} — http→x 데모용. jsonplaceholder 응답을 흉내낸 고정 JSON</li>
 *   <li>{@code POST /api/demo/echo/sink} — x→http 데모용. 받은 본문을 그대로 echo + 타임스탬프</li>
 * </ul>
 *
 * <p>왜 자체 endpoint인가: 폐쇄망 사이트에서는 jsonplaceholder.typicode.com 같은 외부 API에
 * 도달이 안 된다. 데모도 self-contained 일 때 가장 유용하다. 운영 라인에서는 같은 패턴으로
 * 실제 외부 URL을 박으면 된다.</p>
 */
@RestController
@Profile("demo")
@RequestMapping("/api/demo/echo")
public class DemoEchoController {

    /**
     * jsonplaceholder/posts 응답 흉내. http→x 데모 라인이 이 응답의 {@code title}을 다음 노드로 흘린다.
     *
     * @return id/title/body가 있는 단건 JSON
     */
    @GetMapping("/post")
    public Map<String, Object> samplePost() {
        return Map.of(
                "id", 1,
                "userId", 42,
                "title", "sample-from-demo-echo",
                "body", "Demo post body — http.request 응답 shape 검증용");
    }

    /**
     * 받은 본문을 그대로 echo + 서버 타임스탬프. x→http 데모 라인이 이쪽으로 POST 후 응답에서
     * 자기 payload가 다시 들어왔는지로 round-trip 검증.
     *
     * @param payload 호출자가 보낸 임의 JSON
     * @return {@code {receivedAt, echo}} — echo는 payload 그대로
     */
    @PostMapping("/sink")
    public Map<String, Object> sink(@RequestBody(required = false) Map<String, Object> payload) {
        return Map.of(
                "receivedAt", Instant.now().toString(),
                "echo", payload == null ? Map.of() : payload);
    }
}
