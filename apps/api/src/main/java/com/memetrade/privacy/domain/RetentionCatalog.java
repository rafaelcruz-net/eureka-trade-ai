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
 * The retention catalog of the Privacy module (u2-retention, FR3.4, BR2.9): the 22 rows of the table of the privacy
 * policy v3 (the 13 of v2, the 13th the REAL barrier since 261113-trading-real-bot U3, then the 9 rows of the REAL data
 * since its U6), in the order of the policy, each with who applies its period (GUARD, PURGE or PLANNED). It is the source of
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
                    Enforcer.purge("real-risk-purge")),
            // 261113-trading-real-bot U6, Step 19 (review-03 R-12): the data of the REAL that the policy v3 lists. The
            // bot wallet and the ledger are never purged: they stay while the account exists, the ledger and the
            // deposits are append-only, and the anonymization (RealAnonymization) closes the wallet and clears its
            // addresses, the main wallet and the consent messages. The other rows follow the age triggers of the
            // migrations 0019, 0021 and 0022, one declaration (J7) per table of the REAL purges.
            new RetentionRule(
                    "REAL_BOT_WALLET",
                    14,
                    "Carteira do bot: endereço, carteira principal registrada, teto, modo e consentimentos assinados",
                    "Criar e operar a carteira do bot e provar cada autorização",
                    RetentionPeriod.whileAccountExists(WHILE_ACCOUNT),
                    // review-04: the api clears its copy; the TxSigner keeps wallet_policy, policy_change and
                    // signing_record with no deletion (its migration 0001), as proof of what was signed.
                    "Anonimizar: na API, a carteira do bot é encerrada, e o endereço, a carteira principal e as"
                            + " mensagens de consentimento são apagados; o serviço de assinatura guarda, sem exclusão, a"
                            + " política da carteira do bot e o registro de cada assinatura (endereço da carteira do bot,"
                            + " carteira principal e transações assinadas), como prova do que foi assinado",
                    Enforcer.guard()),
            new RetentionRule(
                    "REAL_LEDGER",
                    15,
                    "Ledger, depósitos e saques do modo REAL",
                    "Mostrar saldo e resultado, conferir com a rede e permitir a exportação",
                    RetentionPeriod.whileAccountExists(WHILE_ACCOUNT),
                    KEEP_UNLINKED + "; o ledger nunca é apagado nem editado",
                    Enforcer.guard()),
            new RetentionRule(
                    "REAL_ORDERS",
                    16,
                    "Ordens, envios e vendas totais do modo REAL",
                    "Executar as ordens e provar o que foi feito",
                    RetentionPeriod.fixed(RetentionTerm.months(60), CREATED, "5 anos"),
                    DELETE,
                    Enforcer.purge("real-execution-purge/orders")),
            new RetentionRule(
                    "REAL_DECISION_REQUESTS",
                    17,
                    "Pedidos de decisão do bot no modo REAL",
                    "Levar a decisão do bot até a ordem",
                    RetentionPeriod.fixed(RetentionTerm.months(13), CONCLUDED, "13 meses após o fechamento"),
                    DELETE,
                    Enforcer.purge("real-execution-purge/decisions")),
            new RetentionRule(
                    "REAL_CONDITIONAL_EXITS",
                    18,
                    "Ordens condicionais de saída do modo REAL",
                    "Executar o stop e o alvo e provar o que foi feito",
                    RetentionPeriod.fixed(RetentionTerm.months(60), ENDED, "5 anos após o fim"),
                    DELETE,
                    Enforcer.purge("real-exits-purge")),
            new RetentionRule(
                    "REAL_RELEASES",
                    19,
                    "Liberações do modo REAL",
                    "Provar quem liberou o REAL para você e quando",
                    RetentionPeriod.fixed(RetentionTerm.months(60), ENDED, "5 anos após a revogação"),
                    DELETE,
                    Enforcer.purge("real-ops-purge/releases")),
            new RetentionRule(
                    "REAL_RECONCILIATIONS",
                    20,
                    "Conferências do ledger do modo REAL com a rede",
                    "Detectar divergências entre o ledger e a rede",
                    RetentionPeriod.fixed(
                            RetentionTerm.months(13),
                            CREATED,
                            "13 meses, ou mais enquanto uma divergência depender dela"),
                    DELETE,
                    Enforcer.purge("real-ops-purge/reconciliations")),
            new RetentionRule(
                    "REAL_DIVERGENCES",
                    21,
                    "Divergências da conferência do modo REAL",
                    "Provar como cada divergência foi resolvida",
                    RetentionPeriod.fixed(RetentionTerm.months(60), CONCLUDED, "5 anos após a resolução"),
                    DELETE,
                    Enforcer.purge("real-ops-purge/divergences")),
            new RetentionRule(
                    "REAL_TXSIGNER_COMMANDS",
                    22,
                    "Comandos do modo REAL ao serviço de assinatura",
                    "Criar, ajustar e encerrar a carteira do bot",
                    RetentionPeriod.fixed(RetentionTerm.months(13), CONCLUDED, "13 meses após a confirmação"),
                    DELETE,
                    Enforcer.purge("real-ops-purge/txsigner-commands")));

    private RetentionCatalog() {}

    /** The 22 rows, in the order of the policy. */
    public static List<RetentionRule> rules() {
        return RULES;
    }

    public static Optional<RetentionRule> find(String id) {
        return RULES.stream().filter(rule -> rule.id().equals(id)).findFirst();
    }
}
