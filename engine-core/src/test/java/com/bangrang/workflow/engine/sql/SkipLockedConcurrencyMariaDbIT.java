package com.bangrang.workflow.engine.sql;

import com.bangrang.workflow.engine.dialect.MariaDbDialect;
import com.bangrang.workflow.engine.entity.ActivityExecution;
import com.bangrang.workflow.engine.repository.JdbcActivityRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKIP LOCKED 동작 검증: 다중 워커 동시 폴링 시 동일 행을 두 번 잡지 않음을 보증.
 *
 * <p>H2는 SKIP LOCKED를 부분적으로만 지원하므로 (FOR UPDATE는 받지만 SKIP LOCKED는 무시되는 경우 있음)
 * 이 시나리오는 진짜 MariaDB에서만 의미가 있다.</p>
 */
@Testcontainers
@EnabledIfSystemProperty(named = "runDockerTests", matches = "true",
        disabledReason = "Docker 데몬 필요. -DrunDockerTests=true로 활성화.")
class SkipLockedConcurrencyMariaDbIT {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11")
            .withDatabaseName("swe_concurrency")
            .withUsername("test")
            .withPassword("test");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcActivityRepository repo;
    private static TransactionTemplate tx;

    @BeforeAll
    static void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.mariadb.jdbc.Driver");
        ds.setUrl(mariadb.getJdbcUrl());
        ds.setUsername(mariadb.getUsername());
        ds.setPassword(mariadb.getPassword());
        dataSource = ds;

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("sql/schema-mariadb.sql"));
        populator.setSeparator(";");
        populator.execute(dataSource);

        jdbc = new JdbcTemplate(dataSource);
        repo = new JdbcActivityRepository(jdbc, new MariaDbDialect());
        // SKIP LOCKED는 SELECT FOR UPDATE의 락이 같은 트랜잭션 안에서 유지되어야 의미가 있으므로
        // TransactionTemplate으로 명시 래핑한다. (Spring AOP 프록시 없이도 동일 효과)
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM H_WF_ACTIVITY_EXECUTION");
        jdbc.update("DELETE FROM U_WF_INSTANCE WHERE ID LIKE 'concurrency-%'");
    }

    @Test
    void multipleWorkersDoNotClaimSameRow() throws Exception {
        // 1) 인스턴스 + PENDING 액티비티 30건 시드
        String instId = "concurrency-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO U_WF_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, USE_FL, VIEW_FL, DEL_FL, REG_DT)
                VALUES (?, 'TestFlow', 'RUNNING', 'Y', 'Y', 'N', CURRENT_TIMESTAMP)
                """, instId);

        int rowCount = 30;
        for (int i = 0; i < rowCount; i++) {
            jdbc.update("""
                    INSERT INTO H_WF_ACTIVITY_EXECUTION (
                        ID, INSTANCE_ID, ACTIVITY_NAME, STATUS_ST, RETRY_CNT,
                        USE_FL, VIEW_FL, DEL_FL, REG_DT
                    ) VALUES (?, ?, 'noop', 'PENDING', 0, 'Y', 'Y', 'N', CURRENT_TIMESTAMP)
                    """, UUID.randomUUID().toString(), instId);
        }

        // 2) 워커 4개가 동시에 LIMIT=10으로 폴링
        int workerCount = 4;
        int limitPerPoll = 10;
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<ActivityExecution>>> futures = new java.util.ArrayList<>();

        for (int w = 0; w < workerCount; w++) {
            futures.add(pool.submit(() -> {
                start.await();
                return tx.execute(status -> repo.findPendingActivitiesWithLock(limitPerPoll));
            }));
        }

        start.countDown();

        // 3) 결과 수집
        AtomicInteger totalClaimed = new AtomicInteger();
        java.util.Set<String> claimedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (Future<List<ActivityExecution>> f : futures) {
            List<ActivityExecution> claimed = f.get(15, TimeUnit.SECONDS);
            totalClaimed.addAndGet(claimed.size());
            for (ActivityExecution a : claimed) {
                boolean fresh = claimedIds.add(a.id());
                assertThat(fresh)
                        .as("동일 ID(%s)가 두 워커에 의해 잡혀선 안 됨", a.id())
                        .isTrue();
            }
        }

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // 워커 4개 × LIMIT 10 = 40건 시도, 시드 30건이므로 30건이 클레임되어야 정상.
        // (잠금 경쟁 + skip → 일부 워커가 빈손으로 돌아갈 수 있어도 총합 ≤ 30)
        assertThat(totalClaimed.get())
                .as("총 클레임 수는 시드 행 수와 같아야 함")
                .isEqualTo(rowCount);

        // DB에 RUNNING으로 업데이트되었는지 확인
        Integer running = jdbc.queryForObject(
                "SELECT COUNT(*) FROM H_WF_ACTIVITY_EXECUTION WHERE STATUS_ST = 'RUNNING'",
                Integer.class);
        assertThat(running).isEqualTo(rowCount);
    }
}
