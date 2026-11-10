## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-10T03:43:55Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u2-retention/nfr-requirements/performance-requirements.md > NFR3.5; tech-stack-decisions.md > Persistência; reliability-requirements.md > NFR5.1 | O NFR3.5 exige que o expurgo de sessões ache as sessões vencidas por índice. Em `0001-identity.sql`, `user_session` só tem o índice `user_session_user_idx (user_id)`, e a tabela de digests só indexa `session_id`. Além disso, o fim da sessão da BR2.6 é uma expressão (`revokedAt`, ou o menor entre `refreshExpiresAt` e `absoluteExpiresAt`), que um índice de coluna simples não atende. A migração descrita em tech-stack-decisions.md e em NFR5.1 só acrescenta a coluna `wallet_hmac` e a tabela de execuções. Nenhum artefato cria o índice que o NFR3.5 e o teste de 100 mil linhas (NFR3.1/NFR3.2) pressupõem, então o requisito não pode ser cumprido como escrito. | Decidir e registrar o suporte de índice do expurgo de sessões, por exemplo índice de expressão ou parcial sobre o fim da sessão, ou duas consultas por coluna. Incluí-lo na lista do que a migração acrescenta (tech-stack-decisions.md e NFR5.1). Ajustar a verificação do NFR3.5 para checar esse plano. | New |
| R-02 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u2-retention/nfr-requirements/reliability-requirements.md > NFR5.1 | O NFR5.1 diz que a migração só acrescenta a coluna e a tabela de execuções, mas NFR3.8 e tech-stack-decisions.md também exigem o índice de `wallet_hmac`. A descrição da migração fica incompleta e inconsistente entre os arquivos. | Citar o índice de `wallet_hmac` na lista de acréscimos do NFR5.1. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u2-retention/nfr-requirements/scalability-requirements.md > NFR3.7 | O `lockAtMostFor` de 30 minutos difere dos 35 minutos do runner de auditoria existente (`AuditJobRunner`), sem justificativa. Não há `lockAtLeastFor`, que o runner existente usa (5 minutos). | Alinhar com o padrão existente ou justificar o valor e definir o `lockAtLeastFor`. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u2-retention/nfr-requirements/observability-requirements.md > NFR8.5; security-requirements.md > NFR6.2, NFR6.3; scalability-requirements.md > NFR3.6 | Quatro verificações são só "revisão", "revisão do runbook", "desenho de alertas" ou "projeção registrada". Não há critério de passa ou falha mensurável. | Escrever o critério de aceite de cada uma, por exemplo um item de checklist com artefato nomeado, ou marcá-las como verificação manual com responsável. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Conferência de herança de IDs (`traceability.json` contra o requirements.md) | NFR1 a NFR9 cobertos. NFR4 é N/A, sem tela. Os alvos NFR1.x a NFR9.1 existem nos arquivos. | OK. Em NFR8, as métricas de pedidos de titular ficam de fora, e cabem a outra Unit. |
| Conferência com o código em `apps/api` | ShedLock, ArchUnit, tenant nulo em `audit_event` e os cenários de carga `u1` (300 ms) e `u3` (500 ms) existem. Falta índice de fim de sessão em `user_session`. | Confirma o achado Major. |

### Summary

Os requisitos herdam corretamente os NFR1 a NFR9 e não contradizem o design funcional. O único problema relevante é o NFR3.5, que pressupõe um índice de sessões que nenhum artefato prevê e que o esquema atual não tem. Com um Major, o veredito é READY, desde que o índice seja decidido antes do Infrastructure Design e do Code Generation.
