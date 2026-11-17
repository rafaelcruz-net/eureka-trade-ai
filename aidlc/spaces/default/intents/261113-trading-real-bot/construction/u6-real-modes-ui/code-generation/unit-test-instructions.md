# Instruções de teste — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes: `code-generation-plan.md` (Testing Contract: custom, comprehensive), `construction/u6-real-modes-ui/functional-design/functional-spec.md` (S1 a S44) e `construction/u6-real-modes-ui/nfr-requirements/tech-stack-decisions.md`.

## Ferramentas

| Onde | Ferramentas |
|---|---|
| `apps/web` e `apps/admin` | Jest 30, React Testing Library, relógio falso do Jest, stubs de `<dialog>`, ESLint (teste das regras); piso de 80% de linhas em cada app |
| `apps/api` | JUnit 5, AssertJ, Testcontainers (PostgreSQL), relógio fixo, JaCoCo com piso de 80% |
| Ponta a ponta | Playwright no `verify.sh`, com o validador local e a carteira de teste |

Nenhum teste usa devnet, mainnet, carteira real ou chave real.

## Comandos desta Unit

Rode um Maven por vez, a partir da raiz. Não exporte `MEMETRADE_AUDIT_WALLET_HMAC_KEY`.

| Parte | Comando |
|---|---|
| Web, testes e cobertura | `corepack pnpm@10.18.3 --dir apps/web test:coverage` |
| Web, lint e tipos | `corepack pnpm@10.18.3 --dir apps/web lint` e `corepack pnpm@10.18.3 --dir apps/web typecheck` |
| Admin, testes e cobertura | `corepack pnpm@10.18.3 --dir apps/admin test:coverage` |
| Admin, lint e tipos | `corepack pnpm@10.18.3 --dir apps/admin lint` e `corepack pnpm@10.18.3 --dir apps/admin typecheck` |
| Contrato | `gen:api` e `check:api` nos dois apps |
| API, testes da U6 | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test -Dtest='com.memetrade.botwallet.**.*Release*Test,com.memetrade.botwallet.**.*Release*IT,com.memetrade.botwallet.**.*BotWalletView*Test,com.memetrade.realexecution.**.*SigningSession*Test,com.memetrade.realexecution.**.*SigningSession*IT,com.memetrade.identity.**.GuestCorsPolicy*Test,com.memetrade.architecture.ArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false` |
| Ponta a ponta | `bash scripts/verify.sh`, com a linha de `e2e/real-ui` acrescentada pelo responsável |

## Volume (Comprehensive)

| Componente | Testes | Antes do código |
|---|---|---|
| Adendo do servidor (rota, isolamento, `serverTime`, CORS) | 14 | sim |
| Dinheiro (`money.ts`, `toBaseUnits`), no web e no admin | 30 | sim |
| Carteira, `signTransaction` e base64 | 12 | sim |
| Faixa e diálogo de assinatura (desfechos, contagem, releitura) | 25 | sim |
| Regras de lint (fixtures que falham), dependências, `NEXT_PUBLIC_*` | 12 | não |
| Canal ao vivo no layout raiz e painel adaptado | 15 | não |
| Base (textos, componentes, `useMoneyAction`, `useTracking`, `countdown`) | 30 | não |
| Menu e cabeçalho | 8 | não |
| Ativação R1 | 14 | não |
| Visão geral, teto e cartão do P&L | 18 | não |
| Modo R3 | 12 | não |
| Ordens R5 | 12 | não |
| Depósito e saque R6 | 16 | não |
| Administrador A1 | 30 | não |
| Ponta a ponta | `real-bot-wallet` adaptado e `real-ui` com 6 cenários | não |

## Testes obrigatórios por risco (`team.md`) tocados pela U6

- **Separação REAL/PAPER:** dois cartões de P&L, nunca somados.
- **Deduplicação por `Idempotency-Key`:** chave por corpo e sem envio duplo.
- **`unknown`:** um dado ausente nunca aparece como zero.
- **Pausa:** visível, e as ações recusadas aparecem em texto.
- **Isolamento entre tenants:** a rota nova não mostra a liberação de outra conta.
- **Novo aceite dos termos:** a tela de saque continua aberta; as outras rotas levam ao novo aceite.
- **Saque:** só para a carteira principal, sempre disponível na tela.

## Cobertura

80% de linhas no `apps/web`, no `apps/admin` e na API, sem nenhuma exclusão nova.
