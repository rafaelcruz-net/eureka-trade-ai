# Requisitos de desempenho — U3 `u3-real-risk`

Conversa: português (Brasil). Fontes: `construction/u3-real-risk/functional-design/functional-spec.md` (F1 a F5), `construction/u3-real-risk/functional-design/rules.md` (BR6.1), `inception/requirements-analysis/requirements.md` (NFR3) e `nfr-requirements-questions.md` (Q1).

Os IDs desta Unit têm o prefixo `U3-` para não colidir com os IDs das outras Units. O número depois do prefixo indica o NFR da intent que o requisito atende.

| ID | Operação | Meta | Como medir |
|---|---|---|---|
| U3-NFR3.1 | `evaluate` de compra, **sem** a espera pela trava, com até 10 posições abertas | p95 até 150 ms | histograma `real_risk_evaluation_seconds{side="BUY"}`, que começa a contar depois de obtida a trava |
| U3-NFR3.2 | `evaluate` de venda (só lê M1 e M4) | p95 até 30 ms | histograma `real_risk_evaluation_seconds{side="SELL"}` |
| U3-NFR3.3 | Espera pela trava da carteira | limite duro de 1 s: `pg_try_advisory_xact_lock` repetido a cada 20 ms, sem mudar a configuração da sessão; passado 1 s, `evaluate` lança erro técnico, não aprova e não aborta a transação no banco | histograma `real_risk_lock_wait_seconds` e `real_risk_errors_total{dependency="lock"}` |
| U3-NFR3.4 | Trabalhos F3, F4 e F5 com 21 carteiras | até 5 s por execução | `real_risk_job_seconds{job}` (temporizador em volta de cada execução) |

**Condição de medida:**
- Antes da produção, num teste de integração com PostgreSQL em contêiner, com 21 carteiras, 10 posições cada e 4 avaliações por segundo.
- Na produção, pelas métricas.
- As metas são informativas, sem portão na CI (`team.md`, Desempenho).

**Regras:**
- A barreira não faz chamada de rede: lê o banco e as interfaces de módulo, que leem o banco.
- A trava dura até o fim da transação do ciclo do bot (BR6.1). Por isso a U4 não pode fazer chamada de rede nessa transação: a cotação, a assinatura e o envio ficam no consumidor da fila, em outra transação. Esse é um requisito da U4, registrado aqui como dependência.
- Os preços das posições são lidos numa chamada só por avaliação.
