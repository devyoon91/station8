package com.station8.app.definition.list;

import com.station8.app.security.LineAclService;
import com.station8.app.util.Dates;
import com.station8.app.util.PaginationModel;
import com.station8.engine.entity.LineDefinition;
import com.station8.engine.repository.LineDefinitionRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 라인 정의 목록 페이지의 model 조립을 담당하는 빌더.
 *
 * <p>{@code LineDefinitionPageController.list} 112줄 god method를 분리. 컨트롤러는 라우팅 +
 * 파라미터 패킹만 담당, 본 빌더가 가시성 필터 + 이름/태그 필터 + 페이징 + 행별 태그 + 태그 클라우드를
 * 모두 처리.</p>
 *
 * <h3>모델 키</h3>
 * <ul>
 *   <li>{@code definitions} — 페이지에 표시할 정의 행 (Map view, 태그 + 색상 포함)</li>
 *   <li>{@code totalCount/filterName/filterTag} — 헤더 + 빈결과 메시지</li>
 *   <li>{@code tagCloud/hasTagCloud} — 태그 클라우드 (가시성 필터 적용)</li>
 *   <li>{@code pagination} — 페이지 네비</li>
 * </ul>
 *
 * <p>가시성 필터(#159) 처리: ADMIN은 전체 정의의 글로벌 SQL count로 cloud 빠르게 빌드.
 * USER는 가시 정의의 태그만 합산 (count desc + tag asc 정렬).</p>
 */
@Component
public class DefinitionsListModelBuilder {

    /** in-memory 필터의 active 정의 로드 한도 — 정의 수가 더 큰 환경은 SQL 레벨 필터로 마이그레이션. */
    private static final int MAX_ACTIVE_DEFINITIONS = 10000;

    private final LineDefinitionRepository definitionRepository;
    private final LineAclService aclService;

    public DefinitionsListModelBuilder(LineDefinitionRepository definitionRepository,
                                       LineAclService aclService) {
        this.definitionRepository = definitionRepository;
        this.aclService = aclService;
    }

    /**
     * 요청 파라미터를 model에 반영. 컨트롤러는 본 메서드 호출 후 view 이름만 반환.
     *
     * @param req   파라미터 묶음
     * @param model Mustache view에 전달할 model
     */
    public void build(DefinitionsListRequest req, Model model) {
        int pageSize = PaginationModel.normalizeSize(req.size());

        boolean hasNameFilter = req.nameFilter() != null && !req.nameFilter().isBlank();
        boolean hasTagFilter = req.tagFilter() != null && !req.tagFilter().isBlank();
        String normalizedTag = hasTagFilter ? req.tagFilter().trim().toLowerCase() : null;

        // active 정의 로드 — in-memory 필터링 대상
        List<LineDefinition> allActive = definitionRepository.findActiveDefinitionsPage(0, MAX_ACTIVE_DEFINITIONS);

        // ACL READ 가시성 필터 — ADMIN은 null(전체), USER는 명시 grant + legacy 정의만
        Set<String> visibleIds = aclService.visibleDefinitionIds(
                allActive.stream().map(LineDefinition::id).toList());
        boolean hasVisibilityFilter = visibleIds != null;

        // 태그 필터: 매칭 정의 ID 집합 미리 조회 (1회 SQL — 활성/소프트삭제 자동 필터링)
        Set<String> tagMatchIds = null;
        if (hasTagFilter) {
            tagMatchIds = new HashSet<>(definitionRepository.findDefinitionIdsByTag(normalizedTag));
        }

        // in-memory 필터 (visibility + 이름 LIKE + tag IN) — 한 번에 처리해 stream 짧게 유지
        Set<String> finalTagMatchIds = tagMatchIds;
        Set<String> finalVisibleIds = visibleIds;
        List<LineDefinition> filtered = allActive.stream()
                .filter(d -> !hasVisibilityFilter || finalVisibleIds.contains(d.id()))
                .filter(d -> !hasNameFilter
                        || (d.definitionNm() != null
                            && d.definitionNm().toLowerCase().contains(req.nameFilter().trim().toLowerCase())))
                .filter(d -> !hasTagFilter || finalTagMatchIds.contains(d.id()))
                .toList();

        long totalCount = filtered.size();
        int totalPages = (totalCount <= 0) ? 0 : (int) ((totalCount + pageSize - 1) / pageSize);
        int currPage = PaginationModel.normalizePage(req.page(), totalPages);
        int offset = currPage * pageSize;
        int end = Math.min(offset + pageSize, filtered.size());
        List<LineDefinition> pageDefs = (offset >= filtered.size()) ? List.of() : filtered.subList(offset, end);

        // 페이지 정의들의 태그 일괄 조회 (N+1 방지)
        Map<String, List<String>> tagsByDef = definitionRepository.findTagsForDefinitions(
                pageDefs.stream().map(LineDefinition::id).toList());

        model.addAttribute("definitions", toDefinitionViews(pageDefs, tagsByDef));
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("filterName", hasNameFilter ? req.nameFilter() : null);
        model.addAttribute("filterTag", hasTagFilter ? normalizedTag : null);

        // 태그 클라우드 (필터/페이징 무관, 단 가시성 필터는 적용)
        List<Map<String, Object>> tagCloudView = buildTagCloud(hasVisibilityFilter, visibleIds);
        model.addAttribute("hasTagCloud", !tagCloudView.isEmpty());
        model.addAttribute("tagCloud", tagCloudView);

        // 페이지네이션 — 필터 보존
        Map<String, String> preserve = new LinkedHashMap<>();
        if (hasNameFilter) {
            preserve.put("name", req.nameFilter());
        }
        if (hasTagFilter) {
            preserve.put("tag", normalizedTag);
        }
        model.addAttribute("pagination",
                PaginationModel.build("/line/definitions", currPage, pageSize, totalCount, preserve));
        model.addAttribute("navLines", true);
    }

    /**
     * 정의 엔티티 + 태그맵 → Mustache view용 Map. 태그마다 stable 색상 클래스 부여.
     */
    private List<Map<String, Object>> toDefinitionViews(List<LineDefinition> pageDefs,
                                                        Map<String, List<String>> tagsByDef) {
        return pageDefs.stream().map(d -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", d.id());
            m.put("definitionNm", d.definitionNm());
            m.put("description", d.description());
            m.put("versionNo", d.versionNo());
            m.put("regDt", Dates.format(d.regDt()));
            // 행별 태그 + stable 색상 (태그 문자열 hash → 6색 팔레트)
            List<String> tags = tagsByDef.getOrDefault(d.id(), List.of());
            m.put("tagViews", tags.stream().map(t -> Map.of(
                    "tag", t,
                    "colorClass", colorClassFor(t)
            )).toList());
            return m;
        }).toList();
    }

    /**
     * 태그 클라우드 빌드.
     *
     * <ul>
     *   <li>ADMIN(가시성 필터 미적용) — 글로벌 SQL count 1회로 빠르게.</li>
     *   <li>USER w/ 0 visible — 빈 cloud.</li>
     *   <li>USER w/ visible defs — 가시 정의의 태그만 in-memory 합산 (count desc + tag asc 정렬).</li>
     * </ul>
     */
    private List<Map<String, Object>> buildTagCloud(boolean hasVisibilityFilter, Set<String> visibleIds) {
        if (!hasVisibilityFilter) {
            // 글로벌 cloud — 빠른 single SQL
            List<LineDefinitionRepository.TagCount> cloud = definitionRepository.findAllTagsWithCount();
            return cloud.stream().<Map<String, Object>>map(tc -> Map.of(
                    "tag", tc.tag(),
                    "count", tc.count(),
                    "colorClass", colorClassFor(tc.tag())
            )).toList();
        }
        if (visibleIds.isEmpty()) {
            return List.of();
        }
        // 가시 정의의 태그만 합산
        Map<String, List<String>> tagsByVisible = definitionRepository.findTagsForDefinitions(visibleIds);
        Map<String, Long> counts = new HashMap<>();
        for (List<String> tags : tagsByVisible.values()) {
            for (String t : tags) {
                counts.merge(t, 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> {
                    // 카운트 내림차순, 동률은 태그 알파벳 오름차순 (안정적 정렬)
                    int byCount = Long.compare(b.getValue(), a.getValue());
                    return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
                })
                .<Map<String, Object>>map(e -> Map.of(
                        "tag", e.getKey(),
                        "count", e.getValue(),
                        "colorClass", colorClassFor(e.getKey())
                ))
                .toList();
    }

    /** 태그 문자열 → 안정적 색상 클래스 (auto hash). 6색 팔레트. */
    private static String colorClassFor(String tag) {
        if (tag == null) {
            return "0";
        }
        int h = Math.abs(tag.hashCode()) % 6;
        return String.valueOf(h);
    }
}
