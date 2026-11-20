# Instruções de testes de ponta a ponta — intent `261113-trading-real-bot`

Os testes de ponta a ponta são os specs Playwright rodados pelo `scripts/verify.sh`, o comando de verificação autorizado pelo responsável do projeto (`team.md`, Walking Skeleton). Ele sobe o ambiente completo (PostgreSQL, API, worker, web, administrativo, TxSigner, `kms-sim`, validador Solana local com contas clonadas e o stub da Jupiter), espera a saúde de todos e roda os specs num único worker.

## 1. Como rodar

```bash
bash scripts/verify.sh            # suíte completa; código de saída diferente de zero em qualquer falha

# um spec isolado, com o ambiente já de pé
corepack pnpm@10.18.3 --dir apps/web exec playwright test e2e/real-bot-wallet
```

- Na CI: jobs `smoke` (todo push e PR) e `e2e` (push em `main`, depois dos demais), ambos com `IMAGE_TAG` e `BUILD_SHA` iguais ao SHA do commit.
- Nesta máquina o `verify.sh` completo morre por memória; vale o run `ci` do mesmo commit.

## 2. Specs do REAL e ordem

A ordem está fixada na linha 162 do `scripts/verify.sh`: `real-bot-wallet` → `real-swap` → `real-ops` → `real-ui` → `real-v1-exports` → `real-v2-exits` (sempre o último, porque vende tudo no fim).

| Spec | Unit | O que prova |
|---|---|---|
| `e2e/real-bot-wallet/bot-wallet.spec.ts` | U1 (esqueleto) | ativação pelo teclado com consentimento assinado, depósito de 1 SOL visível, saque total de volta à carteira principal, saque para outro endereço recusado |
| `e2e/real-swap/swap.spec.ts` | U4 | sobe para `AUTO_FULL`, compra o mint gravado e a ordem fica `CONFIRMED` com posição |
| `e2e/real-ops/divergence.spec.ts` | U5 | divergência por token cunhado direto na carteira, compensação errada recusada e exata aceita, kill switch REAL |
| `e2e/real-ui/real-ui.spec.ts` | U6 | visão geral, modos, ordens, depósito e saque, administrador, 320 px e foco visível |
| `e2e/real-v1-exports/real-exports.spec.ts` | U7 | CSV fiscal com BOM e cabeçalho, período maior que 1 ano recusado (422), download pela tela |
| `e2e/real-v2-exits/real-exits.spec.ts` | U7 | modo "Automática nas saídas", OCO pela tela, kill switch e volta a `AUTO_FULL` cancelam a condicional |

Os specs anteriores (esqueleto, auditoria, identidade, descoberta, bots, ledger, administração, painel, termos, pedidos e anonimização) continuam na mesma execução e precisam seguir verdes.

## 3. Dados de teste

- A carteira principal de teste é gerada só no código de teste (`e2e/support/test-wallet.ts`); nenhuma carteira, mint ou dado pessoal real.
- O swap usa a gravação de `infra/solana-fixtures/recorded.env` (mint, decimais, valores e slot); sem ela o `real-swap` se pula sozinho, o que não pode acontecer na CI.
- A conta de smoke `BETA_USER` é semeada só no perfil de teste.

## 4. Metas

- 100% dos specs passam, sem nenhum pulado; teste instável é defeito (`team.md`).
- Acessibilidade básica (teclado, foco visível, sem rolagem lateral em 320 px) conferida nos specs `real-bot-wallet` e `real-ui`; sem portão automático de acessibilidade na CI.

## 5. Resultado de referência

Run `ci` 44688329451 (commit `d7e6a17`): 42/42 no job `smoke` (13,4 min) e 42/42 no job `e2e` (14,3 min), 0 pulados. O `real-swap` não foi pulado (a gravação existe desde o commit `605a8fb`).
