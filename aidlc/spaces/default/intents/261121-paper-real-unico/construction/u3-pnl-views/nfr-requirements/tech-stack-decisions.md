# Decisões de pilha — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-23.

A pilha não muda [Q4]. A U3 usa só módulos e bibliotecas que já existem, nas versões fixadas no repositório. Nenhuma dependência nova entra no `pom.xml` da API nem no `package.json` do `apps/web`.

As linhas deste arquivo são **linhas de referência**: registram decisões de pilha e alternativas rejeitadas e levam um ID herdado para mostrar a que NFR se ligam, mas não são alvos de cobertura no `traceability.json`. Cada NFR é coberto pelos requisitos verificáveis dos outros arquivos.

## Decisões

| ID | Camada | Tecnologia e versão | Decisão | Justificativa |
|---|---|---|---|---|
| NFR8.5 | Backend | Java 21 e Spring Boot 3.5.16 (Web MVC, `JdbcClient`, validação, actuator) | Mantém. C5 e C6 ficam no módulo `pnl` (`pnl.web`, `pnl.modeview`); o filtro `market` e as contagens ficam no `bots`. | Restrição "mesma pilha" do intent; os módulos donos já existem. |
| NFR8.6 | Banco | PostgreSQL 17 e Liquibase | Mantém, com 0 migrações (NFR1.15). | C5 e C6 leem pelo cálculo da U2, com o índice da `0023`. |
| NFR8.7 | Métricas | Micrometer com o exportador do Prometheus | Mantém; acrescenta 3 contadores, 13 séries (NFR3.9, NFR3.15, NFR3.16). | Mesma base das métricas da U1 e da U2. |
| NFR8.13 | Auditoria | Módulo `audit` que já existe (`AuditActions`, `RateLimitedAuditPublisher` da U2) | Mantém; reusa `TENANT_MISMATCH` e acrescenta a ação `PNL_COMPARE_TEMPLATE_MISMATCH`, com o limite por hora da U2 (NFR3.13, NFR3.14). | Decisão do fundador D-NFR1; nenhuma tabela nova. |
| NFR7.13 | Carga | `scripts/load/pnl-by-mode.mjs` e `load-test.mjs` (`node:test` e Node 24), workflow manual `load-test` | Mantém; o cenário `pnl-by-mode` da U2 ganha C5, C6 e C4 com `RANGE` (NFR1.6); o texto pronto do `load-test.yml` da U2 é ampliado. | Decisão do fundador D-NFR2; ferramentas do `team.md`. |
| NFR8.8 | Frontend | Next.js 15.5.28, React 19.2.9, TypeScript em modo `strict` | Mantém; os 6 componentes novos e as variantes do `PnlModeBlock` ficam no `apps/web`, os que escrevem dinheiro em `features/pnl`. O `eslint.config.mjs` não muda. | Mesma pilha; o `apps/admin` não muda. |
| NFR7.9 | Contrato | `openapi-typescript` 7.9.1, gerando `src/lib/api/schema.d.ts` a partir de `openapi-guest.yaml` | Mantém; os tipos de C4, C5, C6 e `BotPage` são gerados, nunca escritos à mão. | Regra do `team.md` (Code Style); NFR7.8. |
| NFR7.10 | Testes do backend | JUnit 5, Testcontainers (PostgreSQL), ArchUnit, JaCoCo (piso 80%) | Mantém; a regra ArchUnit do `pnl` ganha a fronteira de C5 e C6 se uma dependência nova aparecer. | Ferramentas do `team.md`. |
| NFR7.11 | Testes do frontend | Jest 30.2.0 com Testing Library (piso 80%), relógio injetável | Mantém. | Ferramentas do `team.md`; NFR1.7 e NFR2.4 pedem relógio e fuso fixos. |
| NFR7.12 | Ponta a ponta | Playwright 1.56.1, no `scripts/verify.sh`, com interceptação de rotas para os dados montados | Mantém; o spec `zz-paper-real-unico` ganha 10 casos (NFR7.6). | Fatia do intent no `team.md`; nenhum teste da CI usa devnet, mainnet ou chave real. |
| NFR8.9 | Infraestrutura | Terraform em `infra/terraform/`, ECS, contas `devnet` e `mainnet` | Sem mudança. | NFR8 (nenhum serviço AWS novo, US$ 350 por conta). |

## Alternativas rejeitadas

| ID | Alternativa | Motivo da rejeição |
|---|---|---|
| NFR8.10 | Rota nova para o P&L de um bot no detalhe, em vez de ler o bloco de C4 do modo do bot. | O custo de ler o bloco inteiro cabe no prazo de 800 ms do bloco e no p95 de 1 s (BR4.6, NFR1.9); uma rota nova seria contrato novo sem ganho medido. |
| NFR8.11 | `EventSource` (SSE) ou WebSocket novo para o painel. | O canal ao vivo que já existe (`DashboardMarkPoller`, fetch em leitura contínua, `project.md`) traz os eventos dos dois modos; BR7.5 não muda o canal. |
| NFR8.12 | Cache de C4 no navegador (por exemplo, SWR ou React Query) para limitar as releituras. | A janela de 30 s por cartão (NFR1.7) e um pedido por bloco por troca (NFR1.8) já limitam a carga sem dependência nova. |

## Sources

- `nfr-requirements-questions.md`: Q4 e o resumo confirmado [Q4].
- `inception/requirements-analysis/requirements.md`: Restrições ("mesma pilha"), NFR7, NFR8.
- `apps/api/pom.xml` (Spring Boot 3.5.16) e `apps/web/package.json` (Next.js 15.5.28, React 19.2.9, Jest 30.2.0, Playwright 1.56.1, `openapi-typescript` 7.9.1).
- `construction/u3-pnl-views/functional-design/frontend-components.md` e `rules.md` (BR4.6, BR7.5, BR11.1).
- `construction/u1-mode-skeleton/nfr-requirements/tech-stack-decisions.md`: forma das linhas de referência.
- `aidlc/spaces/default/memory/team.md`: Code Style, Testing Posture (Ferramentas); `project.md`: Corrections (canal ao vivo por fetch).

## Assumptions & Open Questions

1. [assumption] As versões são as do repositório em 2026-11-23; uma atualização do Dependabot durante a U3 não muda estas decisões.
