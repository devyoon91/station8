package com.station8.app.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 어드민 — 플러그인 jar 웹 업로드 + 디렉토리 목록 (#102).
 *
 * <p>운영자가 호스트 파일시스템 접근 없이 새 플러그인을 ``engine.plugins.dir``로 떨어뜨릴 수 있게 한다.
 * 활성화(reload/재시작)는 본 이슈 비범위 — 별개 액션 필요.</p>
 *
 * <p>현재 인증 없음 — [#121](https://github.com/devyoon91/station8/issues/121)에서 Spring Security를
 * 도입하면 ``/admin/**``이 자동으로 ADMIN 역할 검사로 보호된다.</p>
 */
@Controller
@RequestMapping("/admin/plugins")
public class AdminPluginController {

    private static final Logger log = LoggerFactory.getLogger(AdminPluginController.class);

    /** zip 매직 바이트 — jar는 zip 형식. */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    /** D3 결정 — 50MB 상한. application.properties의 multipart 설정과 매칭. */
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final String pluginsDir;

    public AdminPluginController(@Value("${engine.plugins.dir:plugins}") String pluginsDir) {
        this.pluginsDir = pluginsDir;
    }

    @GetMapping
    public String list(Model model) {
        File dir = new File(pluginsDir);
        boolean exists = dir.exists() && dir.isDirectory();
        List<Map<String, Object>> entries = new ArrayList<>();
        if (exists) {
            File[] files = dir.listFiles();
            if (files != null) {
                List<File> sorted = new ArrayList<>(List.of(files));
                sorted.sort(Comparator.comparing(File::getName));
                for (File f : sorted) {
                    if (!f.isFile()) continue;
                    Map<String, Object> row = new HashMap<>();
                    row.put("name", f.getName());
                    row.put("size", humanizeSize(f.length()));
                    row.put("modified", DATE_FMT.format(LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(f.lastModified()), ZoneId.systemDefault())));
                    row.put("isJar", f.getName().toLowerCase().endsWith(".jar"));
                    row.put("isBackup", f.getName().toLowerCase().endsWith(".bak"));
                    entries.add(row);
                }
            }
        }
        model.addAttribute("pluginsDir", dir.getAbsolutePath());
        model.addAttribute("dirExists", exists);
        model.addAttribute("entries", entries);
        model.addAttribute("entryCount", entries.size());
        model.addAttribute("maxSizeMb", MAX_FILE_BYTES / 1024 / 1024);
        model.addAttribute("navAdminPlugins", true);
        return "admin-plugins";
    }

    @PostMapping
    public String upload(@RequestParam("file") MultipartFile file, RedirectAttributes flash) {
        try {
            validate(file);
            File targetDir = new File(pluginsDir);
            if (!targetDir.exists()) {
                if (!targetDir.mkdirs()) {
                    throw new IOException("plugins 디렉토리 생성 실패: " + targetDir.getAbsolutePath());
                }
            }

            String filename = sanitizeFilename(file.getOriginalFilename());
            Path target = Paths.get(pluginsDir, filename);

            // D4 결정 — 같은 이름이 있으면 .bak로 백업 후 overwrite (단건 .bak만 보존)
            boolean backedUp = false;
            if (Files.exists(target)) {
                Path backup = Paths.get(pluginsDir, filename + ".bak");
                Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
                backedUp = true;
                log.info("Existing plugin backed up: {} -> {}", filename, backup.getFileName());
            }

            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Plugin uploaded: {} ({} bytes){}",
                    filename, file.getSize(), backedUp ? " — replaced existing (.bak created)" : "");

            String msg = "[OK] '" + filename + "' 업로드 완료 (" + humanizeSize(file.getSize()) + ")"
                    + (backedUp ? " — 기존 파일은 " + filename + ".bak으로 백업" : "")
                    + ". 활성화는 앱 재시작 또는 별개 reload 액션 필요 (#103).";
            flash.addFlashAttribute("uploadMsg", msg);
            flash.addFlashAttribute("uploadOk", true);
        } catch (UploadValidationException ex) {
            flash.addFlashAttribute("uploadMsg", "[FAIL] " + ex.getMessage());
            flash.addFlashAttribute("uploadOk", false);
        } catch (Exception ex) {
            log.warn("Plugin upload failed: {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
            flash.addFlashAttribute("uploadMsg", "[FAIL] 업로드 실패: " + ex.getMessage());
            flash.addFlashAttribute("uploadOk", false);
        }
        return "redirect:/admin/plugins";
    }

    // ---- helpers ----

    private void validate(MultipartFile file) throws UploadValidationException, IOException {
        if (file == null || file.isEmpty()) {
            throw new UploadValidationException("파일이 비어있습니다");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".jar")) {
            throw new UploadValidationException("확장자가 .jar이 아닙니다: " + name);
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new UploadValidationException("파일 크기 상한 초과 (" + humanizeSize(file.getSize())
                    + " > " + (MAX_FILE_BYTES / 1024 / 1024) + "MB)");
        }
        // 매직 바이트 — zip(jar)는 PK\x03\x04로 시작
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(4);
            if (head.length < 4 || head[0] != ZIP_MAGIC[0] || head[1] != ZIP_MAGIC[1]
                    || head[2] != ZIP_MAGIC[2] || head[3] != ZIP_MAGIC[3]) {
                throw new UploadValidationException(
                        "유효한 jar(zip) 매직 바이트(PK\\x03\\x04)가 아닙니다");
            }
        }
    }

    /** 디렉토리 traversal 방어 — 경로 구분자 / 상위 참조 제거. */
    private static String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            throw new UploadValidationException("파일 이름이 비어있습니다");
        }
        String n = original.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        if (n.contains("..") || n.isBlank()) {
            throw new UploadValidationException("유효하지 않은 파일 이름: " + original);
        }
        return n;
    }

    private static String humanizeSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        return String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    /** 사용자 입력 검증 실패 — 일반 IOException과 분리. */
    static class UploadValidationException extends RuntimeException {
        UploadValidationException(String message) { super(message); }
    }
}
