# Decisões de pilha — U1 `u1-mode-skeleton`

A pilha não muda [Q6]. A U1 usa só módulos e bibliotecas que já existem, nas versões fixadas no repositório. Nenhuma dependência nova entra no `pom.xml` da API nem no `package.json` do `apps/web`.

As linhas deste arquivo são **linhas de referência**: registram decisões de pilha e alternativas rejeitadas e levam um ID herdado para mostrar a que NFR se ligam, mas não são alvos de cobertura no `traceability.json`. Cada NFR é coberto pelos requisitos verificáveis dos outros arquivos (NFR2 por NFR2.1 a NFR2.3, NFR7 por NFR7.1 a NFR7.8, NFR8 por NFR8.1 a NFR8.5).

## Decisões

| ID | Camada | Tecnologia e versão | Decisão | Justificativa |
|---|---|---|---|---|
| NFR8.6 | Linguagem e framework do backend | Java 21 e Spring Boot 3.5.16 (Web MVC, JDBC com `JdbcClient`, validação, actuator) | Mantém. As rotas C1 a C4 ficam nos módulos `bots`, `pnl` e `riskcontrol.real`; as interfaces C8, C9 e C10 são interfaces Java públicas desses módulos. | Restrição "mesma pilha" do intent; o monólito modular já tem os módulos donos. Sem ORM e sem Spring Security, como hoje. |
| NFR8.7 | Banco e migrações | PostgreSQL 17 e Liquibase (gerenciado pelo Boot, aplicado só pelo processo `db-migrate`) | Mantém, com 0 migrações na U1. A restrição `bot_market_immutable` da migração `0019-real-execution.sql` já cobre o modo imutável. | A U1 não cria tabela nem coluna (NFR1.13); o índice com slot do ledger REAL é da U2 (C12). |
| NFR8.8 | Métricas | Micrometer com o exportador do Prometheus (gerenciado pelo Boot), em `/actuator/prometheus` | Mantém; acrescenta 2 contadores (NFR3.10, NFR4.6) com a convenção de nomes do código. | Já é a base das métricas e dos alarmes; nenhum serviço AWS novo (NFR8.1). |
| NFR8.9 | Frontend | Next.js 15.5.28 com React 19.2.9 e TypeScript em modo `strict` | Mantém; os 5 componentes novos (`ModeStep`, `RealConfirmDialog`, `RealBotLimitsStep`, `SimulatedBadge`, `PnlModeBlock` na variante `full`) ficam no `apps/web`. | Mesma pilha; o `apps/admin` não muda. |
| NFR7.9 | Contrato entre frontend e backend | `openapi-typescript` 7.9.1, gerando `src/lib/api/schema.d.ts` a partir de `openapi-guest.yaml` | Mantém. Os tipos de C1 a C4 são gerados, nunca escritos à mão; `check:api` e os testes de contrato falham a CI numa quebra. | Regra do `team.md` (Code Style); BR10.5 pede o leitor do web e o contrato no mesmo commit (NFR7.8). |
| NFR2.4 | Tipos numéricos | `long` para lamports com operações exatas, `BigDecimal` para US$; `bigint` só em `features/real/money.ts` no `apps/web` | Mantém. | Proibição de ponto flutuante do `project.md`; regra ArchUnit de ponto flutuante (NFR2.1). |
| NFR7.10 | Testes do backend | JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL), ArchUnit, JaCoCo (piso 80%) | Mantém; a regra ArchUnit do módulo que ganhar fronteira nova (dependência de `bots` em `riskcontrol.api.RealAccountLimits` e de `pnl` em `pnl.api.BotDirectory`) é atualizada. | Ferramentas do `team.md`; o p95 de NFR1.5 usa o mesmo Testcontainers. |
| NFR7.11 | Testes do frontend | Jest 30.2.0 com Testing Library (piso 80%) | Mantém. | Ferramentas do `team.md`. |
| NFR7.12 | Ponta a ponta | Playwright 1.56.1, no `scripts/verify.sh`, com o validador Solana local e o KMS simulado | Mantém; 1 spec novo da fatia do intent (NFR7.7). | Fatia do intent no `team.md` (Walking Skeleton); nenhum teste da CI usa devnet, mainnet ou chave real. |
| NFR8.10 | Infraestrutura | Terraform em `infra/terraform/`, ECS, contas `devnet` e `mainnet` | Sem mudança na U1. | NFR8 (nenhum serviço AWS novo, orçamento de US$ 350 por conta). |

## Alternativas rejeitadas

| ID | Alternativa | Motivo da rejeição |
|---|---|---|
| NFR8.11 | Cache (por exemplo, Caffeine ou Redis) para o P&L por modo já na U1. | Sem medição que o justifique; a U1 lê com os índices atuais e o p95 é medido no NFR1.5. Um cache seria serviço ou estado novo (NFR1.11, NFR8.1) e plano B só da U2 (ADR-004). |
| NFR8.12 | Biblioteca de tracing distribuído (OpenTelemetry) para correlacionar as falhas por modo. | A U1 não chama outro processo; o `correlationId` já correlaciona resposta e log (NFR3.12, NFR3.13). |

## Sources

- `nfr-requirements-questions.md`: Q6 e resumo confirmado [Q6].
- `inception/requirements-analysis/requirements.md`: Restrições ("mesma pilha"), NFR2, NFR7, NFR8.
- `aidlc/spaces/default/codekb/eureka-trader-ai/technology-stack.md`: versões de Java, Spring Boot, PostgreSQL, Liquibase, Micrometer, Next.js e das ferramentas de teste.
- `apps/api/pom.xml` (Spring Boot 3.5.16, Java 21) e `apps/web/package.json` (Next.js 15.5.28, React 19.2.9, Jest 30.2.0, Playwright 1.56.1, `openapi-typescript` 7.9.1).
- `construction/u1-mode-skeleton/functional-design/frontend-components.md` e `rules.md` (BR10.5).
- `inception/contract-design/contract-summary.md`: C8, C9, C10, C12.
- `inception/domain-design/decisions.md`: ADR-004 (cache como plano B).
- `aidlc/spaces/default/memory/team.md`: Code Style, Testing Posture (Ferramentas).

## Assumptions & Open Questions

1. [assumption] As versões listadas são as do repositório em 2026-11-21; uma atualização do Dependabot durante a U1 não muda as decisões desta tabela.
