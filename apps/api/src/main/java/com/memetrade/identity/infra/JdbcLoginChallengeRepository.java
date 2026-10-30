package com.memetrade.identity.infra;

import com.memetrade.identity.domain.login.ChallengeState;
import com.memetrade.identity.domain.login.LoginChallenge;
import com.memetrade.identity.domain.login.LoginChallengeRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLoginChallengeRepository implements LoginChallengeRepository {

    private final JdbcClient jdbc;

    public JdbcLoginChallengeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(LoginChallenge challenge) {
        jdbc.sql("""
                        insert into login_challenge
                          (nonce, wallet_address, domain, chain, message, issued_at, expires_at, state, consumed_at)
                        values (:nonce, :wallet, :domain, :chain, :message, :issuedAt, :expiresAt, :state, null)
                        """)
                .param("nonce", challenge.nonce())
                .param("wallet", challenge.walletAddress())
                .param("domain", challenge.domain())
                .param("chain", challenge.chain())
                .param("message", challenge.message())
                .param("issuedAt", Timestamp.from(challenge.issuedAt()))
                .param("expiresAt", Timestamp.from(challenge.expiresAt()))
                .param("state", challenge.state().name())
                .update();
    }

    @Override
    public Optional<LoginChallenge> findByNonce(String nonce) {
        return jdbc.sql("select * from login_challenge where nonce = :nonce")
                .param("nonce", nonce)
                .query(JdbcLoginChallengeRepository::map)
                .optional();
    }

    @Override
    public boolean consume(String nonce, Instant now) {
        // Conditional update: only one concurrent verification can move ISSUED to CONSUMED (NFR6.2, BR1.3).
        return jdbc.sql("""
                                update login_challenge set state = 'CONSUMED', consumed_at = :now
                                where nonce = :nonce and state = 'ISSUED' and expires_at >= :now
                                """)
                        .param("now", Timestamp.from(now))
                        .param("nonce", nonce)
                        .update()
                == 1;
    }

    @Override
    public boolean expire(String nonce) {
        return jdbc.sql("update login_challenge set state = 'EXPIRED' where nonce = :nonce and state = 'ISSUED'")
                        .param("nonce", nonce)
                        .update()
                == 1;
    }

    private static LoginChallenge map(ResultSet rs, int row) throws SQLException {
        Timestamp consumed = rs.getTimestamp("consumed_at");
        return new LoginChallenge(
                rs.getString("nonce"),
                rs.getString("wallet_address"),
                rs.getString("domain"),
                rs.getString("chain"),
                rs.getString("message"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                ChallengeState.valueOf(rs.getString("state")),
                consumed == null ? null : consumed.toInstant());
    }
}
