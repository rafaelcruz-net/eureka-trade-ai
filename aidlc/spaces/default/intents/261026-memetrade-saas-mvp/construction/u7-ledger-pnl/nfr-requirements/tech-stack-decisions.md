# Decisões de tecnologia — U7 Ledger e P&L

A U7 usa a stack prescrita do projeto e as escolhas das Units anteriores. Nenhuma biblioteca nova entra.

| Área | Escolha | Situação | Justificativa |
|---|---|---|---|
| Linguagem e framework | Java 21, Spring Boot 3.5; módulos `ledger` (PaperTradingLedger) e `pnl` (PortfolioPnL) por pacote | Mantida | Monólito modular; fronteiras do `components.md` |
| Aritmética | `BigDecimal` com `MathContext` e escalas da política (6 para USDC, 12 para preço unitário, 4 para percentuais) e `RoundingMode.HALF_EVEN`; quantidades com `RoundingMode.DOWN` | Mantida | `project.md` (sem ponto flutuante); Q5 |
| Banco | PostgreSQL 17 com `numeric(38,6)` para valores, `numeric(38,12)` para preços, `numeric(38,18)` para quantidades | Mantida | Decimal exato; mints com até 18 casas |
| Acesso a dados | `JdbcClient` com SQL parametrizado; `SELECT ... FOR UPDATE` nas travas; `INSERT ... ON CONFLICT DO NOTHING` na ordem | Mantida | Padrão da U1; BR3.1 e BR2.8 |
| Transações | `Propagation.MANDATORY` em `PaperExecution` | Mantida | C15: a transação é do ciclo da U5 |
| Imutabilidade | Privilégios do papel da aplicação e gatilho no banco | Mantida | BR4.1 |
| Migrações | Liquibase, pelo `db-migrate` com o dono do esquema; política v1 semeada | Mantida | Padrão da U2 |
| Idempotência de `fund` e `closePositions` | `IdempotencyGuard` da U1 (módulo de identidade), por interface | Mantida | Regra do `project.md` |
| Tarefas | `@Scheduled` com ShedLock | Mantida | Padrão da U2 e da U4 |
| Dados de mercado | Interface C11 da U4, com o acréscimo de `decimals` | Mantida e ampliada | BR1.10 |
| Testes do backend | JUnit 5, AssertJ, Testcontainers (PostgreSQL), ArchUnit, JaCoCo; testes de propriedade com semente fixa | Mantida | `team.md`; NFR4.4 |
| Frontend | Next.js com TypeScript `strict`, tipos gerados do OpenAPI; valores decimais exibidos como texto, sem conversão para `number` | Mantida | Regra comum dos decimais |
| Testes do frontend | Jest e Testing Library; Playwright para a tela de P&L | Mantida | Padrão das Units anteriores |

## Qualidade, acessibilidade, portabilidade e idioma

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR11.1 | Acessibilidade básica nas telas da U7 | P&L, ledger, operações e reconciliação operáveis por teclado, com foco visível, rótulos visíveis, contraste ≥ 4,5:1 no tema escuro; positivo e negativo com sinal e texto, nunca só por cor; `unknown` e "parcial" em texto | NFR11, `project.md` |
| NFR12.1 | Testes antes do código no núcleo financeiro | Toda a U7 segue TDD: cada passo começa por testes que falham a partir das regras e do catálogo; cobertura de linhas ≥ 80% no backend e no frontend; testes obrigatórios de risco presentes (REAL e PAPER, deduplicação, ledger sem edição, P&L determinístico, `unknown`, isolamento, REAL desligado) | NFR12, `team.md` |
| NFR13.1 | Mesmo resultado no Windows e no Linux | Fixtures e cenários em UTF-8 e LF; a suíte passa no Linux da CI e no Windows local | NFR13 |
| NFR14.1 | Textos em pt-BR | Textos das telas em pt-BR; valores com separador decimal da localidade só na exibição; datas no fuso do navegador | NFR14 |
