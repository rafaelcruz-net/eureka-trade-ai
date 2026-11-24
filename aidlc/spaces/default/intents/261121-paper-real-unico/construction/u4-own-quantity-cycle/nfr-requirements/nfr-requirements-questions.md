# Perguntas do NFR Requirements — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele.

Fontes:
- `construction/u4-own-quantity-cycle/functional-design/` (33 regras, D1 a D5);
- `inception/requirements-analysis/requirements.md` (NFR1 a NFR8);
- `construction/u2-real-bot-pnl/nfr-requirements/`: orçamento de 250 ms da leitura `CYCLE` e padrão de métricas;
- `project.md`: regra de 120 s / 240 s do CloudWatch.

## Q1. Tempo do ciclo

Quanto tempo a leitura da quantidade própria pode levar dentro do ciclo?

A. A leitura da quantidade própria fica fora da transação do ciclo, com p95 ≤ 250 ms (o mesmo orçamento da U2) e sem segurar a trava do bot. O ciclo REAL completo continua dentro do prazo atual do slot, sem nenhum segundo a mais. Na U4 isso é medido em teste de integração com 2 anos de histórico e só fica registrado no log; não entra portão de carga novo. (recomendado)
B. Portão de carga novo
X. Other (please specify)

[Answer]: A

## Q2. Segurança do dinheiro e testes

Que testes garantem que o bot não vende além da própria quantidade?

A. Os testes são escritos antes do código para os 20 cenários T1 a T20, que incluem:
   - o corte da venda;
   - quantidade zero;
   - vendas em aberto e decisões na fila;
   - AMBIGUOUS;
   - falha e prazo esgotado;
   - flag desligada (comportamento igual ao de hoje);
   - concorrência pela marca do bot (`STALE_SNAPSHOT`);
   - o risco residual documentado;
   - compras sem mudança;
   - o PAPER sem mudança.

   O piso de 80% e a estratégia `Standard` continuam valendo. Os testes de risco do `team.md` continuam verdes, entre eles a pausa, a idempotência, "uma só transação por ordem" e o saque sempre disponível. (recomendado)
B. Só cenários principais
X. Other (please specify)

[Answer]: A

## Q3. Observabilidade e alerta (D3)

O que passa a ser medido e alertado?

A. Dois contadores novos:
   - `real_own_holdings_unavailable_total{reason}`;
   - `real_cycle_sell_capped_total{outcome}`, com os valores `CAPPED`, `NO_OWN_QUANTITY` e `STALE_SNAPSHOT`.

   Um alarme novo para a falha persistente: 3 ciclos seguidos, período de 300 s, 3 de 3, sem disparar quando falta dado. O contador de indisponibilidade entra na lista do CloudWatch, que custa cerca de US$ 0,30 por mês por conta. O runbook ganha a seção de saída pelas condicionais e por "Vender tudo e sacar". (recomendado)
B. Só logs
X. Other (please specify)

[Answer]: A

## Q4. Implantação da flag (D4) e escala

Como a flag é ligada na implantação, e o que muda na escala?

A. A flag `memetrade.real.own-quantity-sell` é uma variável da definição de tarefa, versionada no Terraform, e começa desligada.
   - Ela só é ligada numa implantação auditada separada.
   - A ordem é API e depois worker, conferindo que não há SELL em PENDING antes e depois.
   - Só liga depois da Performance Validation do 261113.

   A migração da regra de resultados do ciclo é só de expansão (`NOT VALID` + `VALIDATE`). A escala não muda. (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Tempo:** a leitura da quantidade própria fica fora da transação, com p95 de até 250 ms e sem segurar a trava. O ciclo não ganha tempo extra. A medição só fica registrada no log [Q1].
- **Dinheiro e testes:** os 20 cenários são escritos antes do código, com o piso de 80%. Os testes de risco do time continuam verdes [Q2].
- **Observabilidade:** dois contadores novos e um alarme para falha persistente (3 ciclos seguidos). O contador entra no CloudWatch, por cerca de US$ 0,30 por mês por conta. O runbook é atualizado [Q3].
- **Flag:** começa desligada e é ligada numa implantação separada, depois da validação de desempenho do REAL. A ordem é API e depois worker, sem venda pendente. A migração é só de expansão [Q4].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
