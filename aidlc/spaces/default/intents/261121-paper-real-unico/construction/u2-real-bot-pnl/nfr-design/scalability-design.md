# Desenho de escala — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). Data: 2026-11-22. Revisado depois de `reviews/review-01.md` e das decisões do fundador de 2026-11-22 (F-1 a F-3, em `performance-design.md`).

A U2 não muda a escala do sistema: roda no monólito atual (API e worker no mesmo app Spring Boot, um PostgreSQL), sem processo, réplica, fila nem cache novos. Este arquivo fixa o volume de referência, a concorrência, o crescimento de `real_entry_slot` e o custo, seguindo as respostas Q1 e Q3 [Q1] [Q3].

## Resumo das decisões

| Tema | Decisão | NFR |
|---|---|---|
| Unidade de partição | A pessoa. Cada leitura do P&L REAL é de uma pessoa, em fluxo, com 1 conexão do pool principal. Pessoas diferentes nunca esperam umas pelas outras. | NFR1.15, NFR1.16 |
| Volume de referência | 14.600 ordens REAL e de 29.200 a 43.800 lançamentos por pessoa (10 bots, 2 anos). Acima disso, a leitura continua em fluxo, limitada pelo teto `real-max-orders` (50.000, F-2) e pelo prazo do bloco. | NFR1.14 |
| Leituras REAL simultâneas | No máximo 8: C4 e `GET /api/v1/real/pnl` passam pelo pool `pnl-mode-block` (U1). | NFR1.16 |
| Pools | Hikari principal sem mudança (20 na API, 36 no worker); pool `pnl-mode-block` sem mudança (8 threads, fila de 16); pool novo `pnl-mismatch-audit` de 2 conexões, só para o evento de divergência. | NFR1.16 |
| Componentes novos | Nenhum serviço, réplica, fila, cache ou tabela de P&L guardado. | NFR1.17, NFR8.1 |
| Dados novos | `real_entry_slot` (1 linha por assinatura confirmada) e o índice `real_ledger_entry_wallet_created_idx` (`0023`); `real_risk_day_deferred`, no máximo 1 linha por carteira e dia com a fotografia adiada (`0024`, F-4). | NFR1.18, NFR1.19 |
| Definição de tarefa | Sem mudança: o teto e a variável `MEMETRADE_PNL_BYMODE_REALMAXORDERS` ficam (F-2). | NFR8.1 |
| Custo AWS | 1 métrica a mais na lista do CloudWatch e 1 alarme, cerca de US$ 0,40 por mês por conta. | NFR8.2 |

## Volume e memória por leitura (NFR1.14, NFR1.15)

| Item | Referência por pessoa | Onde fica |
|---|---|---|
| Ordens assentadas | 14.600 (teto de 50.000) | índice compacto na memória do processo (cerca de 1,5 MB) |
| Lançamentos REAL | 29.200 a 43.800 | passam um a um pelo `Consumer`; no banco, a ordenação usa até 12 MB de `work_mem` (`performance-design.md`, "Memória de ordenação") |
| Posições abertas | por carteira, titular e token (no máximo dezenas) | memória, até o fim da leitura |
| Bots | até 10 | memória |

O que fica na memória do processo por leitura é proporcional às ordens, não aos lançamentos, e o teto de 50.000 ordens limita esse índice a cerca de 5 MB. Com no máximo 8 leituras REAL simultâneas, são no máximo cerca de 40 MB de índices, longe do limite de 900 MB do contêiner `api`.

Acima da referência nenhuma meta é prometida. Uma pessoa com mais de 50.000 ordens assentadas vê o bloco REAL `UNAVAILABLE` com `TOO_MANY_ORDERS`, como na U1; abaixo do teto, uma leitura que passa do prazo vê o bloco `UNAVAILABLE` com `TIMEOUT`. Nunca sai número parcial, e a memória nunca cresce sem limite.

## Concorrência e conexões (NFR1.10, NFR1.16)

- Cada leitura REAL (C4 ou `GET /api/v1/real/pnl`) usa 1 conexão do pool principal, pela thread do pool `pnl-mode-block`, do começo ao fim da transação de leitura. O pool `pnl-mode-block` limita a 8 os blocos que leem ao mesmo tempo (PAPER e REAL juntos); com a fila cheia, o bloco sai `UNAVAILABLE` com a causa `REJECTED`.
- O evento de auditoria de uma divergência usa o pool próprio `pnl-mismatch-audit` (2 conexões sempre abertas, espera de no máximo 250 ms), depois que a transação de leitura fechou. A leitura nunca segura duas conexões do pool principal, e a gravação nunca espera por ele (`security-design.md`). O PostgreSQL recebe 2 conexões a mais da API.
- Com 20 usuários a cada 2 s, alternando C4 e `GET /api/v1/real/pnl`, o pico é de cerca de 10 leituras por segundo, cada uma com p95 abaixo de 1 s: o pool `pnl-mode-block` de 8 threads e o Hikari de 20 aguentam esse ritmo. O portão `hikaricp_connections_pending > 0` por no máximo 10 s seguidos e o de 0 blocos `REJECTED` (NFR1.6, contador com `cause`) conferem isso.
- No worker, a leitura de custo roda dentro da transação do ciclo (4 `cycle-threads`) ou de um trabalho do `RealRiskJobs`, num ponto de salvamento, sem conexão nova.
- Nenhuma trava entre leituras. Nada da U2 roda sob a trava da carteira: a compra não lê a atribuição, e a leitura de custo das rodadas e do ciclo (no máximo 250 ms) roda fora dela (`performance-design.md`).
- O tamanho do pool principal da API e o do worker não mudam.

## Crescimento de `real_entry_slot` (NFR1.19)

| Medida | Valor |
|---|---|
| Linhas | 1 por assinatura confirmada: ordem REAL (`CONFIRMED` ou `FAILED` com transação), condicional executada, depósito, saque e taxa da carteira do bot |
| Tamanho por linha | cerca de 100 bytes de dados (assinatura de até 88 caracteres, `bigint`, `timestamptz`) mais cerca de 100 bytes na chave primária |
| Piloto (21 carteiras, 3 bots, até 4 ordens por bot por dia, depósitos e saques) | até cerca de 100 mil linhas e 20 MB por ano |
| Expurgo | nenhum nesta Unit: só inserção, como o ledger |

O índice novo de `real_ledger_entry` (cerca de 40 bytes por lançamento) cresce na mesma proporção do ledger. Os dois cabem no RDS que já existe, sem mudança de classe de instância. Uma política de retenção para `real_entry_slot`, se um dia for preciso, acompanha a do ledger REAL e é decidida em outro estágio.

## Escala horizontal

Nada na U2 impede mais de uma instância da API:

- a leitura não guarda estado entre pedidos (ADR-004);
- o limite do evento de divergência é lido da auditoria e protegido por trava consultiva no banco, então vale em todas as instâncias (`security-design.md`);
- `recordSlot` é idempotente pela chave primária (`ON CONFLICT DO NOTHING`).

Cada instância da API acrescenta 2 conexões do pool `pnl-mismatch-audit` ao total do PostgreSQL. O worker continua com uma instância lógica; a U2 não acrescenta trabalho agendado.

## Custo e ambientes (NFR8.1 a NFR8.4)

- **Infraestrutura.** 0 recursos de serviço novos em `infra/terraform/`. Mudanças de IaC: a métrica `pnl_real_reconciliation_mismatches_total` na lista de `app_metric_declarations` (`app-metrics.tf`) e o alarme `real_ops_pnl_reconciliation_mismatch` (`real-ops.tf`), dentro do CloudWatch que já existe (`observability-design.md`).
- **Definição de tarefa.** 0 mudanças (F-2): o teto `real-max-orders` e a variável `MEMETRADE_PNL_BYMODE_REALMAXORDERS` ficam como a U1 os deixou. O pool `pnl-mismatch-audit` usa as mesmas variáveis de conexão do banco que já existem; o tamanho (2) e a espera (250 ms) ficam no `application.yml`, sem variável nova.
- **Orçamento.** US$ 350 por mês por conta, sem mudança. Custo recorrente acrescentado: 1 métrica personalizada no namespace `Eureka/App` (sem dimensões, a soma das 8 séries) e 1 alarme padrão, cerca de US$ 0,40 por mês por conta. A etiqueta `cause` de `pnl.mode_block.unavailable` fica só no Prometheus.
- **Ambientes.** Os usuários usam só a mainnet; nenhuma configuração da U2 abre a devnet (NFR8.3).
- **Ordem de implantação.** A U1 só vai para a mainnet num SHA que já tem a U2 com o Build and Test aprovado. A `0023` roda no `db-migrate` antes da troca das imagens; as imagens anteriores não leem nem escrevem a tabela nem o índice novos (NFR8.4, NFR2.3).

## Sources

- `nfr-design-questions.md`: Q1 e Q3 e o resumo confirmado [Q1] [Q3].
- `reviews/review-01.md` (R-04, R-07, R-09) e as decisões do fundador de 2026-11-22 (F-2).
- `construction/u2-real-bot-pnl/nfr-requirements/scalability-requirements.md`: NFR1.14 a NFR1.19, NFR8.1 a NFR8.4; `performance-requirements.md`: NFR1.6, NFR1.10; `tech-stack-decisions.md`: NFR8.11.
- `construction/u2-real-bot-pnl/functional-design/rules.md`: BR10.1, BR10.2; `entities.md`: ENT-202, ENT-204, ENT-206.
- `inception/contract-design/contract-summary.md`: C12, C13, C14, PQ-10.
- `construction/u1-mode-skeleton/nfr-design/performance-design.md` e `scalability-design.md` (pool `pnl-mode-block`, Hikari, `real-max-orders`).
- Código lido: `pnl/infra/PnlByModeProperties.java`, `pnl/modeview/PnlModeMetrics.java`, `application.yml` (Hikari com 20 conexões e espera de 2 s, `cycle-threads: 4`), `infra/terraform/main.tf` (`DB_POOL_MAX_SIZE` 20 e 36, `MEMETRADE_PNL_BYMODE_REALMAXORDERS`), `infra/terraform/modules/observability/app-metrics.tf`, `db/changelog/changes/0016-real-bot-wallet.sql`.
- `aidlc/spaces/default/memory/team.md`: Deployment (contas, orçamento, `db-migrate`, expand-contract); `project.md`: orçamento de US$ 350, leitura do CloudWatch Agent a cada 120 s.

## Assumptions & Open Questions

1. [assumption] Os tamanhos por linha e a projeção do piloto são estimativas; não mudam nenhuma meta.
2. [assumption] O pico de cerca de 10 leituras por segundo vem do tráfego de NFR1.6 (20 usuários, uma leitura a cada 2 s). Se o cenário mostrar blocos `REJECTED`, o ajuste é o `pool-size` dentro do domínio de 1 a 16 da U1, refazendo a conta de memória de `performance-design.md`.
3. [assumption] As 2 conexões do pool `pnl-mismatch-audit` cabem no `max_connections` do PostgreSQL local e do RDS do piloto (API 20 + 2, worker 36, TxSigner e trabalhos de retenção).
