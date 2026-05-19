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
 * M19 built-in 파일 read 활동 — `@Activity("file.read")`.
 *
 * <p>{@link FileSystemRegistry}가 URI scheme 보고 backend를 dispatch. local 한 backend만 등록된
 * 시점에서는 `file://` URI만 동작. SFTP / S3는 별도 sub-issue에서 추가될 때 추가 wiring 0.</p>
 *
 * <h3>응답</h3>
 * <pre>{@code
 *   { "uri": "file:///...", "format": "json", "sizeBytes": 1234, "content": <Object 또는 String> }
 * }</pre>
 *
 * <p>{@code content} 타입:</p>
 * <ul>
 *   <li>{@code text} — String (encoding으로 decode)</li>
 *   <li>{@code json} — Object/List/Map (Jackson parse)</li>
 *   <li>{@code binary} — String (Base64 encode)</li>
 * </ul>
 *
 * <p>{@code sizeBytes}는 raw 파일 크기 — content가 binary면 base64 길이가 아닌 원본 크기.</p>
 */
@Component
@LineDefinition("FileBuiltin")
public class FileReadActivity {

    private final JsonUtil jsonUtil;
    private final FileSystemRegistry registry;

    public FileReadActivity(JsonUtil jsonUtil, FileSystemRegistry registry) {
        this.jsonUtil = jsonUtil;
        this.registry = registry;
    }

    @Activity(value = "file.read", retryCount = 3, backoffSeconds = 2,
            description = "파일 읽기 — built-in. URI는 file:// (SFTP/S3는 별도 sub-issue), format=text|json|binary.")
    public String read(String inputJson) {
        FileReadInput input = parseInput(inputJson);
        validate(input);

        byte[] bytes = registry.read(input.uri(), input.credentialId());
        String format = input.effectiveFormat();
        Object content = decode(bytes, format, input.effectiveEncoding());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uri", input.uri());
        result.put("format", format);
        result.put("sizeBytes", bytes.length);
        result.put("content", content);
        return jsonUtil.toJson(result);
    }

    private FileReadInput parseInput(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            throw new NoRetryException("file.read input is empty");
        }
        try {
            return jsonUtil.fromJson(inputJson, FileReadInput.class);
        } catch (Exception ex) {
            throw new NoRetryException("file.read input parse failed: " + ex.getMessage(), ex);
        }
    }

    private static void validate(FileReadInput input) {
        if (input.uri() == null || input.uri().isBlank()) {
            throw new NoRetryException("file.read uri is required");
        }
        String fmt = input.effectiveFormat();
        if (!"text".equals(fmt) && !"json".equals(fmt) && !"binary".equals(fmt)) {
            throw new NoRetryException(
                    "file.read format not supported: " + fmt + " (allowed: text/json/binary)");
        }
    }

    private Object decode(byte[] bytes, String format, String encoding) {
        switch (format) {
            case "binary":
                return Base64.getEncoder().encodeToString(bytes);
            case "json": {
                String raw = decodeAsString(bytes, encoding);
                try {
                    return jsonUtil.fromJson(raw, Object.class);
                } catch (Exception ex) {
                    throw new NoRetryException(
                            "file.read format=json but content is not valid JSON: " + ex.getMessage(), ex);
                }
            }
            case "text":
            default:
                return decodeAsString(bytes, encoding);
        }
    }

    private static String decodeAsString(byte[] bytes, String encoding) {
        Charset cs;
        try {
            cs = Charset.forName(encoding);
        } catch (IllegalArgumentException ex) {
            // UnsupportedCharsetException extends IllegalArgumentException — superclass로 cover.
            throw new NoRetryException("file.read encoding not supported: " + encoding);
        }
        return new String(bytes, cs);
    }
}
