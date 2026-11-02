# Pipeline de CI — U4 Descoberta e avaliação

A U4 usa os workflows existentes (`.github/workflows/ci.yml`, `security.yml`, `load-test.yml`) sem criar workflow novo. Ações de terceiros continuam fixadas por SHA, com `permissions` mínimas, e os lockfiles versionados (`project.md`). Integração por squash-merge em `main`, sem merge com CI vermelha (`team.md`).

## Estágios e portões

| Estágio | Job | O que a U4 acrescenta | Portão |
|---|---|---|---|
| Lint, formato e tipos | `backend` (Spotless), `frontend` (ESLint, Prettier, `tsc`) | Código dos módulos `marketdata`, `scanner`, `riskassessment` e da tela S3; regra de lint contra `dangerouslySetInnerHTML` nas telas da U4 | Falha bloqueia o merge |
| Testes do backend | `backend` (`mvnw verify`) | Testes marcados `u4`: unitários (curvas, cobertura, faixas, propriedade com semente fixa), integração com Testcontainers (coleta, histórico, recálculo, limpeza, lista, detalhe, limite, saúde) e ArchUnit (sem `double`/`float`, sem dependência de operações) | Falha ou cobertura < 80% bloqueia |
| Testes do frontend | `frontend` (Jest com 80%) | Componentes da tela S3 e do painel em `src/features/scanner` (já no comando do job); teste do catálogo de termos proibidos | Falha ou cobertura < 80% bloqueia |
| Contrato | `contract` | OpenAPI do convidado regenerado com os acréscimos do C2 (`PENDING`, `computedAt`, `bandRaisedByUnknown`, `sources` no `503`, `RATE_LIMITED`); tipos do frontend regenerados | Diferença entre gerado e versionado bloqueia |
| Smoke | `smoke` (`bash scripts/verify.sh`) | O esqueleto continua passando com `marketSources` no lugar de `marketSource` (mesmo `lastUpdatedAt`). Um smoke Playwright da U4 (`e2e/u4-discovery`) é escrito; ele entra no `verify.sh` por ação do responsável do projeto, porque o script não pode ser editado pelo agente | Falha bloqueia |
| Segurança | `security` (Gitleaks, OWASP Dependency-Check, CodeQL) | Resilience4j (`retry`, `timelimiter`) com versão fixa entra na varredura de dependências | Crítico ou Alto bloqueia; Médio avisa |
| Carga | `load-test.yml` (manual) e `scripts/load-test.sh` local | Cenário da U4 descrito abaixo | Não bloqueia o merge; é evidência do ponto de controle da U4 |

## Mudanças no script de carga (`scripts/load-test.sh` e `scripts/load/load-test.mjs`)

O script atual (U1) roda 20 usuários por 5 minutos e reprova qualquer p95 acima de 300 ms em `challenge`, `verify` e na lista. A U4 o amplia sem afrouxar o limite da U1:

| Item | Hoje | Depois da U4 |
|---|---|---|
| Duração | `DURATION_S=300` | `DURATION_S=600` no cenário da U4; 300 no cenário da U1 |
| Volume | 30 tokens de uma fonte | Cenário U1: padrão diário (200 por fonte); cenário U4: `MARKET_SIMULATED_TOKENS_PER_SOURCE=2000`, exportado antes do `docker compose up`; cenário de crescimento (NFR3.5, antes do G1): o mesmo com `10000`, só local, sem limite de reprovação, com o resultado anotado no relatório |
| Ações | challenge, verify, lista sem filtros | Mais: lista com filtros e ordenações variadas, detalhe da avaliação |
| Limites de p95 (lidos do histograma Prometheus) | 300 ms para todas | Por rota: `challenge` e `verify` 300 ms; lista sem filtros no cenário U1 300 ms (limite da U1 mantido); lista com filtros no cenário U4 1 s (NFR3.1); detalhe 500 ms (NFR3.2) |
| Pool e memória | não lidos | `hikaricp_connections_pending` lido a cada 5 s pela porta de gestão; reprova se ficar acima de 0 por mais de 10 s seguidos; `docker stats` da `api` e do `postgres` (limite de 1 GB cada) reprova acima de 900 MB em qualquer um |
| Plano da consulta (NFR2.6) | — | Ao fim, o script roda `EXPLAIN` da consulta da lista pelo `psql` do contêiner `postgres` e reprova se aparecer `Seq Scan` em `observation_sample` |
| Relatório | `load-test-report.json` | O mesmo JSON com as novas chaves e uma cópia em Markdown em `docs/perf/u4-discovery-<data>.md` |

## Verificações de NFR na CI

| NFR | Onde roda | Como |
|---|---|---|
| NFR9.5 rótulos sem dado pessoal | job `backend` | `MetricsLabelsIT` lê `/actuator/prometheus` depois de exercitar coleta, lista e detalhe e falha se algum rótulo tiver mint, chain, carteira, pessoa ou tenant |
| NFR12.3 determinismo | jobs `backend` e `smoke` | Fontes simuladas com `MARKET_SIMULATED_SEED=42` e fixtures gravadas; relógio injetável nos testes |
| NFR13.1 portabilidade | job `backend` (Linux) e Compose local (Windows) | Mesmas migrações e mesma suíte; fixtures em LF pelo `.gitattributes` |
| NFR4.3 coleta tudo ou nada | job `backend` | `MarketCollectorIT` interrompe a gravação no meio (falha injetada no repositório) e confere que nada da coleta ficou |

## Implantação e rollback

- **Implantação:** só local; `docker compose up` sobe `postgres`, `db-bootstrap`, `db-migrate` (aplica `0006-discovery.sql`) e `api`; a primeira coleta acontece na partida.
- **Migração aditiva (expand-contract):** a `0006` só acrescenta tabelas, colunas, índices e o valor `PENDING`; nenhuma coluna da U1 é removida. A consulta antiga da U1 continua funcionando sobre o esquema novo.
- **Rollback para a U2:** voltar a imagem da `api` para a versão anterior funciona sobre o banco migrado (tabelas novas ficam sem uso); o indicador volta a ser `marketSource`. A U1 regrava `FAILED` nas fontes na partida, o que só afeta a exibição até a primeira coleta.
- **Teste do rollback** (regra da equipe: rollback testado):
  - automático, no job `backend`: `MigrationsIT` aplica todas as migrações até a `0006` e roda as consultas da versão anterior da listagem (U1) contra o esquema novo, sem erro;
  - manual, no ponto de controle da U4: subir a imagem da `api` com a etiqueta do commit anterior à U4 sobre o volume já migrado pela U4 e rodar `bash scripts/verify.sh` (smoke da U1 e da U2) até sair 0; o resultado vai para o resumo do código.
  - pontos que esse passo confere: o `db-migrate` da imagem anterior roda o changelog antigo sobre um banco que já tem a `0006` (o Liquibase ignora conjuntos aplicados que não estão no changelog e não reaplica nada); o valor `PENDING` não chega ao código antigo, porque o registro das fontes da U1 regrava o estado de cada fonte na partida, antes de qualquer leitura.
- **Smoke de aceite:** `bash scripts/verify.sh` verde depois de cada mudança.

## Segredos na CI

A U4 não cria segredo novo. As senhas de banco continuam efêmeras no `scripts/verify.sh`; nenhuma variável da U4 é sensível.

## Ação pendente do responsável do projeto

Acrescentar `e2e/u4-discovery` à linha do Playwright em `scripts/verify.sh` (como foi feito com `e2e/u2-audit-trail`), quando o smoke da U4 existir. O texto da mudança é preparado na geração de código.
