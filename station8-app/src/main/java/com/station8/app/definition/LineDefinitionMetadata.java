package com.station8.app.definition;

import com.station8.engine.repository.LineDefinitionRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * #179 — 라인 정의 태그({@code U_LINE_DEFINITION_TAG}) 정규화 + 영속성을 담당하는 sub-service.
 *
 * <p>태그는 별도 테이블에 저장되며 free-form 입력(공백/대소문자/길이)을 정규화 후 dedup하여
 * 저장한다. 정규화 규칙은 {@link #normalize(List)} 한 곳에 단일 정의 — 호출 측이 직접
 * 정규화하지 않도록 한다.</p>
 *
 * <h3>정규화 규칙 (#142)</h3>
 * <ul>
 *   <li>{@code trim} + {@code toLowerCase}</li>
 *   <li>빈 문자열/{@code null} 제거</li>
 *   <li>50자 초과 시 앞 50자만 보존</li>
 *   <li>입력 순서 유지하면서 dedup ({@link LinkedHashSet})</li>
 * </ul>
 */
@Service
public class LineDefinitionMetadata {

    /** 태그 1건의 최대 저장 길이 (#142). 초과 시 앞부분만 보존. */
    public static final int TAG_MAX_LENGTH = 50;

    private final LineDefinitionRepository definitionRepository;

    /**
     * 컴포넌트 의존성 주입.
     *
     * @param definitionRepository 태그 insert/delete를 담당하는 repository
     */
    public LineDefinitionMetadata(LineDefinitionRepository definitionRepository) {
        this.definitionRepository = definitionRepository;
    }

    /**
     * 정규화한 태그를 정의에 일괄 저장한다. 정의가 이미 다른 태그를 가진 경우 누적되므로,
     * 교체 시맨틱이 필요하면 {@link #replaceTags(String, List)}를 사용하라.
     *
     * @param definitionId 태그를 부여할 정의 ID
     * @param rawTags      free-form 태그 목록 ({@code null} 허용 — no-op)
     */
    public void persistTags(String definitionId, List<String> rawTags) {
        Set<String> normalized = normalize(rawTags);
        for (String tag : normalized) {
            definitionRepository.insertTag(definitionId, tag, "api");
        }
    }

    /**
     * 정의의 기존 태그를 모두 지우고 새 태그로 교체한다. {@code replaceDefinition}의 태그 처리에 사용.
     *
     * @param definitionId 태그를 교체할 정의 ID
     * @param rawTags      새 태그 목록 ({@code null}이면 모든 태그 제거 결과)
     */
    public void replaceTags(String definitionId, List<String> rawTags) {
        definitionRepository.deleteTagsByDefinition(definitionId);
        persistTags(definitionId, rawTags);
    }

    /**
     * 태그 정규화 단일 진입점 — trim + lowercase + dedup + 50자 제한.
     * 로그 메시지에서 운영자에게 정규화 결과를 보여주거나, 테스트가 결과를 비교할 때 호출.
     *
     * @param rawTags free-form 태그 목록 ({@code null}/빈값 허용)
     * @return 정규화 + dedup 결과 ({@link LinkedHashSet}으로 입력 순서 유지)
     */
    public static Set<String> normalize(List<String> rawTags) {
        if (rawTags == null) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : rawTags) {
            if (t == null) {
                continue;
            }
            String trimmed = t.trim().toLowerCase();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > TAG_MAX_LENGTH) {
                trimmed = trimmed.substring(0, TAG_MAX_LENGTH);
            }
            out.add(trimmed);
        }
        return out;
    }
}
