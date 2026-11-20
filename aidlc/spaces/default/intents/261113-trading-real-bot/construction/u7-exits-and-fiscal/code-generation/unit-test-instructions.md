# Instruções de teste — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes: `code-generation-plan.md` (Testing Contract: custom, comprehensive), `construction/u7-exits-and-fiscal/functional-design/functional-spec.md` (S1 a S50) e `construction/u7-exits-and-fiscal/nfr-requirements/tech-stack-decisions.md`.

## Ferramentas

| Onde | Ferramentas |
|---|---|
| API (`apps/api`) | JUnit 5, AssertJ, Testcontainers (PostgreSQL), WireMock com respostas gravadas ou sintéticas da Jupiter, transações sintéticas pelo SDK Solana só no escopo de teste, relógio fixo, JaCoCo com piso de 80% e ArchUnit |
| TxSigner (`apps/txsigner`) | JUnit 5, `trigger-program.json` de teste e o piso de 80% |
| Web (`apps/web`) | Jest e React Testing Library, com o piso de 80% |
| Ponta a ponta | Playwright no `verify.sh`, com o adaptador simulado no perfil de teste local |

Nenhum teste chama a Jupiter real, a devnet ou a mainnet.

## Comandos desta Unit

Um Maven por vez, sempre a partir da raiz. Não exporte `MEMETRADE_AUDIT_WALLET_HMAC_KEY`.

| Parte | Comando |
|---|---|
| API, testes da U7 | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test -Dtest='com.memetrade.realexits.**.*Test,com.memetrade.realexits.**.*IT,com.memetrade.realexecution.**.*Exit*Test,com.memetrade.realexecution.**.*Deferred*Test,com.memetrade.realexecution.**.*Exit*IT,com.memetrade.botwallet.**.Reconciliation*Test,com.memetrade.botwallet.**.*Exits*Test,com.memetrade.privacy.**.*Test,com.memetrade.ledger.**.*Stream*Test,com.memetrade.shared.**.*RateLimiter*Test,com.memetrade.shared.config.**.*Test,com.memetrade.architecture.ArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false` |
| API completa, com cobertura | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` |
| TxSigner, com cobertura | `sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml verify` |
| Web | `corepack pnpm@10.18.3 --dir apps/web test:coverage`, `lint` e `typecheck` |
| Alertas | `docker run --rm -v "$PWD/infra/observability:/rules" --entrypoint promtool prom/prometheus:v3.5.0 check rules /rules/real-exits-alerts.yml` |
| Terraform | `terraform fmt -check` e `terraform validate`, pela imagem `hashicorp/terraform:1.9.8` |
| Ponta a ponta | `bash scripts/verify.sh`, com os specs novos acrescentados pelo responsável |
| Só o `ConditionalCreationIT` (inclui BR3.5, volta 1) | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ConditionalCreationIT -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true` |
| Rastreabilidade | `aidlc engine sensor-traceability --stage code-generation --output-path aidlc/spaces/default/intents/261113-trading-real-bot/construction/u7-exits-and-fiscal/code-generation/traceability.json` |

## Volume (Comprehensive)

| Componente | Testes | Antes do código |
|---|---|---|
| Migração 0022 | 12 | sim |
| `ConditionalStateMachine`, `TriggerValidation`, `needsUserCancellation` | 25 | sim |
| Interfaces de extensão, venda dupla, `DEFERRED`, reconciliação, anonimização, flag | 30 | sim |
| Criação, assinatura e cancelamento | 20 | sim |
| Recusa da Jupiter e da política de assinatura na criação (BR3.5, volta 1 do Build and Test) | 2 | não (teste depois, Step 18) |
| Preenchimento e `FillPosting` | 20 | sim |
| Cancelamento automático | 15 | sim |
| Exportação fiscal (escrita, mapeamento, conferência, isolamento, limite, fluxo) | 30 | sim |
| Carga do programa no TxSigner | 8 | sim |
| Expurgo | 6 | sim |
| Rotas, contrato e canal ao vivo | 15 | não |
| Configuração, guard e ArchUnit | 12 | não |
| Métricas, paridade e logs | 10 | não |
| Telas | 25 | não |
| Ponta a ponta | `real-exports` e `real-exits` | não |

## Testes obrigatórios por risco (`team.md`) tocados pela U7

- separação REAL/PAPER (condicionais e exportação);
- deduplicação por `Idempotency-Key` e uma única venda por posição;
- limites e controles de risco (barreira na criação, limites de preço);
- pausa e kill switch;
- ledger sem edição: o preenchimento só por lançamento novo;
- REAL desligado por padrão e flag de saídas desligado por padrão;
- isolamento entre tenants, inclusive nos trabalhos;
- anonimização recusada com condicional aberta;
- política de conteúdo do TxSigner: recusa total antes da gravação;
- saque sempre disponível.

## Cobertura

80% de linhas na API, no TxSigner e no web, sem nenhuma exclusão nova.

## Volta 1 do Build and Test

O BR3.5 é coberto por `ConditionalCreationIT.br35TheRefusalOfTheProviderIsTriggerRejectedWithItsReasonAndLeavesNothingBehind`, com o `ScriptedTriggerPort` no papel do adaptador simulado devolvendo `Rejected`. O `SimulatedTriggerAdapter` de produção nunca recusa, por isso o teste usa a porta roteirizada da base `AbstractRealExitsIT`. O teste roda com os outros testes da U7 no primeiro comando e na API completa.
