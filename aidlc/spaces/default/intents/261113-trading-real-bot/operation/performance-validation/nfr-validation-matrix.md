# Matriz de validação dos NFR — intent 261113-trading-real-bot

Conversa: português (Brasil). Estágio: Performance Validation. Data: 2026-11-20.

As 19 metas abaixo são as que o Build and Test deixou com dono `performance-validation` (`construction/build-and-test/build-and-test-summary.md`). Nenhuma rodada de carga aconteceu ainda, então todas estão **Pendente**: nenhum valor medido entra aqui antes do run.

**Ambientes que fecham as metas:**

- **CI:** cenário `real` do `load-test.yml`, no GitHub Actions, com o validador local e o `kms-sim`. É ambiente de CI, não produção [Q1].
- **CI backend:** teste de medição do job backend da CI.
- **CI capacidade:** rodada de 1.000 carteiras, ainda não construída.
- **Devnet:** a conta AWS devnet, depois do primeiro `apply`.
- **Produção 30 dias:** tráfego real do piloto por 30 dias corridos.

**Dono:** o fundador (responsável do projeto) é o dono de todas as metas. A coluna indica o estágio que executa.

| ID | Fonte | Meta | Como é medida | Ambiente que fecha | Situação | Dono |
|---|---|---|---|---|---|---|
| U1:NFR3.1 | `u1-bot-wallet/nfr-requirements/performance-requirements.md` | Desafio de consentimento e leituras da carteira: p95 até 300 ms. O método pede um teste de carga local com 1.000 carteiras | cliente, no cenário `real`: `GET /real/bot-wallet`, depósitos, saques (lista e item) e `POST /real/consents/challenge`; p50, p95 e p99 | CI para o piloto x2; a condição de 1.000 carteiras do método fica para a CI capacidade; devnet repete pelo balanceador e pelo RDS | Pendente | fundador; performance-validation |
| U1:NFR3.2 | idem | Resposta do pedido de saque: p95 até 500 ms | cliente: `POST /real/bot-wallet/withdrawals` nas 5 rajadas de 21 saques | CI; devnet repete | Pendente | fundador; performance-validation |
| U1:NFR3.3 | idem | Do pedido de saque ao envio para a rede: p95 até 5 s | cliente: do instante antes do POST até a primeira leitura `SENT`, lida a cada 250 ms | CI (validador local e `kms-sim`) **e** devnet com o KMS real, como pede o requisito antes da mainnet | Pendente | fundador; performance-validation |
| U1:NFR3.4 | idem | Ativação, sem o tempo de assinar: até 3 s (p95) | cliente: o `POST /real/bot-wallet` das 21 ativações (20 ao mesmo tempo) | CI (`kms-sim`) **e** devnet com o KMS real | Pendente | fundador; performance-validation |
| U1:NFR4.1 | `u1-bot-wallet/nfr-requirements/reliability-requirements.md` | 99,5% ao mês sem 5xx na leitura da carteira e no pedido de saque, em 30 dias corridos | métrica do balanceador, `1 - (5xx do alvo + 5xx do balanceador) / pedidos` em 30 dias, mais o batimento do TxSigner em intervalos de 240 s (no máximo 54 ruins); consultas em `load-test-plan.md`, seção 10 | Produção 30 dias | Pendente | fundador; performance-monitoring |
| U1:NFR3.6 | `u1-bot-wallet/nfr-requirements/scalability-requirements.md` | Piloto com até 21 carteiras; o desenho e os testes de carga consideram 1.000 | o cenário `real` mede as 21 carteiras do piloto x2; 1.000 carteiras pedem a rodada de capacidade | CI para 21 carteiras; CI capacidade para 1.000 (só com `kms-sim`). **Inalcançável na devnet e na produção pelo desenho de custo:** 1.000 chaves KMS custam cerca de US$ 1.000 por mês, contra US$ 350 por conta | Pendente | fundador; performance-validation |
| U1:NFR3.9 | idem | Perfil de carga: 1.000 carteiras, 10 leituras/s e 1 saque/s por 10 min (medição informativa antes da mainnet) | rodada de capacidade a construir (`load-test-plan.md`, seção 7) | CI capacidade (`kms-sim`); inalcançável no KMS real pelo mesmo custo | Pendente | fundador; performance-validation |
| U1:NFR3.8 | idem | Saques concorrentes de carteiras diferentes não se bloqueiam | cliente: em cada rajada, 21 carteiras pedem saque em 10 s; o mais lento chega ao envio em até 5 s (com uma trava global, os últimos esperariam pelos primeiros) | CI | Pendente | fundador; performance-validation |
| U2:NFR3.1 | `u2-real-market-data/nfr-requirements/performance-requirements.md` | Rodada de preços com 50 tokens: até 10 s, prazo total de 20 s | antes da produção, medição sintética com WireMock e 300 ms por chamada (`CoinGeckoRoundMeasurementTest`, timer `marketdata_round_seconds{kind=price}`); depois, a mesma métrica com a API real | CI backend (teste escrito, verde nesta máquina: 600 ms contra 10 s; falta rodar na CI) **e** produção, porque a CoinGecko não tem devnet | Pendente | fundador; performance-validation |
| U2:NFR3.2 | idem | Descoberta (2 chamadas): até 5 s | o mesmo teste, `kind=discovery`; depois, produção | CI backend e produção | Pendente | fundador; performance-validation |
| U2:NFR3.3 | idem | Fatia de detalhes: até 5 s | o mesmo teste, `kind=detail`; depois, produção | CI backend e produção | Pendente | fundador; performance-validation |
| U2:NFR3.4 | idem | Leitura do scanner e do detalhe sem piora em relação à intent anterior | cenário `u4` do `load-test.yml`, que já existe, comparado a `docs/perf/u4-discovery-2026-11-06.md` | CI (cenário `u4`, sem mudança no workflow) | Pendente | fundador; performance-validation |
| U2:NFR4.1 | `u2-real-market-data/nfr-requirements/reliability-requirements.md` | Em 99% dos minutos do mês, o preço mais velho tem até 60 s | intervalos de 240 s com `marketdata_oldest_price_age_seconds` acima de 60 s ou sem dado, no máximo 108 em 30 dias; consulta em `load-test-plan.md`, seção 10 | Produção 30 dias (a devnet serve só de ensaio) | Pendente | fundador; performance-monitoring |
| U4-NFR3.4 | `u4-real-execution/nfr-requirements/performance-requirements.md` | `GET` de ordens e `GET /pnl`: p95 até 300 ms | cliente: as duas rotas no rodízio da tela | CI; devnet repete com o RDS | Pendente | fundador; performance-validation |
| U5-NFR3.1 | `u5-real-operations/nfr-requirements/performance-requirements.md` | Reconciliação de uma carteira: p95 até 5 s | servidor: histograma `real_reconciliation_seconds`, na janela da carga, com rodadas a cada minuto | CI (RPC do validador local) **e** devnet (RPC real) | Pendente | fundador; performance-validation |
| U5-NFR3.2 | idem | Rodada das 21 carteiras: máximo de 60 s | servidor: `real_ops_job_seconds_max{job="reconciliation"}` lido a cada 10 s; execuções por `_count` | CI e devnet | Pendente | fundador; performance-validation |
| U5-NFR3.3 | idem | Trabalho de portões: máximo de 10 s por execução | servidor: `real_ops_job_seconds_max{job="gates"}` | CI mede sem a leitura do GitHub (o resultado é fixo em `GREEN`); **só fecha na devnet**, com a leitura real da CI | Pendente | fundador; performance-validation |
| U5-NFR3.4 | idem | `GET /admin/real/users` (página de 50) e `/users/{userId}`: p95 até 500 ms | cliente: administrador a cada 2 s durante os 25 min | CI; devnet repete | Pendente | fundador; performance-validation |
| BT-TC-PERF | Testing Contract (Comprehensive): "Add performance and security tests when NFRs demand them" | Testes de desempenho para as metas numéricas do REAL | o cenário `real` (`scripts/load/real.mjs`, 34 testes do próprio código) e o `CoinGeckoRoundMeasurementTest` | CI: fecha quando o run do cenário `real` passar | Pendente | fundador; performance-validation |

## Resumo por ambiente

| Ambiente | Metas que ele fecha sozinho | Metas que ele fecha em parte |
|---|---|---|
| CI (cenário `real`) | U1:NFR3.2, U1:NFR3.8, U4-NFR3.4, U5-NFR3.4, BT-TC-PERF | U1:NFR3.1 (falta a condição de 1.000 carteiras), U1:NFR3.3 e U1:NFR3.4 (falta o KMS real), U1:NFR3.6 (21 carteiras), U5-NFR3.1 e U5-NFR3.2 (falta o RPC real), U5-NFR3.3 (sem a leitura do GitHub) |
| CI (cenário `u4`) | U2:NFR3.4 | — |
| CI backend | — | U2:NFR3.1 a NFR3.3 (parte sintética) |
| CI capacidade (a construir) | U1:NFR3.9 | U1:NFR3.1 e U1:NFR3.6 (1.000 carteiras) |
| Devnet | U5-NFR3.3 | U1:NFR3.3, U1:NFR3.4, U5-NFR3.1, U5-NFR3.2 (repetição com KMS real e RPC real) |
| Produção 30 dias | U1:NFR4.1, U2:NFR4.1 | U2:NFR3.1 a NFR3.3 (API real) |

## Metas que parecem inalcançáveis pelo desenho

- **U1:NFR3.6 e U1:NFR3.9 com 1.000 carteiras no KMS real.** Cada carteira é uma chave KMS, a cerca de US$ 1 por mês. As 1.000 custariam cerca de US$ 1.000 por mês, e o orçamento é de US$ 350 por conta. A escala só pode ser testada na CI com o `kms-sim`. Atendê-la de verdade pede rever o orçamento ou o desenho de uma chave por carteira (ADR-005).
- **U5-NFR3.3 na CI.** O resultado da CI é fixo em `GREEN` no validador local, então a chamada de até 5 s ao GitHub nunca acontece ali.
- **U2:NFR3.1 a NFR3.3 com a API real antes da produção.** A CoinGecko não tem devnet, e os testes não usam rede (NFR8.2).

## Sources

- `construction/build-and-test/build-and-test-summary.md`: as 19 linhas com dono `performance-validation`.
- Os arquivos de requisitos citados na coluna Fonte.
- `load-test-plan.md`: seções 4 a 11.
- `performance-validation-questions.md`: Q1 a Q5.
- `operation/observability-setup/slo-config.md`: SLIs de 30 dias.
- `operation/environment-provisioning/environment-inventory.md`: custo do KMS e o estado NÃO PROVISIONADO.
- `aidlc/spaces/default/memory/project.md`: orçamento de US$ 350 por conta.

## Assumptions & Open Questions

- A coluna Dono usa o fundador para todas as metas, porque ele é o revisor único (`team.md`). U1:NFR4.1 e U2:NFR4.1 são executadas pelo estágio de acompanhamento depois da produção, chamado aqui de `performance-monitoring`, que revisa o orçamento de erro todo mês (`slo-config.md`, seção 3).
- O `CoinGeckoRoundMeasurementTest` entra na CI no próximo push. Até lá, U2:NFR3.1 a NFR3.3 continuam Pendente.
