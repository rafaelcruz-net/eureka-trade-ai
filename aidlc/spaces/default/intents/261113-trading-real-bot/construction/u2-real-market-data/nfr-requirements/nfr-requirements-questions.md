# Perguntas de NFR Requirements — U2 `u2-real-market-data`

Conversa: português (Brasil). Base:
- `construction/u2-real-market-data/functional-design/` (`rules.md` BR1.1 a BR1.10, `functional-spec.md`);
- `inception/requirements-analysis/requirements.md` (NFR1 a NFR10);
- `inception/contract-design/contract-summary.md` (X2: 5 s e uma repetição);
- `aidlc/spaces/default/codekb/eureka-trader-ai/technology-stack.md` (Resilience4j, Micrometer);
- a regra ArchUnit `theMarketModuleNeverUsesTheNetwork`, que hoje proíbe `java.net` em todo o módulo `marketdata`.

## Q1. [Arquitetura] Onde a rede pode entrar no módulo de mercado?

A. Só no pacote do adaptador real, `com.memetrade.marketdata.infra.coingecko`. A regra ArchUnit passa a proibir rede no resto do módulo e a permitir neste pacote, com o mesmo motivo registrado. O cliente HTTP é o `RestClient` do Spring que o projeto já usa, com tempo limite próprio.
B. Tirar a regra inteira
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Desempenho] Metas da coleta

A. As metas, em p95:
- **Rodada de preços:** até 10 s com 50 tokens.
- **Descoberta:** até 5 s.
- **Fatia de detalhes:** até 5 s.

A leitura do scanner continua com a meta atual: o dado sai do banco, nunca da chamada ao fornecedor. A rodada nunca segura uma trava do banco enquanto espera a rede.
B. Sem metas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Segurança] Proteção da chamada ao fornecedor

A. As regras:
- só HTTPS, com o certificado conferido;
- o endereço base vem da configuração, numa lista fixa de domínios da CoinGecko;
- a resposta tem tamanho máximo de 2 MB e o JSON é lido com limites de profundidade e tamanho de texto;
- a chave nunca aparece em log, resposta ou métrica;
- na nuvem, só o worker sai para a CoinGecko.
B. Sem limites extras
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Confiabilidade] Proteção contra falha do fornecedor

A. Um circuito do Resilience4j para a CoinGecko conta só falhas técnicas (tempo esgotado, conexão, 5xx e 429 longo). Ele abre com 50% de falha em 60 s, com no mínimo 5 chamadas, e fica aberto por 60 s. Com o circuito aberto, a rodada termina sem chamar o fornecedor, e a fonte segue a regra de estados (DEGRADED na primeira rodada, FAILED na segunda). A meta de disponibilidade da coleta é de 99% ao mês, medida pelas rodadas OK ou DEGRADED.
B. Só o tempo limite
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Observabilidade] Métricas e alertas

A. **Métricas:**
- chamadas por minuto e por mês, com o percentual do orçamento;
- duração da rodada;
- estado da fonte;
- `marketdata_invalid_values_total{field}`;
- tokens ativos;
- idade do preço mais velho.

**Alertas:**
- fonte FAILED por mais de 2 min;
- orçamento mensal a 80%;
- mais de 5% de valores inválidos numa hora;
- preço mais velho com mais de 60 s em 3 rodadas seguidas, sinal de que o REAL vai recusar ordens.
B. Só logs
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Custo e escala] Plano e volume

A. O piloto usa 50 tokens ativos. O orçamento mensal de 450 mil chamadas e as 250 por minuto ficam configuráveis e são conferidos com o contrato da CoinGecko antes da produção. O custo do plano entra no teto de US$ 100 por mês do NFR9 (dados), e o fundador decide o plano. Acima de 100 tokens, a escala é revista.
B. Sem teto
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Rede só no pacote infra.coingecko, com RestClient; regra ArchUnit ajustada [Q1].
- Rodada de preços até 10 s, descoberta e detalhes até 5 s; scanner lê do banco [Q2].
- HTTPS, domínios fixos, resposta até 2 MB, JSON com limites, chave fora de log; só o worker sai [Q3].
- Circuito Resilience4j com falhas técnicas; coleta 99% ao mês [Q4].
- Métricas de chamadas, orçamento, rodada, estado, inválidos e idade; quatro alertas [Q5].
- 50 tokens, orçamento configurável conferido com o contrato; custo no teto do NFR9 [Q6].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
