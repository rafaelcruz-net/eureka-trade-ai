# Perguntas de NFR Design — U2 `u2-real-market-data`

Conversa: português (Brasil). Base: `construction/u2-real-market-data/nfr-requirements/` (os seis documentos), `construction/u2-real-market-data/functional-design/`, `inception/contract-design/contract-summary.md` (X2), `inception/domain-design/components.md` e os pontos menores da segunda revisão do NFR Requirements (falha na leitura do uso da chave, justificativa dos 10% de repetições).

## Q1. [Componentes] Como fica o adaptador?

A. Quatro peças no pacote `infra.coingecko`:
- `CoinGeckoHttp`: chamadas, leitura do corpo em fluxo com limite de 2 MB, recusa de redirecionamento, JSON com limites.
- `CoinGeckoAdapter`: implementa `MarketSourceAdapter`; cuida da rodada de preços, da descoberta, da fatia de detalhes e do conjunto ativo em memória.
- `CallBudget`: limitador por minuto e orçamento mensal.
- `ValueParser`: lê os campos como decimal exato, aplica as faixas e devolve `unknown`.

A interface `WatchedTokens` fica em `marketdata.api`, com uma implementação padrão vazia.

B. Uma classe só
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Cota] Como o limite e o orçamento são aplicados?

A. O limite por minuto usa o RateLimiter do Resilience4j, com 250 chamadas e espera zero; acima dele, a chamada não é feita. Cada chamada pede licença ao `CallBudget`, nesta ordem de prioridade:
1. preços, que são bloqueados só com o orçamento esgotado;
2. descoberta, bloqueada a partir de 95% do orçamento;
3. detalhes, bloqueados a partir de 90%.
B. Sem prioridade
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Correção da revisão] Falha na leitura do uso da chave

A. Se a rota de uso da chave falhar, ou se o contrato não confirmar que ela existe, o worker entra em modo conservador:
- considera o orçamento já em 90%, o que suspende os detalhes;
- continua com os preços e a descoberta;
- tenta ler de novo a cada 10 min;
- gera um alerta.

A existência da rota é conferida no contrato antes da produção. Se ela não existir, o contador do mês é gravado no banco da aplicação numa tabela pequena (`marketdata_provider_usage`), com migração própria.
B. Seguir sem contador
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Correção da revisão] Repetições

A. Só há repetição em tempo esgotado e em 5xx, uma vez, sem repetir 4xx nem 429. O teto de 10% é uma reserva de orçamento, não uma meta. Se as repetições passarem de 10% das chamadas em uma hora, um alerta avisa que o fornecedor está instável, e o orçamento é revisto.
B. Sem teto
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Observabilidade] Onde ficam métricas e alertas?

A. As métricas são do Micrometer, com os nomes do NFR, sem chave nem mint nos rótulos. As regras de alerta vão para `infra/observability/` (Prometheus local) e seguem para o CloudWatch na nuvem, como na U1:
- fonte FAILED;
- orçamento a 80%;
- valores inválidos;
- preço acima de 60 s;
- repetições acima de 10%;
- modo conservador.
B. Só logs
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Quatro peças em infra.coingecko e a interface WatchedTokens vazia [Q1].
- RateLimiter de 250 por minuto e orçamento com prioridade: preços, descoberta (95%), detalhes (90%) [Q2].
- Modo conservador quando a leitura do uso falha; tabela própria se a rota não existir no contrato [Q3].
- Repetição só em tempo esgotado e 5xx, uma vez; alerta acima de 10% por hora [Q4].
- Métricas Micrometer sem chave nem mint; seis alertas em infra/observability e CloudWatch [Q5].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
