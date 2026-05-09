package com.station8.engine.datasource;

/**
 * 어드민 UI / 진단용 DataSource 메타데이터 + 풀 통계 스냅샷.
 *
 * <p>비밀번호는 절대 포함하지 않는다. Hikari 풀 통계는 {@code HikariPoolMXBean}에서 가져옴.</p>
 *
 * @param name        등록된 이름 (e.g., ``primary``, ``source-oracle``)
 * @param url         JDBC URL
 * @param username    접속 계정 (비밀번호는 노출하지 않음)
 * @param dialect     SQL 방언 (e.g., ``mariadb``, ``oracle``)
 * @param healthy     부팅 시점 health-check 결과 (현재 풀 상태가 아님 — UI 측에서 별도 ping 가능)
 * @param activeConn  active 커넥션 수 (-1 = 미지원/조회 실패)
 * @param idleConn    idle 커넥션 수 (-1 = 미지원/조회 실패)
 * @param totalConn   total 커넥션 수 (-1 = 미지원/조회 실패)
 * @param errorMsg    health-check 또는 풀 통계 조회 실패 메시지 (정상 시 null)
 */
public record DataSourceInfo(
        String name,
        String url,
        String username,
        String dialect,
        boolean healthy,
        int activeConn,
        int idleConn,
        int totalConn,
        String errorMsg
) {
}
