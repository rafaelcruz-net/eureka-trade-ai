package com.memetrade.privacy.domain;

import static com.memetrade.shared.retention.RetentionAnchor.CONCLUDED;
import static com.memetrade.shared.retention.RetentionAnchor.CREATED;
import static com.memetrade.shared.retention.RetentionAnchor.DELIVERED;
import static com.memetrade.shared.retention.RetentionAnchor.ENDED;
import static com.memetrade.shared.retention.RetentionAnchor.EXPIRED;

import com.memetrade.shared.retention.RetentionTerm;
import java.util.List;
import java.util.Optional;

/**
 * The retention catalog of the Privacy module (u2-retention, FR3.4, BR2.9): the 13 rows of the table of the privacy
 * policy v2 (the 13th, the REAL barrier, since 261113-trading-real-bot U3), in the order of the policy, each with who applies its period (GUARD, PURGE or PLANNED). It is the source of
 * the table the J5 port serves, and the conformance check holds it against the declarations of the purges (J7), the
 * pending list and the {@code retention.tsv} of every non-legacy version of the legal catalog. Immutable at run time.
 */
public final class RetentionCatalog {

    private static final String DELETE = "Apagar";
    private static final String WHILE_ACCOUNT = "Enquanto a conta existir";
    private static final String KEEP_UNLINKED = "Manter sem vínculo com você depois da anonimização";

    private static final List<RetentionRule> RULES = List.of(
            new RetentionRule(
                    "ACCOUNT",
                    1,
                    "Conta, endereço público da carteira e provedor",
                    "Identificar sua conta",
                    RetentionPeriod.whileAccountExists(WHILE_ACCOUNT),
                    "Anonimizar: o endereço e o provedor são apagados",
                    Enforcer.guard()),
            new RetentionRule(
                    "PAPER_HISTORY",
                    2,
                    "Bots e histórico de operações PAPER",
                    "Executar a simulação e mostrar resultados",
                    RetentionPeriod.whileAccountExists(WHILE_ACCOUNT),
                    KEEP_UNLINKED,
                    Enforcer.guard()),
            new RetentionRule(
                    "TERMS_ACCEPTANCES",
                    3,
                    "Aceites dos termos",
                    "Provar o aceite de cada versão",
                    RetentionPeriod.whileAccountExists(WHILE_ACCOUNT),
                    KEEP_UNLINKED,
                    Enforcer.guard()),
            new RetentionRule(
                    "AUDIT_TRAIL",
                    4,
                    "Trilha de auditoria",
                    "Segurança e exercício de direitos",
                    RetentionPeriod.fixed(RetentionTerm.months(12), CREATED, "12 meses"),
                    DELETE,
                    Enforcer.purge("auditRetention")),
            new RetentionRule(
                    "AUDIT_OUTBOX",
                    5,
                    "Saída da trilha de auditoria",
                    "Entregar os eventos da trilha",
                    RetentionPeriod.fixed(RetentionTerm.days(7), DELIVERED, "7 dias após a entrega"),
                    DELETE,
                    Enforcer.purge("auditOutboxCleanup")),
            new RetentionRule(
                    "FEEDBACK",
                    6,
                    "Comentários de feedback",
                    "Melhorar o produto",
                    RetentionPeriod.fixed(RetentionTerm.days(180), CREATED, "180 dias"),
                    DELETE,
                    Enforcer.purge("feedbackPurge")),
            new RetentionRule(
                    "HOLD_NO_TRADE_DECISIONS",
                    7,
                    "Decisões HOLD e NO_TRADE dos bots",
                    "Explicar as decisões recentes",
                    RetentionPeriod.fixed(RetentionTerm.days(30), CREATED, "30 dias"),
                    DELETE,
                    Enforcer.purge("decisionPurge")),
            new RetentionRule(
                    "APPROVED_RISK_EVALUATIONS",
                    8,
                    "Avaliações aprovadas da barreira de risco",
                    "Conferir as ordens simuladas",
                    RetentionPeriod.fixed(RetentionTerm.days(30), CREATED, "30 dias"),
                    DELETE,
                    Enforcer.purge("riskcontrol-purge")),
            new RetentionRule(
                    "IDEMPOTENCY_KEYS",
                    9,
                    "Chaves de idempotência",
                    "Evitar operações repetidas",
                    RetentionPeriod.fixed(RetentionTerm.hours(24), CREATED, "24 horas"),
                    DELETE,
                    Enforcer.purge("idempotencyCleanup")),
            new RetentionRule(
                    "LOGIN_CHALLENGES",
                    10,
                    "Desafios de login",
                    "Verificar a assinatura de entrada",
                    RetentionPeriod.fixed(RetentionTerm.hours(24), EXPIRED, "24 horas após expirar"),
                    DELETE,
                    Enforcer.purge("loginChallengePurge")),
            new RetentionRule(
                    "SESSIONS",
                    11,
                    "Sessões e renovações",
                    "Manter você conectado",
                    RetentionPeriod.fixed(RetentionTerm.days(30), ENDED, "30 dias após o fim ou a revogação"),
                    DELETE,
                    Enforcer.purge("sessionPurge")),
            new RetentionRule(
                    "DATA_SUBJECT_REQUESTS",
                    12,
                    "Pedidos de titular",
                    "Atender e comprovar o atendimento",
                    RetentionPeriod.fixed(RetentionTerm.months(12), CONCLUDED, "12 meses após concluídos"),
                    DELETE,
                    // u4-data-requests (BR4.15): the purge of the closed requests now exists and declares itself.
                    Enforcer.purge("dataSubjectRequestPurge")),
            // 261113-trading-real-bot U3, loop-back 1 of Build and Test (U3-NFR3.7): the evaluations, the closed
            // reservations and the daily photos of the REAL barrier, deleted 13 months after they are written by the
            // daily purge of the REAL barrier. They hold only internal ids, mints and amounts.
            new RetentionRule(
                    "REAL_RISK_EVALUATIONS",
                    13,
                    "Avaliações, reservas e totais do dia da barreira de risco do modo REAL",
                    "Provar o controle de risco aplicado às ordens com dinheiro real",
                    RetentionPeriod.fixed(RetentionTerm.months(13), CREATED, "13 meses"),
                    DELETE,
                    Enforcer.purge("real-risk-purge")));

    private RetentionCatalog() {}

    /** The 13 rows, in the order of the policy. */
    public static List<RetentionRule> rules() {
        return RULES;
    }

    public static Optional<RetentionRule> find(String id) {
        return RULES.stream().filter(rule -> rule.id().equals(id)).findFirst();
    }
}
