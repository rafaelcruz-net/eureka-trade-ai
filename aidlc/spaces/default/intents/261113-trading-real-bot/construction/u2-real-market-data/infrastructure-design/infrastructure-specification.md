# Especificação de infraestrutura — U2 `u2-real-market-data`

Conversa: português (Brasil).

Fontes:
- `construction/u2-real-market-data/nfr-design/` (segurança, confiabilidade, observabilidade, componentes lógicos)
- `construction/u2-real-market-data/nfr-requirements/`
- `construction/u2-real-market-data/functional-design/`
- `construction/u1-bot-wallet/infrastructure-design/infrastructure-specification.md` (rede e segredos da nuvem)
- `inception/contract-design/contract-summary.md` (X2)
- `inception/domain-design/components.md`
- o código de `SourceCaller`, `MarketCollector` e `MarketDataProperties`
- `infrastructure-design-questions.md` (Q1 a Q4)

Esta versão foi revisada depois da primeira revisão do estágio.

## Deployment

| Facet | Choice | Rationale |
|---|---|---|
| Compute | Nenhum serviço novo. O adaptador só existe no `worker` (`@ProcessRole(WORKER)`) [Q1] | a coleta é do worker |
| Configuração | `MARKET_SOURCES=coingecko` vai para o **worker e a API**. O `MarketDataProperties` passa a aceitar `coingecko` em `KNOWN_SOURCES`. Só o worker recebe a chave. Na API, a fonte serve para `activeSourceKind()`, `attribution()` e a trava BR1.6, sem adaptador nem chave. Um teste confirma que a API sobe com `coingecko` e sem a chave | NFR1.5 vale para os dois processos |
| Local | O `docker-compose.yml` e o Compose de teste continuam com `MARKET_SOURCES=sim-a,sim-b`. `COINGECKO_API_KEY` aparece só com o nome no `.env.example` | testes e `verify.sh` sem rede |
| Rede (nuvem) | A tarefa da API e do worker fica na subrede B, com NAT, como na U1, e o grupo de segurança permite saída em 443. Na U1, API e worker estão na mesma tarefa e no mesmo grupo de segurança, então a rede não impede a API de sair. O que impede é o código: a API não tem o adaptador nem a chave. Essa limitação do piloto é aceita, e a separação vem quando o worker ganhar tarefa própria | só o worker chama a CoinGecko |
| Armazenamento | Tabela nova `marketdata_provider_usage` no banco da aplicação, por migração no `db-migrate` | contador mensal que sobrevive ao reinício |
| IaC | Acréscimos nos módulos `services` (segredo e variáveis) e `observability` (alarmes) do Terraform da U1 | mesma IaC |

## Infrastructure Services

| Service | Role | Configuration | Notes |
|---|---|---|---|
| Secrets Manager | secrets | segredo `coingecko-api-key`, lido só pelo papel de execução do worker | troca manual a cada 90 dias. Em caso de vazamento: revogar a chave na CoinGecko, gravar a nova no segredo e forçar nova implantação do worker |
| PostgreSQL da aplicação | database | `marketdata_provider_usage` (`source`, `month`, `calls`, `updated_at`), chave primária (`source`, `month`), 13 meses de retenção | contador do orçamento |
| CoinGecko | external | 250 chamadas por minuto e 450 mil por mês na configuração, conferidas com o contrato | o preço do plano está pendente (NFR9.1) |

## Coleta: correções da revisão

| Item | Desenho |
|---|---|
| Prazo da `coingecko` | O `SourceCaller` dá a ela um limite de 25 s, com 1 tentativa. O adaptador trabalha com 18 s pelo relógio injetável e não abre uma chamada nova depois de 12 s, porque cada chamada tem limite de 4 s mais 2 s de conexão. Assim a rodada termina no máximo em cerca de 18 s, e o corte de 25 s fica só como proteção. Os preços obtidos voltam no término normal |
| Contador | O `CallBudget` soma a tentativa **antes** de abrir a chamada, numa transação própria e curta (upsert). Uma chamada em voo interrompida já está contada |
| Leitura do uso | Um trabalho do worker, com ShedLock e de hora em hora, lê a rota de uso da chave e grava no contador o maior valor entre a tabela e a CoinGecko (NFR3.7) |
| Tolerância de falha | `MarketSourceAdapter` ganha `tolerantFailure()`, que por padrão é `false`; a `coingecko` devolve `true`. No coletor, tanto o caminho de sucesso (`RoundSummary`) quanto o caminho de exceção (`fail()`) consultam o contador de rodadas seguidas sem preço. Para a fonte tolerante, a primeira rodada vira DEGRADED e a segunda, FAILED. O contador fica em memória, então depois de um reinício a primeira falha volta a ser DEGRADED, o que é aceito |
| Categoria do DEGRADED | Rodada parcial ou cortada usa a categoria que já existe e mais combina: `TIMEOUT` quando o prazo cortou, `ERROR_RESPONSE` quando houve 429 ou 5xx. Assim não é preciso migrar o CHECK de `failure_category` |
| Teste do mapa de estados | Teste de contrato do coletor. Para a fonte tolerante: OK → parcial (DEGRADED) → sem preço (DEGRADED) → sem preço (FAILED) → OK. As fontes simuladas continuam como hoje |

## Rollback

1. Desligar o REAL (`memetrade.real.enabled=false`) na API e no worker. O saque continua (`team.md`).
2. Voltar a imagem e o `MARKET_SOURCES` juntos, na mesma implantação. A imagem anterior só aceita `sim-a` e `sim-b`.
3. Manter a tabela `marketdata_provider_usage`, que é só um acréscimo, no padrão expand-contract.
4. Religar o REAL só quando a fonte `coingecko` voltar.

## Ordem de implantação

1. Migração.
2. Segredo.
3. Worker com `coingecko`.
4. API com `coingecko`.
5. Na devnet, antes da produção: conferir com a API real se a resposta vem sem compressão. O fundador faz essa conferência e registra o resultado.

## Shared Infrastructure

| Shared Resource | Owner Unit | Consumer Units | Access Boundary |
|---|---|---|---|
| `MarketDataQuery` (M4) | U2 | U3, U4 | interface pública |
| `WatchedTokens` | U2 | a U4 implementa | interface pública |
| Saída por NAT da tarefa | U1 | U2 | grupo de segurança da tarefa. A NAT única é um ponto único de falha para o frescor; o piloto aceita, porque o alerta de preço velho e o de worker fora do ar cobrem esse caso |
