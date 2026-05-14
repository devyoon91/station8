package com.station8.app.definition;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #179 — {@link LineDefinitionMetadata#normalize} 단위 테스트.
 *
 * <p>태그 정규화는 pure static logic이므로 Spring context 없이 검증. DB 영속성은 기존
 * integration test ({@code TagsApiTest})가 라운드트립으로 커버.</p>
 */
class LineDefinitionMetadataTest {

    @Test
    void normalize_nullInput_returnsEmptySet() {
        assertThat(LineDefinitionMetadata.normalize(null)).isEmpty();
    }

    @Test
    void normalize_emptyList_returnsEmptySet() {
        assertThat(LineDefinitionMetadata.normalize(List.of())).isEmpty();
    }

    @Test
    void normalize_trimsLowersAndDedupsCaseInsensitive() {
        // 대소문자 + 공백 + 중복이 단일 정규화 키로 합쳐져야 함
        Set<String> result = LineDefinitionMetadata.normalize(
                Arrays.asList("  Prod  ", "PROD", "prod", "Dev"));
        assertThat(result).containsExactly("prod", "dev");
    }

    @Test
    void normalize_nullEntriesAndBlanksAreSkipped() {
        Set<String> result = LineDefinitionMetadata.normalize(
                Arrays.asList(null, "", "   ", "keep"));
        assertThat(result).containsExactly("keep");
    }

    @Test
    void normalize_truncatesAt50Chars() {
        String longTag = "x".repeat(60);
        Set<String> result = LineDefinitionMetadata.normalize(List.of(longTag));
        assertThat(result).hasSize(1);
        assertThat(result.iterator().next()).hasSize(LineDefinitionMetadata.TAG_MAX_LENGTH);
    }

    @Test
    void normalize_preservesInsertionOrder() {
        // LinkedHashSet — 첫 등장 순서 보존
        Set<String> result = LineDefinitionMetadata.normalize(
                Arrays.asList("z", "a", "m", "a"));  // 'a' 중복은 첫 위치에서 dedup
        assertThat(result).containsExactly("z", "a", "m");
    }

    @Test
    void normalize_resultIsLinkedHashSet() {
        // 정확한 순서 보장이 LinkedHashSet 구현 디테일에 의존 — 회귀 방지로 명시
        Set<String> result = LineDefinitionMetadata.normalize(List.of("a", "b"));
        assertThat(result).isInstanceOf(LinkedHashSet.class);
    }
}
