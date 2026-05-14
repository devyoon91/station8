package com.station8.app.definition;

import com.station8.app.security.LineAclRepository;
import com.station8.app.security.LineUser;
import com.station8.app.security.LineUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * #179 — 정의 생성 시 creator에게 ADMIN 권한을 자동 부여하는 sub-service. (#140 구현)
 *
 * <p>{@link LineDefinitionService} Facade가 {@code createDefinition} 마지막 단계로 호출한다.
 * 인증 컨텍스트가 없거나(시스템/테스트 호출) DB에 사용자가 없는 경우 안전하게 skip — 정의 생성 자체는
 * ACL 부여 실패로 인해 롤백되지 않는다.</p>
 *
 * <h3>실패 정책</h3>
 * <ul>
 *   <li>인증 컨텍스트 없음/anonymous — DEBUG 로그 후 skip</li>
 *   <li>사용자 DB에 없음 — WARN 로그 후 skip</li>
 *   <li>예기치 못한 예외 — ERROR 로그 후 swallow (정의는 그대로 유지, 권한은 운영자가 수동 grant)</li>
 * </ul>
 */
@Service
public class LineDefinitionAclBootstrap {

    private static final Logger log = LoggerFactory.getLogger(LineDefinitionAclBootstrap.class);

    private final LineAclRepository aclRepository;
    private final LineUserRepository userRepository;

    /**
     * 컴포넌트 의존성 주입.
     *
     * @param aclRepository  정의-사용자 권한 grant repository
     * @param userRepository 사용자 lookup repository (username → user.id)
     */
    public LineDefinitionAclBootstrap(LineAclRepository aclRepository,
                                      LineUserRepository userRepository) {
        this.aclRepository = aclRepository;
        this.userRepository = userRepository;
    }

    /**
     * 현재 인증된 사용자에게 정의에 대한 ADMIN 권한을 자동 부여.
     *
     * <p>실패해도 호출자에게 예외가 전파되지 않는다 — 정의 생성이 ACL 부여 때문에 롤백되는
     * 운영 시나리오를 피하기 위함.</p>
     *
     * @param definitionId 권한을 부여할 정의 ID (방금 생성된 ID 가정)
     */
    public void autoGrantAdminToCreator(String definitionId) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()
                    || "anonymousUser".equals(auth.getPrincipal())) {
                log.debug("정의 생성 — 인증 컨텍스트 없음, ADMIN auto-grant skip: id={}", definitionId);
                return;
            }
            String username = auth.getName();
            LineUser user = userRepository.findByUsername(username);
            if (user == null) {
                log.warn("정의 생성 — 사용자 '{}' DB에 없음, ADMIN auto-grant skip: id={}",
                        username, definitionId);
                return;
            }
            aclRepository.grant(definitionId, user.id(), "ADMIN", username);
            log.info("정의 생성자에게 ADMIN 자동 부여: definitionId={}, user={}", definitionId, username);
        } catch (Exception ex) {
            // 정의 생성은 이미 성공 — ACL 부여 실패가 정의 롤백을 일으키지 않도록 swallow
            log.error("ADMIN auto-grant 실패 (definition은 그대로 저장됨): id={}", definitionId, ex);
        }
    }
}
