# Avaliação de qualidade do código — eureka-trader-ai

Avaliação feita só por leitura (nenhum teste ou build executado nesta varredura). Componentes em
[component-inventory.md](component-inventory.md); ferramentas e versões em [technology-stack.md](technology-stack.md).

As seções "Resumo" a "Riscos para o Trading REAL" são de 2026-11-13. A avaliação da varredura focada de 2026-11-21
(módulos `bots`, `pnl`, `ledger`, `realexecution`, `realexits` e features `bots`, `real` e `dashboard` do app web)
está em [Área de P&L e REAL](#área-de-pl-e-real-2026-11-21) e em
[Riscos para o intent 261121-paper-real-unico](#riscos-para-o-intent-261121-paper-real-unico).

## Resumo

| Critério | Nota | Evidência |
|---|---|---|
| Clareza de nomes | boa | Nomes em inglês idiomático, ligados ao vocabulário do domínio (`OrderGate`, `PaperExecution`, `FillCalculator`, `KillSwitchApplier`); textos de interface em pt-BR |
| Tamanho das funções | boa | Domínio em funções curtas e puras; maiores arquivos de produção abaixo de 600 linhas (`JdbcLedgerRepository` 595) |
| Tratamento de erros | boa | Recusa de domínio como resultado selado; falhas técnicas relançadas com causa; nenhum `catch` vazio encontrado nas classes lidas; RFC 7807 com código e `correlationId` |
| Presença de testes | boa | 339 classes de teste no backend, 60 arquivos Jest e 15 specs Playwright; cenários por regra de negócio e por risco |
| Duplicação | razoável | Política de simulação copiada em `SlippageEstimate.V1`; padrões de job e expurgo repetidos entre `identity`, `audit`, `feedback`, `riskcontrol` e `privacy` (intencional, cada um com seu pool) |
| Código morto | boa | Nenhum `TODO`/`FIXME`; um `@SuppressWarnings("checkstyle:ParameterNumber")` sem Checkstyle configurado |

## Testes

| Parte | Quantidade | Tipo | Observações |
|---|---|---|---|
| `apps/api` | 169 `*Test` | Unitários (Surefire) | Domínio puro, cenários de catálogo, propriedades (`LedgerPropertyTest`, `RiskGatePropertyTest`), configuração por processo, contratos |
| `apps/api` | 170 `*IT` | Integração (Failsafe, Testcontainers) | PostgreSQL real com as migrações; privilégios e gatilhos (`LedgerPrivilegesIT`, `RiskPrivilegesIT`, `DatabaseRolesIT`); idempotência e atomicidade (`PaperExecutionIdempotencyIT`, `PaperExecutionAtomicityIT`); barreira que falha fechada (`RiskGateFailClosedIT`); ciclo (`CycleIT`, `CycleFailureIT`, `LockOrderIT`); compatibilidade de migração |
| `apps/api` | 9 classes | Arquitetura (ArchUnit) | Fronteiras, pureza, relógio, ponto flutuante, rede, chaves e SDKs |
| `apps/api` | — | Transversais | `tenancy/RouteRegistryTest` (teste negativo obrigatório por rota), `infra/ComposeFilesTest`, `WorkflowsTest`, `SecurityExceptionsTest`, `ops/LogScanIT` |
| `apps/web` | 43 | Jest + Testing Library | Telas, estado do painel, cliente de API, renovação, reaceite, termos proibidos |
| `apps/admin` | 17 | Jest + Testing Library | Telas administrativas, kill switch, pedidos e anonimização |
| `apps/web/e2e` | 15 | Playwright | Um smoke por Unit (sem U6) e pelos fluxos de termos e privacidade |
| `scripts/load` | 6 | `node:test` | Validação dos cenários de carga |

Cobertura: JaCoCo com regra `check` de 80% de linhas sobre unitários e integração mesclados (exclusões declaradas no
`pom.xml`); Jest com `coverageThreshold.global.lines = 80` nos dois apps. Os testes obrigatórios por risco do
`team.md` (isolamento de tenant, REAL e PAPER separados, idempotência, limites, `unknown`, pausa, ledger sem edição,
P&L determinístico, REAL desligado por padrão, login sem reutilização, anonimização, novo aceite) têm testes nomeados
correspondentes nos pacotes lidos.

## Lint, formatação e CI

- **Backend**: Spotless com palantir-java-format no `verify`; enforcer de versões e dependências banidas; sem
  Checkstyle.
- **Frontends**: ESLint, Prettier (`format:check`), `tsc --noEmit`, `check:api` (tipos iguais ao OpenAPI); o app web
  tem também `check:bundle` (`guest-bundle-test.mjs`).
- **CI** (`ci.yml`, em PR, push para `main` e manual): `backend` (`mvnw verify`), `frontend` (lint, formatação, tipos,
  Jest com cobertura), `admin` (lint, formatação, tipos, Jest com cobertura, `check:api`), `contract` (testes de
  contrato e `check:api` do web), `smoke` (`scripts/verify.sh`), `e2e` (suíte Playwright completa, só em push para
  `main`, depois dos demais). A lacuna da CI do `apps/admin` citada no `team.md` está fechada no código atual.
- **Segurança** (`security.yml`): Gitleaks, CodeQL, OWASP Dependency-Check com exceções versionadas, `pnpm audit
  --audit-level high` nos dois apps.
- **Integridade**: ações fixadas por SHA, `permissions: contents: read`, nenhum segredo nos workflows.

## Documentação

- `README.md` em pt-BR com pré-requisitos, primeira configuração, execução local, testes por Unit, trilha de
  auditoria, fontes simuladas, `verify.sh` e segurança.
- 5 ADRs (`docs/adr/`), anexos de contrato por Unit (`docs/api/`, 11 arquivos), retenção e auditoria
  (`docs/privacy/`).
- Javadoc de abertura em quase todas as classes, citando a regra de negócio (BRx.y), o NFR ou o contrato; comentários
  explicam o porquê em configurações e migrações.

## Dívida técnica

| Item | Onde | Impacto |
|---|---|---|
| 13 exceções de segurança vencendo em 2027-02-09 | `security/exceptions.yml` | Migração para Spring Boot 4 / Spring 7.0.9+ precisa acontecer antes; exceção vencida volta a bloquear |
| Quatro sobreposições de versão por CVE | `apps/api/pom.xml` | Remover quando o BOM alcançar |
| Política de simulação duplicada | `riskcontrol/domain/SlippageEstimate.V1` | Uma nova versão da política exige mudar código e banco juntos (`SlippageConformanceIT` protege) |
| Dois limites de frescor do preço | `memetrade.riskcontrol.freshness-max-age` (60 s) e `FillCalculator.MAX_PRICE_AGE` (5 min) | A barreira é mais exigente que o preenchimento; precisa ser uma regra única antes do REAL |
| `RiskWeightAdministration` sem rota nem tela | `riskassessment/api` | Pesos só mudam por migração ou código |
| `decision` sem pacote `api` | `bots` → `decision.domain`/`decision.infra` | Reuso por outro módulo exigiria publicar uma interface |
| 56 `@SuppressWarnings("unchecked")` | `apps/api` | Baixo; concentrados em leituras genéricas |
| Fontes de mercado fixas | `MarketDataProperties.KNOWN_SOURCES` | Um adaptador real exige mudar código e a regra ArchUnit de rede |

## Riscos para o Trading REAL

Fatos do código que o intent `261113-trading-real-bot` precisa tratar explicitamente (nenhum deles é um defeito: são
proteções do beta PAPER).

1. **Bloqueio em camadas**: `CHECK (mode = 'PAPER')` em 13 tabelas, `ModeGuardFilter`, `PaperEnums.MODE`, REAL
   `UNAVAILABLE` na conta e nos termos v1/v2 (detalhe em [architecture.md](architecture.md#estado-atual-do-modo-real)).
   Abrir o REAL é uma migração expand-contract nova, um filtro novo, uma versão nova dos termos com reaceite e testes
   que hoje garantem o contrário (por exemplo, `AccountScreen.test.tsx` exige que não haja controle de REAL).
2. **Chave do bot em produção**: as regras ArchUnit e o enforcer proíbem `KeyPairGenerator`, `PrivateKey`,
   `PKCS8EncodedKeySpec`, `*Mnemonic*` e os SDKs Solana em produção, alinhadas às regras Forbidden do `project.md`. Uma
   carteira do bot gerada pelo sistema conflita com essas regras; a decisão (por exemplo, geração e assinatura só dentro
   do KMS, sem material de chave no processo) precisa ser tomada e registrada antes do desenho.
3. **Transação longa**: o ciclo inteiro é uma transação com `lock_timeout` 2 s e `statement_timeout` 5 s, e
   `OrderGate`/`PaperExecution` exigem essa transação. Chamadas à Jupiter e ao RPC não podem acontecer dentro dela; o
   REAL precisa de um fluxo assíncrono com estados persistidos e tratamento de transação ambígua (PRD §8).
4. **Auditoria de assinaturas**: `AuditEventValidator` recusa assinaturas de transação e trata endereços on-chain de
   32 bytes como carteira; `Base58AddressValueMasker` mascara nos logs qualquer palavra Base58 de 32 a 44 caracteres
   (mints e contas incluídos).
5. **Dados de risco virtuais**: saldo, exposição, perda do dia e drawdown vêm do ledger PAPER; slippage pela política
   simulada; motivo `INSUFFICIENT_PAPER_BALANCE`.
6. **Kill switch limitado aos bots**: não alcança transações enviadas nem ordens condicionais mantidas pela Jupiter.
7. **Anonimização**: anula o endereço da carteira e para os bots, sem regra para fundos reais ou saque pendente.
8. **Rede de saída**: o processo e a regra ArchUnit não preveem chamadas HTTP; o worker não tem limite de egress nem
   segredos além do banco e da chave HMAC da trilha.
9. **Cadeia fixa**: `memetrade.auth.chain: solana:mainnet` na configuração; devnet antes da mainnet exige parâmetro
   por ambiente.
10. **Governança**: `project.md` Mandated exige G2 e G3 cumpridos antes do REAL automático, e `team.md` diz que a
    autoaprovação do responsável não satisfaz G2/G3; o pedido do intent ("sem G3", "fundador solo é o único revisor")
    precisa de decisão registrada que concilie as regras.

## Área de P&L e REAL (2026-11-21)

| Critério | Nota | Evidência |
|---|---|---|
| Clareza de nomes | boa | Vocabulário do domínio REAL explícito (`RealOrderStateMachine`, `PositionExitGuard`, `FillPosting`, `RealPnlCalculator`); Javadoc de abertura cita BR, NFR e Unit de origem |
| Tamanho das funções | boa | Cálculos puros curtos (`RealPnlCalculator.of`, `RealPositions.of`, `RealLedgerEntries.of`); maiores arquivos: `JdbcRealExecutionStore` (762 linhas), `BotCommandService` (521), `ConditionalService` (514), `OrderDriver` (488) |
| Tratamento de erros | boa | Recusas como resultado (`Outcome`, `GateDecision`, `ExitRelease`), `RealGateUnavailable` com indicação de transação utilizável, aritmética com `*Exact`; preço ausente nunca vira zero |
| Presença de testes | boa, com uma lacuna | `bots` 12 Test e 20 IT, `pnl` 3 e 6, `realexecution` 12 e 18, `ledger` 7 e 13, `realexits` 14 e 9; `RealPnlCalculatorTest` cobre o cálculo; nenhum teste encontrado confere o P&L REAL contra o ledger REAL nem com uma venda condicional |
| Duplicação | razoável | Custo médio REAL em duas versões (`RealPositions` e `RealPnlCalculator`) com regras diferentes; conversão de preço do SOL embrulhado repetida em `RealCycle` e `RealPnlService` (constante `WRAPPED_SOL` nas duas) |
| Código morto | boa | Nenhum `TODO`/`FIXME` nos cinco módulos; 2 `@SuppressWarnings` |

Frontend: `features/real` tem 17 arquivos de teste para 50 arquivos, `features/dashboard` 7 para 28 e `features/bots`
3 para 16; `real-lint.test.ts` e `lint-rules.test.ts` fixam proibições como teste (aritmética de número e
`parseFloat` fora de `money.ts`, `EventSource`, SDK Solana, `NEXT_PUBLIC_*`, envio de transação pelo código REAL), e
`DashboardRealPnl.test.tsx` garante que o cartão REAL e o PAPER mostram só os próprios valores.

### Dívida técnica da área

| Item | Onde | Impacto |
|---|---|---|
| P&L REAL sem as vendas condicionais | `pnl/application/RealPnlService` lê só `real_order`; `realexits/application/FillPosting` lança só no ledger | O P&L REAL mostra como aberta uma posição já vendida pela condicional e não realiza o resultado; o Javadoc de `FillPosting` afirma o contrário |
| Dois custos médios REAL | `ledger/domain/RealPositions` e `pnl/real/RealPnlCalculator` | Custo da posição e P&L divergem (taxa e aluguel fora ou dentro; arredondamento para baixo ou para cima) |
| `mode` e `market` no bot | `bot.mode` fixo `PAPER` (0010) e `bot.market` (0019); `BotSummary.mode` sempre `"PAPER"` | Dois campos com nomes parecidos; um consumidor que leia `mode` classifica todo bot como PAPER |
| Criação de bot REAL sem interface | `features/bots/BotCreate.tsx`, textos "Bots PAPER" | Hoje só a API (e o spec `e2e/real-swap`) cria bot REAL |
| `bot_holding` aberto na decisão REAL | `bots/worker/CycleRunner` (`REAL_RECORDED` de BUY) | A posse do token pelo bot começa antes da confirmação na rede; não foi encontrado onde ela fecha para um bot REAL |
| Importação de `riskcontrol.infra` | `RealExecutionPurgeJob`, `RealExitsPurgeJob` | Fonte de retenção compartilhada fora de um pacote `api` |

## Riscos para o intent 261121-paper-real-unico

Fatos do código que o intent precisa tratar explicitamente:

1. **Nada por bot no REAL além de `real_order.bot_id`**: `real_ledger_entry`, `RealPosition`, `real_conditional_order`
   e `real_liquidation` são por carteira do bot (pessoa). O P&L REAL por bot só pode partir das ordens `BOT` e ratear o
   resto.
2. **Vendas sem bot**: a liquidação gera `real_order` `USER_LIQUIDATION` sem `bot_id` (entra no P&L REAL de hoje); a
   condicional não gera `real_order` (não entra). O rateio pedido precisa das duas, então o P&L REAL precisa ler também
   o ledger REAL (referências `conditional:<id>`) ou passar a registrar a venda condicional de outro jeito.
3. **Venda de um bot leva a quantidade de outro**: `RealCycle.order` (SELL) vende a posição inteira do mint na carteira;
   até o ciclo mudar, uma venda de bot pode vender mais do que o bot comprou (o excedente previsto na suposição A8 do
   escopo).
4. **Ordem dos fatos**: `settledOrders` ordena por `updated_at, real_order_id`; o ledger por criação; o custo médio por
   bot e o rateio dependem de uma ordem única e determinística.
5. **Unidades e totais**: PAPER em USDC (`BigDecimal`, 6 casas) e REAL em lamports (`long`); o total por modo não pode
   ser somado nem convertido implicitamente; o painel já mostra cartões separados e o teste
   `DashboardRealPnl.test.tsx` protege isso.
6. **Período**: o P&L PAPER tem período (até 366 dias, dias UTC); o REAL não tem. Um filtro por período comum exige
   período no REAL.
7. **Testes antes do código**: P&L e execução REAL (estados e idempotência) estão na ordem de testes antes do código do
   `team.md`; a regra de custo médio por bot e o rateio precisam de cenários determinísticos com resultado conhecido em
   lamports.
8. **Contrato gerado**: mudanças em `RealPnl` ou `BotSummary` mudam o `openapi-guest.yaml` e os tipos gerados do app
   web (`check:api` na CI).
