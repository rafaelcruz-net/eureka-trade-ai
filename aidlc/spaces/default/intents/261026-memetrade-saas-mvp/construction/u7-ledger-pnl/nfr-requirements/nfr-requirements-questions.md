# Perguntas de requisitos não funcionais — U7 Ledger e P&L

Contexto: o design funcional da U7 está em [functional-spec.md](../functional-design/functional-spec.md) e [rules.md](../functional-design/rules.md). Os requisitos da inception que tocam a U7 são NFR2 (20 convidados e 60 bots PAPER; cada ciclo termina antes do dado ficar desatualizado), NFR3 (p95 ≤ 2 s para carregar o P&L com 20 usuários), NFR4 (100% das operações com chave de idempotência; 100% dos cenários de P&L exatos), NFR5 (isolamento), NFR8 (decisão reproduzível), NFR9 (saúde, erro e correlação), NFR10 (sem meta de disponibilidade no beta local), NFR12 (cobertura; TDD no núcleo financeiro) e NFR13 (Windows e Linux).

Já resolvido sem pergunta (pode corrigir na revisão do resumo):
- O ledger nunca é apagado nem alterado (FR10.1); não há retenção nem limpeza de lançamentos, ordens, preenchimentos e posições no beta. Só os resultados de reconciliação são limitados aos últimos 100 por pessoa (BR8.1).
- TDD (testes antes do código) em toda a U7: ela é o núcleo financeiro do `team.md` (ledger, P&L e deduplicação).
- As tarefas de marcação, fechamento pendente e reconciliação rodam no mesmo processo que já coleta o mercado, com execução única pelo ShedLock (padrão da U2 e da U4).
- Sem alertas automáticos enquanto o beta roda só localmente, como nas Units anteriores.
- Sem dependência nova: decimal exato do Java (`BigDecimal`), PostgreSQL com `numeric`.

## Q1. [Desempenho] Quanto tempo a execução de uma ordem pode levar dentro do ciclo do bot?

A execução roda dentro da transação do ciclo da U5, com a linha do bot travada.

- A. p95 ≤ 100 ms para `execute` e `closePositions` (sem contar a decisão e a barreira), com 60 bots ativos
- B. p95 ≤ 250 ms para `execute` e `closePositions`, com 60 bots ativos
- X. Other (please specify)

[Answer]: B

## Q2. [Desempenho] Qual o tempo máximo das rotas de P&L e de ledger?

O NFR3 fala da tela inteira (p95 ≤ 2 s com 20 usuários). A API precisa de uma meta própria, menor.

- A. P&L e ledger: p95 ≤ 500 ms com até 10 000 preenchimentos por pessoa e 20 usuários simultâneos
- B. P&L e ledger: p95 ≤ 1 s com até 10 000 preenchimentos por pessoa e 20 usuários simultâneos
- X. Other (please specify)

[Answer]: B

## Q3. [Segurança] As rotas de P&L, ledger, operações e reconciliação têm limite de requisições por pessoa?

- A. Sim: 60 requisições por minuto por pessoa em cada rota, como o scanner da U4; "verificar agora" continua com 6 por minuto; acima disso, `429` com `Retry-After`
- B. Só "verificar agora" tem limite (6 por minuto); as leituras não têm
- X. Other (please specify)

[Answer]: B

## Q4. [Observabilidade] Quais métricas próprias a U7 publica?

- A. Contadores de ordens preenchidas, parciais e não preenchidas por motivo, de duplicadas e de conflitos; e um indicador de reconciliação com o número de pessoas com divergência (deve ficar sempre em zero). Sem identificador de pessoa, bot ou token nos rótulos
- B. Só a métrica de saúde e a de erro exigidas pelo NFR9
- X. Other (please specify)

[Answer]: B

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
