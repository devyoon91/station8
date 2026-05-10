package com.station8.app.controller;

import com.station8.engine.entity.ActivityExecution;

import java.util.HashMap;
import java.util.Map;

/**
 * 인스턴스 상태/활동 → 운영 UI에 노출할 derived 필드 계산 유틸.
 *
 * <p>{@link LineMonitoringController}(timeline 페이지 model 빌드)와
 * {@link LineInstanceController}(state JSON 엔드포인트 — #132)가 공유.</p>
 *
 * <p>모든 메서드는 정적이며 부작용 없음.</p>
 */
final class InstanceStateBuilder {

    private InstanceStateBuilder() {}

    /** 액티비티 상태 → 노선도(subway-map.js) 단순 카테고리. */
    static String mapActivityToSubway(String activityStatus) {
        return switch (activityStatus == null ? "" : activityStatus) {
            case "COMPLETED" -> "completed";
            case "RUNNING" -> "running";
            case "FAILED" -> "failed";
            case "PENDING", "WAITING_DEPENDENCIES" -> "pending";
            default -> "untouched";
        };
    }

    /** 같은 역에 여러 ActivityExecution이 있을 때 더 진행된 상태가 덮어쓰도록. */
    static int subwayStatusRank(String status) {
        return switch (status == null ? "" : status) {
            case "untouched" -> 0;
            case "pending"   -> 1;
            case "running"   -> 2;
            case "failed"    -> 3;
            case "completed" -> 4;
            default          -> 0;
        };
    }

    /** 인스턴스 상태(STATUS_ST) → CSS badge 클래스. */
    static String badgeForInstanceStatus(String status) {
        return switch (status == null ? "" : status) {
            case "COMPLETED" -> "success";
            case "RUNNING" -> "warning";
            case "FAILED" -> "danger";
            default -> "mute";
        };
    }

    /** 액티비티 상태 → CSS dot 클래스. */
    static String dotForActivityStatus(String status) {
        return switch (status == null ? "" : status) {
            case "COMPLETED" -> "completed";
            case "RUNNING" -> "running";
            case "FAILED" -> "failed";
            case "PENDING", "WAITING_DEPENDENCIES" -> "pending";
            default -> "untouched";
        };
    }

    /**
     * 액티비티들의 nodeId를 기반으로 {@code statusByNode} 맵을 만든다.
     * 노드별로 가장 진행된 상태를 채택.
     */
    static Map<String, String> buildStatusByNode(java.util.List<ActivityExecution> activities) {
        Map<String, String> out = new HashMap<>();
        for (ActivityExecution a : activities) {
            if (a.nodeId() == null) continue;
            String mapped = mapActivityToSubway(a.statusSt());
            String prev = out.get(a.nodeId());
            if (prev == null || subwayStatusRank(mapped) > subwayStatusRank(prev)) {
                out.put(a.nodeId(), mapped);
            }
        }
        return out;
    }
}
