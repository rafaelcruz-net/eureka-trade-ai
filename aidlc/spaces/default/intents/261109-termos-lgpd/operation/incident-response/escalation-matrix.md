# Matriz de escalonamento

Conversa: português (Brasil). Resposta 2A. A matriz do intent anterior continua valendo (`261026-memetrade-saas-mvp/operation/incident-response/escalation-matrix.md`): o responsável do projeto é a única pessoa da escala; tópico SNS `memetrade-<env>-alta` com reconhecimento em 15 minutos; `memetrade-<env>-media` no mesmo dia; o revisor independente (C-14) entra em qualquer incidente que envolva funções REAL.

## Alarmes deste intent

| Alarme | Severidade | Prioridade inicial | Quem age | Escala para | Runbook |
|---|---|---|---|---|---|
| `DATA_REQUEST_OVERDUE` | Alta | P2 | responsável do projeto (administrador) | encarregado, se o atraso afetar o titular | RB-13 |
| `ANONYMIZATION_FAILING_REPEATEDLY` | Alta | P2 | responsável do projeto | encarregado, se a Exclusão passar do prazo | RB-14 |
| `AUDIT_FULL_WALLET_REJECTED` | Alta | P3 (nada foi gravado); P1 se a carteira apareceu em outro lugar | responsável do projeto | encarregado, se for incidente com dados pessoais | RB-15 |
| `LEGAL_NOT_PUBLISHABLE`, `LEGAL_VERSION_IGNORED` | Média | P4 | responsável do projeto | — | triagem dos Médios |
| `PURGE_STALE`, `PURGE_FAILED` | Média | P4 | responsável do projeto | — | triagem dos Médios |
| `TERMS_CHECK_SLOW` | Média | P4 | responsável do projeto | — | triagem dos Médios |
| `DATA_REQUEST_DUE_SOON` | Média | P3 | responsável do projeto | — | RB-13 |
| `ANONYMIZATION_FAILED` | Média | P3 | responsável do projeto | — | RB-14 |

## Incidente com dados pessoais

| Papel | Quem | Quando entra |
|---|---|---|
| Comando do incidente | responsável do projeto | sempre |
| Encarregado (DPO) | a pessoa publicada na `/privacidade` (a definir) | todo incidente com dados pessoais; decide e prepara a comunicação à ANPD e aos titulares |
| Assessoria jurídica | a do parecer C-09 | quando o encarregado pedir, antes de comunicar |

Enquanto o encarregado não estiver nomeado nos campos de implantação, o responsável do projeto acumula o papel e registra isso no incidente.
