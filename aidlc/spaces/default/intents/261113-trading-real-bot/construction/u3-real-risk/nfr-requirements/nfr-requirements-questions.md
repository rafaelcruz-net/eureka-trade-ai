# Perguntas de NFR Requirements — U3 `u3-real-risk`

Conversa: português (Brasil). Base:
- `construction/u3-real-risk/functional-design/` (`rules.md` BR1.1 a BR7.3, `functional-spec.md` F1 a F6);
- `inception/requirements-analysis/requirements.md` (NFR2, NFR3, NFR4, NFR5, NFR6, NFR8, NFR10);
- `inception/contract-design/contract-summary.md` (M1, M2, M4, M5);
- `aidlc/spaces/default/codekb/eureka-trader-ai/technology-stack.md`;
- a barreira PAPER atual em `riskcontrol` (`TransactionalOrderGate`, métricas, retenção).

## Q1. [Desempenho] Quanto tempo a barreira pode levar?

A. A `evaluate` de compra tem meta de p95 até 150 ms, contando a leitura de posições e preços, dentro dos 3 s da decisão ao envio (NFR3). A venda tem meta de p95 até 30 ms. A espera pela trava da carteira tem limite de 1 s (`lock_timeout` na transação); passado o limite, a avaliação falha com erro técnico e nunca aprova. Os preços das posições são lidos de uma vez, e nenhuma chamada de rede acontece dentro da trava.
B. Sem metas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Segurança] Que proteções a barreira exige?

A. As proteções:
- O tenant e o usuário vêm do bot ou da autenticação, e toda consulta filtra por eles.
- A lista de bloqueio só muda pela M5, chamada pela área do administrador (U5).
- Os logs mascaram mint e endereço, como hoje.
- Os detalhes da recusa trazem só números da própria carteira.
- Uma regra ArchUnit impede a barreira REAL de ler tabelas ou classes internas da BotWallet, do Ledger e do PAPER; ela usa só `botwallet.api`, `ledger.api`, `marketdata.api` e `riskassessment.api`.
- As regras de ponto flutuante e de rede que já existem passam a valer também para o pacote novo.
B. Só as regras que já existem
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Confiabilidade] O que acontece quando algo falha?

A. A barreira falha fechada: um erro técnico ao ler a carteira, o ledger, o mercado ou a avaliação nunca aprova uma ordem, nem de venda. A ordem fica para o próximo ciclo e o erro é contado e registrado. Os trabalhos (F3, F4, F5) usam ShedLock, são idempotentes e retomam sozinhos depois de reinício. A foto do dia e a reserva sobrevivem a reinício porque ficam no banco. O saque não passa pela barreira e nunca é afetado.
B. Aprovar vendas quando uma leitura falhar
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Escala] Para que volume a barreira é dimensionada?

A. Para o piloto: o fundador e até 20 convidados, até 5 bots por usuário e um ciclo por bot a cada 30 s, ou seja, até cerca de 4 avaliações por segundo no pico. As tabelas têm índices por carteira e dia e por `decisionId`. A retenção das avaliações é a mesma das avaliações PAPER, com o mesmo trabalho de expurgo. Não há meta de carga. Uma meta numérica só entra quando os requisitos definirem uma, como diz o `team.md`.
B. Dimensionar para 1.000 usuários
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Observabilidade] Que métricas e alertas?

A. As métricas:
- `real_risk_evaluations_total{side,outcome,code,detail}`;
- `real_risk_evaluation_seconds` (histograma);
- `real_risk_lock_wait_seconds`;
- `real_risk_reservations{state}`;
- `real_risk_daily_loss_pauses_total`;
- `real_risk_errors_total{dependency}`.

Os rótulos nunca levam mint, endereço nem usuário.

Os alertas:
- reserva vencida (`EXPIRED`) nos últimos 15 min, sinal de que a U4 não consumiu a decisão;
- erros da barreira acima de 5 em 5 min;
- p95 da avaliação acima de 150 ms por 10 min.

As regras ficam em `infra/observability/`, conferidas pelo `promtool`, com os mesmos limites nos alarmes do Terraform.
B. Só as métricas que a barreira PAPER já tem
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Testes] Como provar a barreira?

A. Por ser núcleo financeiro, os testes são escritos antes do código a partir dos cenários S1 a S17 da especificação funcional.
- **Concorrência:** com PostgreSQL real (Testcontainers), duas avaliações simultâneas da mesma carteira nunca passam juntas do teto.
- **Repetição:** uma avaliação repetida com o mesmo `decisionId` não conta duas vezes.
- **Separação REAL/PAPER:** o saldo PAPER nunca entra na conta do REAL.
- **Determinismo:** relógio injetável para 00:00 UTC, 60 s e 5 min.
- **Cobertura:** piso de 80%.
- **Testes obrigatórios por risco do `team.md` tocados pela U3:**
  - limites;
  - pausa;
  - `unknown` nunca zero;
  - separação REAL/PAPER;
  - isolamento entre tenants;
  - deduplicação.
B. Testes depois do código
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Tecnologia] Onde e com que ferramentas?

A. No módulo `riskcontrol`, num pacote próprio `riskcontrol.real`, com `domain`, `infra` e `api`. A M5 entra em `riskcontrol.api`. A trava é `pg_advisory_xact_lock` pelo `JdbcTemplate` que o projeto já usa; as tabelas novas vêm por migração Liquibase; os trabalhos usam o ShedLock que já existe. Nenhuma biblioteca nova.
B. Um módulo novo `realrisk`
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Configuração] Os limites podem ser mudados?

A. Os valores (10% por ordem, 20% de perda, 20 compras, 60 s, 5 min de reserva) ficam na configuração com esses padrões. Fora dos perfis `test` e `local`, a API e o worker não sobem com um valor mais frouxo que o padrão (percentual maior, mais compras, prazo maior). Valores mais rígidos são aceitos. A mudança de valor só entra com nova implantação.
B. Limites fixos no código
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Compra em até 150 ms (p95) e venda em até 30 ms; espera pela trava de no máximo 1 s; nenhuma rede dentro da trava [Q1].
- Tenant do bot, lista de bloqueio só pela M5, logs mascarados, ArchUnit para as fronteiras [Q2].
- Falha fechada, nunca aprova com erro; trabalhos com ShedLock e idempotentes; o saque não é afetado [Q3].
- Dimensionada para o piloto (cerca de 4 avaliações por segundo), retenção igual à do PAPER [Q4].
- Métricas sem mint nem usuário nos rótulos; alertas de reserva vencida, erros e latência [Q5].
- Testes antes do código a partir dos cenários, concorrência com PostgreSQL real e piso de 80% [Q6].
- Pacote `riskcontrol.real`, trava do PostgreSQL, Liquibase e ShedLock, sem biblioteca nova [Q7].
- Limites na configuração, sem aceitar valor mais frouxo fora dos testes [Q8].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
