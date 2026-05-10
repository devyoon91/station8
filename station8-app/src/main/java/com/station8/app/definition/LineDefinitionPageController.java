package com.station8.app.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.station8.app.security.LineAclEntry;
import com.station8.app.security.LineAclRepository;
import com.station8.app.security.LineAclService;
import com.station8.app.security.LineUser;
import com.station8.app.security.LineUserRepository;
import com.station8.app.util.PaginationModel;
import com.station8.engine.entity.LineDefinition;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.repository.LineDefinitionRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 라인 정의 조회용 Mustache 뷰 컨트롤러.
 *
 * <ul>
 *   <li>GET /line/definitions       — 활성 정의 목록</li>
 *   <li>GET /line/definitions/{id}  — 노선도(서브웨이 맵) 미리보기 (#87 M1)</li>
 * </ul>
 *
 * 편집·실행은 {@link LineDefinitionController}(REST)에 위임한다.
 */
@Controller
public class LineDefinitionPageController {

    /** 정의 상세 페이지 '최근 실행' 섹션에 노출할 최근 N개 (#133 D1=10). */
    private static final int RECENT_RUNS_LIMIT = 10;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LineDefinitionRepository definitionRepository;
    private final LineDefinitionService definitionService;
    private final ObjectMapper objectMapper;
    private final ActivityRepository activityRepository;
    private final LineAclRepository aclRepository;
    private final LineAclService aclService;
    private final LineUserRepository userRepository;

    public LineDefinitionPageController(LineDefinitionRepository definitionRepository,
                                        LineDefinitionService definitionService,
                                        ObjectMapper objectMapper,
                                        ActivityRepository activityRepository,
                                        LineAclRepository aclRepository,
                                        LineAclService aclService,
                                        LineUserRepository userRepository) {
        this.definitionRepository = definitionRepository;
        this.definitionService = definitionService;
        this.objectMapper = objectMapper;
        this.activityRepository = activityRepository;
        this.aclRepository = aclRepository;
        this.aclService = aclService;
        this.userRepository = userRepository;
    }

    @GetMapping("/line/definitions")
    public String list(@RequestParam(value = "page", required = false) Integer page,
                       @RequestParam(value = "size", required = false) Integer size,
                       @RequestParam(value = "name", required = false) String nameFilter,
                       @RequestParam(value = "tag", required = false) String tagFilter,
                       Model model) {
        int pageSize = PaginationModel.normalizeSize(size);

        boolean hasNameFilter = nameFilter != null && !nameFilter.isBlank();
        boolean hasTagFilter = tagFilter != null && !tagFilter.isBlank();
        String normalizedTag = hasTagFilter ? tagFilter.trim().toLowerCase() : null;

        // #142 — 필터 처리: tag/name 활성 시 in-memory 필터 (active 정의 최대 10000건 load 후 필터)
        // 정의 수가 더 큰 환경에선 SQL 레벨 필터로 마이그레이션 (별도 이슈).
        List<LineDefinition> allActive = definitionRepository.findActiveDefinitionsPage(0, 10000);

        // 태그 필터: 매칭 정의 ID 집합 미리 조회
        java.util.Set<String> tagMatchIds = null;
        if (hasTagFilter) {
            tagMatchIds = new java.util.HashSet<>(definitionRepository.findDefinitionIdsByTag(normalizedTag));
        }

        // in-memory 필터 (이름 LIKE + tag IN)
        java.util.Set<String> finalTagMatchIds = tagMatchIds;
        List<LineDefinition> filtered = allActive.stream()
                .filter(d -> !hasNameFilter
                        || (d.definitionNm() != null
                            && d.definitionNm().toLowerCase().contains(nameFilter.trim().toLowerCase())))
                .filter(d -> !hasTagFilter || finalTagMatchIds.contains(d.id()))
                .toList();

        long totalCount = filtered.size();
        int totalPages = (totalCount <= 0) ? 0 : (int) ((totalCount + pageSize - 1) / pageSize);
        int currPage = PaginationModel.normalizePage(page, totalPages);
        int offset = currPage * pageSize;
        int end = Math.min(offset + pageSize, filtered.size());
        List<LineDefinition> pageDefs = (offset >= filtered.size()) ? List.of() : filtered.subList(offset, end);

        // 페이지 정의들의 태그 일괄 조회 (N+1 방지)
        java.util.Map<String, List<String>> tagsByDef = definitionRepository.findTagsForDefinitions(
                pageDefs.stream().map(LineDefinition::id).toList());

        List<Map<String, Object>> rows = pageDefs.stream().map(d -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", d.id());
            m.put("definitionNm", d.definitionNm());
            m.put("description", d.description());
            m.put("versionNo", d.versionNo());
            m.put("regDt", d.regDt());
            // #142 — 행별 태그 + 색상
            List<String> tags = tagsByDef.getOrDefault(d.id(), List.of());
            m.put("tagViews", tags.stream().map(t -> Map.of(
                    "tag", t,
                    "colorClass", colorClassFor(t)
            )).toList());
            return m;
        }).toList();
        model.addAttribute("definitions", rows);
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("filterName", hasNameFilter ? nameFilter : null);
        model.addAttribute("filterTag", hasTagFilter ? normalizedTag : null);

        // #142 — 태그 클라우드 (전체 통계, 필터 무관)
        List<LineDefinitionRepository.TagCount> cloud = definitionRepository.findAllTagsWithCount();
        model.addAttribute("hasTagCloud", !cloud.isEmpty());
        model.addAttribute("tagCloud", cloud.stream().map(tc -> Map.of(
                "tag", tc.tag(),
                "count", tc.count(),
                "colorClass", colorClassFor(tc.tag())
        )).toList());

        // 페이지네이션 — 필터 보존
        java.util.LinkedHashMap<String, String> preserve = new java.util.LinkedHashMap<>();
        if (hasNameFilter) preserve.put("name", nameFilter);
        if (hasTagFilter) preserve.put("tag", normalizedTag);
        model.addAttribute("pagination",
                PaginationModel.build("/line/definitions", currPage, pageSize, totalCount, preserve));
        model.addAttribute("navLines", true);
        return "definitions";
    }

    /** #142 — tag 문자열 → 안정적 색상 클래스 (auto hash). 6색 팔레트. */
    private static String colorClassFor(String tag) {
        if (tag == null) return "0";
        int h = Math.abs(tag.hashCode()) % 6;
        return String.valueOf(h);
    }

    @GetMapping("/line/definitions/{id}")
    public String preview(@PathVariable("id") String id, Model model) {
        DagDefinitionResponse def = definitionService.getDefinition(id);
        model.addAttribute("definitionId", def.definitionId());
        model.addAttribute("definitionNm", def.definitionNm());
        model.addAttribute("description", def.description());
        model.addAttribute("versionNo", def.versionNo());
        model.addAttribute("nodeCount", def.nodes().size());
        model.addAttribute("edgeCount", def.edges().size());
        model.addAttribute("graphJson", toJson(def));
        model.addAttribute("navLines", true);

        // #133 — 정의 단위 최근 실행 + 상태별 통계
        String wf = def.definitionNm();
        long total = activityRepository.countInstances(wf, null, null);
        long running = activityRepository.countInstances(wf, "RUNNING", null);
        long completed = activityRepository.countInstances(wf, "COMPLETED", null);
        long failed = activityRepository.countInstances(wf, "FAILED", null);
        model.addAttribute("statTotal", total);
        model.addAttribute("statRunning", running);
        model.addAttribute("statCompleted", completed);
        model.addAttribute("statFailed", failed);
        model.addAttribute("hasAnyRun", total > 0);

        List<LineInstance> recent = activityRepository.findInstancesPage(wf, null, null, 0, RECENT_RUNS_LIMIT);
        List<Map<String, Object>> recentViews = new ArrayList<>(recent.size());
        for (LineInstance i : recent) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", i.id());
            m.put("idShort", shortId(i.id()));
            m.put("statusSt", i.statusSt());
            m.put("badgeClass", badgeFor(i.statusSt()));
            m.put("startedAt", formatDt(i.startDt()));
            m.put("endedAt", formatDt(i.endDt()));
            m.put("duration", formatDuration(i.startDt(), i.endDt()));
            recentViews.add(m);
        }
        model.addAttribute("recentRuns", recentViews);

        // dashboard deeplink — 같은 라인 이름 prefilter (D10)
        model.addAttribute("dashboardDeepLink",
                "/line/dashboard?workflowName=" + java.net.URLEncoder.encode(
                        wf, java.nio.charset.StandardCharsets.UTF_8));

        // #140 — ACL 관리 영역 (현재 사용자가 ADMIN일 때만 노출)
        boolean canAdmin = aclService.canAdmin(id);
        model.addAttribute("canAdmin", canAdmin);
        if (canAdmin) {
            List<LineAclEntry> entries = aclRepository.findByDefinition(id);
            // user_id → username 매핑 (entry는 ID만 보유 — UI는 username 필요)
            List<Map<String, Object>> aclViews = new ArrayList<>(entries.size());
            for (LineAclEntry e : entries) {
                Map<String, Object> m = new HashMap<>();
                m.put("entryId", e.id());
                m.put("userId", e.userId());
                m.put("permission", e.permission());
                LineUser user = userRepository.findById(e.userId());
                m.put("username", user == null ? e.userId() : user.username());
                m.put("displayNm", user == null ? null : user.displayNm());
                m.put("isAdmin", "ADMIN".equals(e.permission()));
                aclViews.add(m);
            }
            model.addAttribute("aclEntries", aclViews);
            model.addAttribute("aclEmpty", aclViews.isEmpty());
        }

        return "definition-preview";
    }

    /** #140 — 권한 부여 (ADMIN만). */
    @PostMapping("/line/definitions/{id}/acl/grant")
    @PreAuthorize("@lineAcl.canAdmin(#id)")
    public String grantAcl(@PathVariable("id") String id,
                           @RequestParam("username") String username,
                           @RequestParam("permission") String permission,
                           org.springframework.security.core.Authentication auth,
                           RedirectAttributes flash) {
        LineUser target = userRepository.findByUsername(username);
        if (target == null) {
            flash.addFlashAttribute("aclMsg", "[FAIL] 사용자 '" + username + "'를 찾을 수 없습니다.");
            flash.addFlashAttribute("aclOk", false);
            return "redirect:/line/definitions/" + id;
        }
        if (!java.util.Set.of("READ", "WRITE", "EXECUTE", "SCHEDULE", "ADMIN").contains(permission)) {
            flash.addFlashAttribute("aclMsg", "[FAIL] 알 수 없는 권한 '" + permission + "'.");
            flash.addFlashAttribute("aclOk", false);
            return "redirect:/line/definitions/" + id;
        }
        aclRepository.grant(id, target.id(), permission, auth.getName());
        flash.addFlashAttribute("aclMsg", "[OK] " + username + " ← " + permission + " grant.");
        flash.addFlashAttribute("aclOk", true);
        return "redirect:/line/definitions/" + id;
    }

    /** #140 — 권한 회수 (ADMIN만, 자기 자신의 마지막 ADMIN 강등은 거부). */
    @PostMapping("/line/definitions/{id}/acl/revoke")
    @PreAuthorize("@lineAcl.canAdmin(#id)")
    public String revokeAcl(@PathVariable("id") String id,
                            @RequestParam("userId") String userId,
                            @RequestParam("permission") String permission,
                            org.springframework.security.core.Authentication auth,
                            RedirectAttributes flash) {
        // 자기 마지막 ADMIN 강등 보호
        if ("ADMIN".equals(permission)) {
            LineUser current = userRepository.findByUsername(auth.getName());
            int adminCount = aclRepository.countAdminsForDefinition(id);
            if (current != null && current.id().equals(userId) && adminCount <= 1) {
                flash.addFlashAttribute("aclMsg",
                        "[FAIL] 자기 자신의 마지막 ADMIN 권한은 회수할 수 없습니다 (다른 ADMIN을 먼저 추가하세요).");
                flash.addFlashAttribute("aclOk", false);
                return "redirect:/line/definitions/" + id;
            }
        }
        aclRepository.revoke(id, userId, permission);
        flash.addFlashAttribute("aclMsg", "[OK] " + permission + " revoke.");
        flash.addFlashAttribute("aclOk", true);
        return "redirect:/line/definitions/" + id;
    }

    // ---- helpers (#133) ----

    private static String shortId(String id) {
        if (id == null) return "";
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static String badgeFor(String status) {
        return switch (status == null ? "" : status) {
            case "COMPLETED" -> "success";
            case "RUNNING" -> "warning";
            case "FAILED" -> "danger";
            case "TERMINATED" -> "secondary";
            default -> "mute";
        };
    }

    private static String formatDt(LocalDateTime dt) {
        return dt == null ? null : DT_FMT.format(dt);
    }

    /** RUNNING이면 진행중 표시, 둘 다 있으면 "1m 23s" 형태. */
    private static String formatDuration(LocalDateTime start, LocalDateTime end) {
        if (start == null) return "—";
        LocalDateTime to = end != null ? end : LocalDateTime.now();
        long seconds = Duration.between(start, to).toSeconds();
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    /**
     * SVG 렌더러가 소비할 정의 JSON. Mustache view 안에 인라인 임베드되므로
     * ``<`` ``>`` ``&`` ``'``를 ``</script>`` 깨짐 없이 안전하게 인코딩.
     */
    private String toJson(DagDefinitionResponse def) {
        Map<String, Object> payload = Map.of(
                "definitionId", def.definitionId(),
                "definitionNm", def.definitionNm(),
                "nodes", def.nodes().stream().map(n -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", n.nodeId());
                    m.put("name", n.nodeNm() == null ? n.activityNm() : n.nodeNm());
                    m.put("activity", n.activityNm() == null ? "" : n.activityNm());
                    m.put("x", n.posX() == null ? 0 : n.posX());
                    m.put("y", n.posY() == null ? 0 : n.posY());
                    // M3 클릭 시 inline 패널이 표시할 메타 — null 허용
                    m.put("inputParams", n.inputParams());
                    return m;
                }).toList(),
                "edges", def.edges().stream().map(e -> Map.of(
                        "id", e.edgeId(),
                        "from", e.fromNodeId(),
                        "to", e.toNodeId()
                )).toList()
        );
        try {
            // ``<script type="application/json">``에 인라인 임베드되므로 ``</script>`` 깨짐 방지를 위해
            // ``</``를 JSON-안전한 ``<\/``로 치환한다 (RFC 8259상 ``\/``는 허용된 escape).
            return objectMapper.writeValueAsString(payload).replace("</", "<\\/");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("graphJson 직렬화 실패", ex);
        }
    }
}
