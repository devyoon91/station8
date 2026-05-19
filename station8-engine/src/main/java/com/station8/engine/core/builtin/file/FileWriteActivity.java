package com.station8.engine.core.builtin.file;

import com.station8.engine.annotation.Activity;
import com.station8.engine.annotation.LineDefinition;
import com.station8.engine.core.NoRetryException;
import com.station8.engine.util.JsonUtil;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M19 built-in 파일 write 활동 — `@Activity("file.write")`.
 *
 * <p>{@link FileSystemRegistry}를 거쳐 byte를 backend에 위임. format에 따라 content를 byte로
 * 직렬화하고, path는 {@link FilePathPolicy}가 미리 검증.</p>
 *
 * <h3>응답</h3>
 * <pre>{@code
 *   { "uri": "file:///...", "sizeBytes": 1234 }
 * }</pre>
 *
 * <h3>content 타입 by format</h3>
 * <ul>
 *   <li>{@code text} — String. encoding으로 encode</li>
 *   <li>{@code json} — Object/List/Map/String. Jackson serialize</li>
 *   <li>{@code binary} — String (Base64). decode 후 byte</li>
 * </ul>
 */
@Component
@LineDefinition("FileBuiltin")
public class FileWriteActivity {

    private final JsonUtil jsonUtil;
    private final FileSystemRegistry registry;

    public FileWriteActivity(JsonUtil jsonUtil, FileSystemRegistry registry) {
        this.jsonUtil = jsonUtil;
        this.registry = registry;
    }

    @Activity(value = "file.write", retryCount = 3, backoffSeconds = 2,
            description = "파일 쓰기 — built-in. URI는 file:// (SFTP/S3는 별도 sub-issue), format=text|json|binary.")
    public String write(String inputJson) {
        FileWriteInput input = parseInput(inputJson);
        validate(input);

        byte[] bytes = encode(input);
        registry.write(input.uri(), bytes);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uri", input.uri());
        result.put("sizeBytes", bytes.length);
        return jsonUtil.toJson(result);
    }

    private FileWriteInput parseInput(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            throw new NoRetryException("file.write input is empty");
        }
        try {
            return jsonUtil.fromJson(inputJson, FileWriteInput.class);
        } catch (Exception ex) {
            throw new NoRetryException("file.write input parse failed: " + ex.getMessage(), ex);
        }
    }

    private static void validate(FileWriteInput input) {
        if (input.uri() == null || input.uri().isBlank()) {
            throw new NoRetryException("file.write uri is required");
        }
        String fmt = input.effectiveFormat();
        if (!"text".equals(fmt) && !"json".equals(fmt) && !"binary".equals(fmt)) {
            throw new NoRetryException(
                    "file.write format not supported: " + fmt + " (allowed: text/json/binary)");
        }
    }

    private byte[] encode(FileWriteInput input) {
        String format = input.effectiveFormat();
        Object content = input.content();
        switch (format) {
            case "binary": {
                if (!(content instanceof String s)) {
                    throw new NoRetryException(
                            "file.write format=binary requires content as Base64 String, got "
                                    + (content == null ? "null" : content.getClass().getSimpleName()));
                }
                try {
                    return Base64.getDecoder().decode(s);
                } catch (IllegalArgumentException ex) {
                    throw new NoRetryException("file.write content is not valid Base64: " + ex.getMessage(), ex);
                }
            }
            case "json": {
                if (content == null) {
                    return new byte[0];
                }
                Charset cs = charset(input.effectiveEncoding());
                try {
                    return jsonUtil.toJson(content).getBytes(cs);
                } catch (Exception ex) {
                    throw new NoRetryException("file.write JSON serialize failed: " + ex.getMessage(), ex);
                }
            }
            case "text":
            default: {
                if (content == null) {
                    return new byte[0];
                }
                if (!(content instanceof String s)) {
                    // Object를 text로 쓰면 ambiguous — fail-fast
                    throw new NoRetryException(
                            "file.write format=text requires content as String, got "
                                    + content.getClass().getSimpleName()
                                    + ". format=json으로 바꾸거나 content를 String으로 직렬화하세요.");
                }
                return s.getBytes(charset(input.effectiveEncoding()));
            }
        }
    }

    private static Charset charset(String encoding) {
        try {
            return Charset.forName(encoding);
        } catch (IllegalArgumentException ex) {
            // UnsupportedCharsetException extends IllegalArgumentException — superclass로 cover.
            throw new NoRetryException("file.write encoding not supported: " + encoding);
        }
    }
}
