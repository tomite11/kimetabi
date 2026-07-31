package app.tabikime.kimetabi.trip;

import java.time.Duration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenRateLimitService {

    static final int MAX_ATTEMPTS = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final JdbcClient jdbcClient;
    private final SensitiveTokenCodec tokenCodec;

    public TokenRateLimitService(JdbcClient jdbcClient, SensitiveTokenCodec tokenCodec) {
        this.jdbcClient = jdbcClient;
        this.tokenCodec = tokenCodec;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allow(String clientAddress, String tokenHash) {
        int ipAttempts = increment("IP", tokenCodec.hash(clientAddress));
        int tokenAttempts = increment("TOKEN", tokenHash);
        return ipAttempts <= MAX_ATTEMPTS && tokenAttempts <= MAX_ATTEMPTS;
    }

    private int increment(String subjectType, String subjectHash) {
        return jdbcClient.sql("""
                        INSERT INTO token_rate_limit (
                            subject_type, subject_hash, window_started_at, attempt_count
                        )
                        VALUES (:subjectType, :subjectHash, CURRENT_TIMESTAMP, 1)
                        ON CONFLICT (subject_type, subject_hash)
                        DO UPDATE SET
                            window_started_at = CASE
                                WHEN token_rate_limit.window_started_at
                                     <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'
                                THEN CURRENT_TIMESTAMP
                                ELSE token_rate_limit.window_started_at
                            END,
                            attempt_count = CASE
                                WHEN token_rate_limit.window_started_at
                                     <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'
                                THEN 1
                                ELSE token_rate_limit.attempt_count + 1
                            END
                        RETURNING attempt_count
                        """)
                .param("subjectType", subjectType)
                .param("subjectHash", subjectHash)
                .query(Integer.class)
                .single();
    }
}
