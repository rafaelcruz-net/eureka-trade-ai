# Instruções de teste — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes: `code-generation-plan.md` (Testing Contract: custom, comprehensive), `construction/u4-real-execution/functional-design/functional-spec.md` (S1 a S30) e `construction/u4-real-execution/nfr-requirements/tech-stack-decisions.md`.

## Ferramentas

| Onde | Ferramentas |
|---|---|
| API (`apps/api`) | JUnit 5, AssertJ, Testcontainers (PostgreSQL), WireMock com respostas gravadas da Jupiter, TxSigner em processo com KMS simulado, JaCoCo com piso de 80% e ArchUnit |
| TxSigner (`apps/txsigner`) | JUnit 5, vetores Solana comuns e transações gravadas da Jupiter; piso de 80% |
| Ponta a ponta | Playwright no `verify.sh`, com o validador local carregando as contas gravadas e o `jupiter-stub` |
| Relógio | injetável para 200 ms, 1 s, 2 s, 8 s, 10 s, 15 s, 60 s e 5 min |

Nenhum teste chama a Jupiter real, a devnet ou a mainnet. O SDK Solana entra só no escopo de teste, para gerar vetores.

## Comandos desta Unit

Um Maven por vez, sempre a partir da raiz. Não exporte `MEMETRADE_AUDIT_WALLET_HMAC_KEY`.

| Parte | Comando |
|---|---|
| API, testes da U4 | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test -Dtest='com.memetrade.realexecution.**.*Test,com.memetrade.realexecution.**.*IT,com.memetrade.pnl.real.**.*Test,com.memetrade.bots.**.RealBot*Test,com.memetrade.bots.**.RealBot*IT,com.memetrade.shared.solana.**.*Test,com.memetrade.shared.config.RuntimeSettingsGuard*Test,com.memetrade.architecture.ArchitectureTest'` |
| TxSigner, política de swap | `sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml test -Dtest='*Swap*Test,*SolanaVector*Test'` |
| TxSigner completo, com cobertura | `sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml verify` |
| Alertas | `docker run --rm -v "$PWD/infra/observability:/rules" --entrypoint promtool prom/prometheus:v3.5.0 check rules /rules/real-execution-alerts.yml` |
| Terraform | `terraform fmt -check` e `terraform validate` em `infra/terraform`, pela imagem `hashicorp/terraform:1.9.8` |

## Volume (Comprehensive)

| Componente | Testes | Antes do código |
|---|---|---|
| Vetores e `MessageBuilder` | 15 | sim |
| `SwapPolicy` (um ataque por motivo e transações que passam) | 30 | sim |
| Máquina de estados e `QuoteMath` | 30 | sim |
| Migração (privilégios e gatilhos) | 12 | sim |
| Bot REAL e ciclo | 15 | sim |
| Consumidor e condutor (concorrência e queda) | 25 | sim |
| Sessão de assinatura | 15 | sim |
| Confirmação, ambíguas e fornecedores | 20 | sim |
| Ledger e P&L REAL | 20 | sim |
| Troca de modo e liquidação | 20 | sim |
| Expurgo | 6 | sim |
| Rotas de leitura e canal | 15 | não |
| Guard, configuração e ArchUnit | 12 | não |
| Métricas e paridade | 10 | não |
| Medição informativa | 2 | não |
| Ponta a ponta (`real-swap`) | 1 spec | não |

## Testes obrigatórios por risco (`team.md`) tocados pela U4

- separação REAL/PAPER;
- deduplicação, com uma transação por ordem REAL (repetir com o mesmo `Idempotency-Key`, reprocessar a decisão, repetir depois de ambígua);
- limites e pausa pela barreira;
- política de conteúdo do TxSigner;
- REAL desligado por padrão;
- isolamento entre tenants;
- anonimização sem endereço nas tabelas de ordem.

## Cobertura

80% de linhas na API e no TxSigner, sem nenhuma exclusão nova.
