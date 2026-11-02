package com.memetrade.riskassessment.infra;

import com.memetrade.riskassessment.api.WeightSetDraft;
import com.memetrade.riskassessment.api.WeightSetStatus;
import com.memetrade.riskassessment.api.WeightSetView;
import com.memetrade.riskassessment.domain.WeightSet;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code risk_weight_set}: versions of the indicator weights, DRAFT, ACTIVE (at most one) and RETIRED. */
@Repository
public class JdbcRiskWeightStore {

    /** Serialises every activation in the database (the "lock of the active version" of security-design.md). */
    private static final String ACTIVATION_LOCK = "select pg_advisory_xact_lock(hashtext('risk-weight-activation'))";

    private final JdbcClient jdbc;

    public JdbcRiskWeightStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The ACTIVE version; there is always one after the initial migration (BR4.3). */
    public WeightSet active() {
        return jdbc.sql("select * from risk_weight_set where status = 'ACTIVE'")
                .query((rs, n) -> weightSet(rs))
                .optional()
                .orElseThrow(() -> new IllegalStateException("No ACTIVE weight version"));
    }

    public Optional<WeightSetView> find(int version) {
        return jdbc.sql("select * from risk_weight_set where version = :v")
                .param("v", version)
                .query((rs, n) -> view(rs))
                .optional();
    }

    public List<WeightSetView> list() {
        return jdbc.sql("select * from risk_weight_set order by version desc")
                .query((rs, n) -> view(rs))
                .list();
    }

    /** Takes the transactional activation lock; concurrent activations then run one after the other. */
    public void lockActivations() {
        jdbc.sql(ACTIVATION_LOCK).query().listOfRows();
    }

    public int insertDraft(WeightSetDraft draft, String createdBy, Instant at) {
        Integer version = jdbc.sql("select coalesce(max(version), 0) + 1 from risk_weight_set")
                .query(Integer.class)
                .single();
        jdbc.sql("""
                        insert into risk_weight_set (version, status, factors, low_max, medium_max, min_coverage,
                          created_at, created_by)
                        values (:v, 'DRAFT', cast(:factors as jsonb), :low, :medium, :coverage, :at, :by)
                        """)
                .param("v", version)
                .param("factors", RiskJson.writeDefinitions(draft.factors()))
                .param("low", draft.lowMax())
                .param("medium", draft.mediumMax())
                .param("coverage", draft.minCoverage())
                .param("at", Timestamp.from(at))
                .param("by", createdBy)
                .update();
        return version;
    }

    public void updateDraft(int version, WeightSetDraft draft) {
        jdbc.sql("""
                        update risk_weight_set set factors = cast(:factors as jsonb), low_max = :low,
                          medium_max = :medium, min_coverage = :coverage
                        where version = :v and status = 'DRAFT'
                        """)
                .param("factors", RiskJson.writeDefinitions(draft.factors()))
                .param("low", draft.lowMax())
                .param("medium", draft.mediumMax())
                .param("coverage", draft.minCoverage())
                .param("v", version)
                .update();
    }

    public void retire(int version, Instant at) {
        jdbc.sql("update risk_weight_set set status = 'RETIRED', retired_at = :at where version = :v")
                .param("at", Timestamp.from(at))
                .param("v", version)
                .update();
    }

    public void activate(int version, Instant at) {
        jdbc.sql("update risk_weight_set set status = 'ACTIVE', activated_at = :at where version = :v")
                .param("at", Timestamp.from(at))
                .param("v", version)
                .update();
    }

    private static WeightSet weightSet(ResultSet rs) throws SQLException {
        return WeightSet.of(rs.getInt("version"), draft(rs));
    }

    private static WeightSetDraft draft(ResultSet rs) throws SQLException {
        return new WeightSetDraft(
                RiskJson.readDefinitions(rs.getString("factors")),
                rs.getInt("low_max"),
                rs.getInt("medium_max"),
                rs.getBigDecimal("min_coverage"));
    }

    private static WeightSetView view(ResultSet rs) throws SQLException {
        return new WeightSetView(
                rs.getInt("version"),
                WeightSetStatus.valueOf(rs.getString("status")),
                draft(rs),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("created_by"),
                Optional.ofNullable(rs.getTimestamp("activated_at")).map(Timestamp::toInstant),
                Optional.ofNullable(rs.getTimestamp("retired_at")).map(Timestamp::toInstant));
    }
}
