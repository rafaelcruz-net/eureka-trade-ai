# Instruções de teste — U2 `u2-real-market-data`

Conversa: português (Brasil). Fontes: `code-generation-plan.md` (Testing Contract: custom, comprehensive), `construction/u2-real-market-data/functional-design/functional-spec.md` (cenários) e `construction/u2-real-market-data/nfr-requirements/tech-stack-decisions.md`.

## Ferramentas

- **Backend (`apps/api`):** JUnit 5, AssertJ, WireMock com respostas sintéticas da CoinGecko (sem mint real), Testcontainers (PostgreSQL) para a tabela do contador e o coletor, JaCoCo com piso de 80% e ArchUnit.
- **Web (`apps/web`):** Jest.
- **Rede:** nenhum teste chama a CoinGecko real.

## Comandos desta Unit

Só os testes da U2, um Maven por vez. Não exporte `MEMETRADE_AUDIT_WALLET_HMAC_KEY`.

| Parte | Comando |
|---|---|
| Backend, testes da U2 | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test -Dtest='com.memetrade.marketdata.infra.coingecko.**.*Test,com.memetrade.marketdata.**.CoinGecko*IT,com.memetrade.marketdata.**.SourceStateContractTest,com.memetrade.marketdata.**.FieldFreshnessTest,com.memetrade.marketdata.**.MarketSourceGuardTest,com.memetrade.architecture.ArchitectureTest'` |
| Web, tela do scanner | `pnpm --dir apps/web exec jest src/features/scanner` |

## Volume (Comprehensive)

| Componente | Testes | Antes do código |
|---|---|---|
| `ValueParser` | 14 | sim (`unknown` nunca zero) |
| `CoinGeckoHttp` (redirecionamento, compressão, 2 MB, JSON, mint, chave fora do log) | 12 | não |
| `CallBudget` (janela, prioridade, upsert antes da chamada, modo conservador, leitura do uso) | 12 | não |
| `CoinGeckoAdapter` (lotes, descoberta, detalhes, prazos, 429, repetição, circuito, RoundSummary) | 14 | não |
| Coletor e `SourceCaller` (mapa de estados tolerante, fontes simuladas sem mudança) | 10 | não |
| `Freshness` por campo | 5 | não |
| M4, `WatchedTokens` e trava de subida | 10 | não |
| API do scanner (atribuição) | 5 | não |
| Tela (Jest) | 8 | não |

Os testes obrigatórios por risco de `team.md` que a U2 toca:
- dado de mercado ausente vira `unknown`, nunca zero;
- REAL desligado por padrão, com o sistema impedindo a subida com fonte simulada quando ligado.

## Cobertura

80% de linhas no backend e no web, sem nenhuma exclusão nova.

## Dados

- **Respostas sintéticas** da CoinGecko em `src/test/resources/coingecko/`, com mints gerados no teste.
- **Relógio:** injetável para prazos e frescor.
- **Latência do WireMock:** fixa nos testes de prazo.
