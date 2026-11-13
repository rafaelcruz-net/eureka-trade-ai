# Resultados do smoke

Conversa: português (Brasil). Release `v2026.11.12-1` (`9b2695c`).

## Smoke da release

O smoke da release é o `bash scripts/verify.sh`, rodado pela CI no commit da tag (pergunta 3 de `deployment-execution-questions.md`: o responsável decidiu não rodar de novo localmente).

| Execução | Job | Specs | Varredura de logs | Resultado |
|---|---|---|---|---|
| `ci` `43214445497` | `smoke` | 19 de 19 em 3,1 min | ok (carteira, feedback e convites fora dos logs) | "walking skeleton verified" |
| `ci` `43214445497` | `e2e` | 19 de 19 em 2,9 min | ok | "walking skeleton verified" |

Os 19 specs cobrem as Units do intent anterior (U1 a U9) e as deste intent: `e2e/legal-documents` (U1), `e2e/reacceptance` (U3), `e2e/data-requests` (U4) e `e2e/anonymization` (U5).

## Outros portões no commit da tag

| Portão | Resultado |
|---|---|
| `backend` (unidade, integração, ArchUnit, Spotless, JaCoCo ≥ 80%) | verde |
| `frontend` e `admin` (lint, formatação, tipos, Jest ≥ 80%) | verde |
| `contract` | verde |
| `security`: segredos, CodeQL, dependências (NVD, CVSS ≥ 7 com exceções EX-001 a EX-013), `pnpm audit` | verde |

## Smokes locais deste intent

O mesmo `verify.sh` passou localmente no ponto de controle da U3 (2026-11-11, 19 de 19), antes da troca do log4j e da correção dos testes instáveis.
