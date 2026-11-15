# Runbook — chave da API da CoinGecko: giro, vazamento e falha da fonte

Intent `261113-trading-real-bot`, Unit U2 (`u2-real-market-data`). Fontes: `infrastructure-specification.md` ("Infrastructure Services", "Rollback", "Ordem de implantação"), `security-design.md` (BR1.10, NFR1.1) e `monitoring-design.md` (alertas).

Quem executa: o fundador. A chave é segredo: nunca entra em log, mensagem, métrica, tela, `NEXT_PUBLIC_*`, chat ou ticket.

## Onde a chave vive

- **Nuvem:** segredo `<nome>/coingecko-api-key` no Secrets Manager (`infra/terraform/modules/services/coingecko.tf`). O valor é gravado à mão, nunca pelo Terraform, para não ir para o state. Só o papel de execução do **worker** recebe a política `worker-reads-coingecko-key`; a API e o TxSigner não leem o segredo.
- **Processo:** o worker lê `COINGECKO_API_KEY` do ambiente na subida. Sem a chave, com `MARKET_SOURCES` contendo `coingecko`, o worker não sobe e a mensagem cita só o nome da variável. A API sobe sem a chave.
- **Local e CI:** não há chave. O `docker-compose.yml`, o Compose de teste e o `scripts/verify.sh` usam `MARKET_SOURCES=sim-a,sim-b`. Os testes usam WireMock com respostas sintéticas.

## 1. Giro programado (a cada 90 dias)

1. Gere uma chave nova no painel da CoinGecko, sem revogar a atual.
2. Grave a chave nova no segredo `coingecko-api-key` (nova versão do segredo).
3. Force uma nova implantação do worker. A API não precisa ser reimplantada.
4. Confira na métrica `marketdata_provider_calls_total{source="coingecko",kind="price",outcome="ok"}` que as chamadas continuam saindo e que o estado da fonte (`marketdata_source_state{state="OK"}`) volta a 1.
5. Revogue a chave antiga no painel da CoinGecko.
6. Registre a data do giro. O próximo vence em 90 dias.

## 2. Vazamento ou suspeita de vazamento

1. **Revogue a chave na CoinGecko imediatamente.** A partir daí, as chamadas falham com 401/403: a primeira rodada sem preço deixa a fonte DEGRADED e a segunda, FAILED. Os preços viram `unknown` (`SOURCE_UNAVAILABLE`) e o REAL recusa ordens (U3). Nunca há volta para a fonte simulada.
2. Se o REAL estiver ligado, desligue-o primeiro (`memetrade.real.enabled=false` na API e no worker) ou acione o kill switch REAL. O saque continua disponível.
3. Gere uma chave nova e grave-a no segredo `coingecko-api-key`.
4. Force uma nova implantação do worker e confira as métricas do passo 4 do giro.
5. Procure a chave vazada nos logs do CloudWatch, no histórico do Git e nos tickets. O código nunca registra a chave; se ela aparecer em algum lugar, trate como incidente e limpe a origem.
6. Se a chave entrou no repositório, primeiro revogue e troque (passos 1 e 3), depois limpe o histórico (`team.md`, "Proteção de segredos").
7. Religue o REAL só quando a fonte `coingecko` voltar a OK.

## 3. Alertas da fonte real

| Alerta | O que fazer |
|---|---|
| `MarketSourceFailed` (alta) | Veja `failure_category` em `data_source` e os logs do worker (`Market provider call failed`, só rota e código). 401/403: siga a seção 2. 429: veja a cota e o orçamento. 5xx ou tempo esgotado: o circuito abre por 60 s e tenta de novo; nada a fazer além de acompanhar. |
| `RealPriceStale` (alta) | O REAL já recusa ordens. Confira o worker (alerta `WorkerDown` da U1), a saída por NAT e o estado da fonte. |
| `MarketCallCounterUnavailable` (alta) | O banco não responde para a tabela `marketdata_provider_usage`; o worker não chama o fornecedor (modo conservador). Restabeleça o banco. |
| `MarketMonthlyBudget` (média) | 80% do orçamento mensal. Os detalhes param em 90%, a descoberta em 95% e os preços em 98%. Avalie subir o plano ou reduzir `COINGECKO_DISCOVERY_LIMIT`. |
| `MarketInvalidValues` (média) | Mais de 5% dos valores inválidos numa hora. Veja `marketdata_invalid_values_total{field}` e o log `Invalid market value refused` (só campo e motivo). Pode ser mudança de formato da API. |
| `MarketProviderUnstable` (média) | Mais de 10% de repetições numa hora. Acompanhe o status da CoinGecko. |

## 4. Rollback da fonte real

1. Desligue o REAL (`memetrade.real.enabled=false`) na API e no worker. O saque continua.
2. Volte a imagem e o `MARKET_SOURCES` juntos, na mesma implantação. A imagem anterior só aceita `sim-a` e `sim-b`.
3. Mantenha a tabela `marketdata_provider_usage` (só acréscimo, expand-contract).
4. Religue o REAL só quando a fonte `coingecko` voltar.
