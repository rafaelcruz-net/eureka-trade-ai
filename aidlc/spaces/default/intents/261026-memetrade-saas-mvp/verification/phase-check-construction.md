# Verificação de fronteira: Construction → Operation

Conversa: português (Brasil). Feita no estágio CI Pipeline em 2026-11-08, conforme o Passo 5 do estágio.

## Veredito

**Bloqueado.** A Construction ainda não pode passar para a Operation sem uma decisão da pessoa. Todas as Units foram construídas e testadas, mas há achados sem resolução e a rastreabilidade entre Units não passou.

## O que foi conferido

| Verificação | Resultado | Evidência |
|---|---|---|
| Todas as Units construídas | sim: U1 a U9 com Code Generation aprovada e ponto de controle aprovado (esqueleto revisado e aprovado de novo em 2026-11-08 depois da troca do `postcss`) | registros `construction/<unit>/code-generation/` e provas em `.aidlc-construction-checkpoints/` |
| Todas as Units testadas | sim: backend 909/763 testes, 93,91%; `apps/web` 391/391, 96,03%; `apps/admin` 85/85, 91,34%; `scripts/verify.sh` 10/10 (2026-11-08 23:06–23:12 UTC, com `postcss` 8.5.28) | `construction/build-and-test/test-results.md` |
| `traceability.json` de cada Unit presente | sim, nas 9 Units | `construction/*/code-generation/traceability.json` |
| Tabelas da Code Generation sem achado aberto | **não**: há itens `Deferred` e `GAP`/achados Minor aceitos (U1 R-11 Major aceito; U5 e U6 Minors das revisões 05 e 07; U9 R-04 a R-06) | revisões em `construction/*/code-generation/reviews/` |
| Portão FR/NFR/AC entre Units | **não passou**: 199 de 215 elementos cobertos; descobertos FR14.1–FR14.4 e FR15.1 (sem AC) e 11 grupos de NFR com itens `Deferred` (hospedagem, CI, réplica) | `construction/build-and-test/cross-unit-traceability.md` |
| Portões da CI cobrem os comandos do Build and Test | **em parte**: `backend`, `contract`, `smoke` e `security` já cobrem; `apps/admin` e a suíte inteira do `apps/web` só passam a ser cobertos quando a pessoa aplicar as mudanças 1 a 3 do `ci-config.md` no `ci.yml` | `construction/ci-pipeline/ci-config.md`, `quality-gates.md` |

## Pendências que mantêm o bloqueio

| Pendência | Dono | Encaminhamento |
|---|---|---|
| Aplicar no `ci.yml` as mudanças 1 a 3 do `ci-config.md` (job `admin`, Jest inteiro do `apps/web`, `needs` do `e2e`) | pessoa | antes de abrir o próximo pull request |
| `perf-audit.sh` da U2 (U2-NFR3.1, 3.3 e 4.2) | pessoa | rodar com memória livre no computador, ou na CI quando houver hospedagem |
| Escala da U4 (U4-NFR3.5) | pessoa / infraestrutura | réplica de leitura com a hospedagem; medir antes do G1 |
| FR14.1–FR14.4 e FR15.1 sem AC | Inception (User Stories, Units Generation) | ligar a histórias e Units ou marcar fora do beta |
| NFRs `Deferred` para hospedagem e CI | estágios de infraestrutura e implantação | medir quando o ambiente existir |
| Revisão do Contract Design (o `contract-summary.md` mudou depois do estágio) | pessoa | decidir se refaz o Contract Design |

A pessoa decide, na aprovação do CI Pipeline, se segue para a Operation com essas pendências registradas ou se volta ao estágio dono de alguma delas.
