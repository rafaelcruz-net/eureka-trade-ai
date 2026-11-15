# Avaliação de qualidade do código — eureka-trader-ai

Avaliação feita só por leitura (nenhum teste ou build executado nesta varredura). Componentes em
[component-inventory.md](component-inventory.md); ferramentas e versões em [technology-stack.md](technology-stack.md).

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
