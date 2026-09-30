package com.company.scheduler.identity.service;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

/** 按登录主体撤销 JDBC 会话；用于密码修改/重置等场景。 */
@Service
public class SessionRevoker {
    private static final Logger log = LoggerFactory.getLogger(SessionRevoker.class);
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public SessionRevoker(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    /**
     * 删除该用户的全部会话。
     * @param keepSessionId 非空时保留该会话（密码修改场景保留当前会话）
     * @return 实际删除数量
     */
    public int revokeAll(UUID userId, String keepSessionId) {
        Map<String, ? extends Session> found =
                sessions.findByIndexNameAndIndexValue(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, userId.toString());
        int removed = 0;
        for (Session session : found.values()) {
            if (keepSessionId != null && keepSessionId.equals(session.getId())) continue;
            sessions.deleteById(session.getId());
            removed++;
        }
        if (removed > 0) log.info("Revoked {} session(s) for user {}", removed, userId);
        return removed;
    }
}
