# Perguntas do NFR Requirements — U1 `u1-mode-skeleton`

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele.

Fontes:
- `construction/u1-mode-skeleton/functional-design/` (BR1.1 a BR10.5);
- `inception/requirements-analysis/requirements.md` (NFR1 a NFR8);
- `inception/contract-design/contract-summary.md`;
- `team.md`, nas seções Testing Posture e Code Style;
- o estado do intent, com a Test Strategy `Standard`.

## Q1. Desempenho da U1

Que metas de desempenho valem para a U1?

A. As metas são estas:
   - `GET /pnl/by-mode`, `/bots/real-eligibility` e `/real/account-limits` respondem em até 1 s no p95, para uma pessoa com até 10 bots (NFR1).
   - `POST /bots` responde em até 1 s no p95.

   Na U1, o p95 é medido no teste de integração com dados semeados: 10 bots e 2 anos de ordens do próprio bot. O cenário de carga da CI fica para a U2, que troca o cálculo. (recomendado)
B. Medir na carga já na U1
X. Other (please specify)

[Answer]: A

## Q2. Volume de testes (NFR7 contra o estado)

Que volume de testes vale para a U1?

A. Vale a Test Strategy que o estado do intent registra, `Standard`, somada aos testes obrigatórios por risco do `team.md` que a U1 toca:
   - isolamento entre contas e 404;
   - separação REAL e PAPER com os modos nunca somados;
   - REAL desligado por padrão;
   - novo aceite dos termos;
   - idempotência da criação.

   Valem também o piso de 80% por parte e os testes de BR1.9 e BR2.4. O `Comprehensive` do NFR7 fica como volume do projeto, como o `team.md` já diz. (recomendado)
B. Comprehensive na U1
X. Other (please specify)

[Answer]: A

## Q3. Segurança

Que requisitos de segurança valem para a U1?

A. Estes:
   - o tenant vem sempre da autenticação, e bot de outra pessoa dá 404;
   - as rotas novas exigem login e o novo aceite dos termos, como as demais;
   - a recusa REAL é auditada fora da transação;
   - nenhum segredo vai para o log ou para `NEXT_PUBLIC_*`;
   - a semente de teste fica só no perfil `test`, com um teste de guarda;
   - nenhum caminho da U1 assina ou envia transação.

   (recomendado)
B. Sem requisito novo
X. Other (please specify)

[Answer]: A

## Q4. Confiabilidade

Como a U1 se comporta quando algo falha?

A. Assim:
   - quando um modo falha, o outro bloco continua, e o bloco que falhou aparece como `UNAVAILABLE`, com `correlationId`;
   - se a fonte da elegibilidade falhar, a criação REAL dá 503 com falha fechada, e a criação PAPER segue;
   - as leituras são idempotentes e podem ser repetidas;
   - a criação usa a `Idempotency-Key`;
   - não há disponibilidade nova além da do monólito.

   (recomendado)
B. Erro inteiro quando um modo falha
X. Other (please specify)

[Answer]: A

## Q5. Observabilidade

O que a U1 passa a medir?

A. Duas métricas novas, sem dado pessoal:
   - as recusas REAL na criação, contadas por motivo;
   - os blocos `UNAVAILABLE`, contados por modo.

   Os logs registram o `correlationId`, nunca o endereço da carteira. Nenhum alarme novo entra na U1. (recomendado)
B. Sem métricas novas
X. Other (please specify)

[Answer]: A

## Q6. Escala e pilha

O que muda na escala e nas tecnologias?

A. Nada muda na escala: a U1 é leitura por pessoa, sobre o monólito atual. A pilha também não muda:
   - Java 21 e Spring Boot;
   - Liquibase, sem migração na U1;
   - Next.js com os tipos gerados do OpenAPI;
   - Jest, JUnit, Testcontainers e ArchUnit;
   - Playwright no spec novo.

   (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Desempenho:** p95 de até 1 s nas leituras novas e na criação, para até 10 bots. Na U1 é medido no teste de integração com dados semeados; o cenário de carga fica para a U2 [Q1].
- **Testes:** Test Strategy `Standard`, mais os testes obrigatórios por risco que a U1 toca, o piso de 80% e os testes de BR1.9 e BR2.4 [Q2].
- **Segurança:** tenant da autenticação e 404 para bot alheio; login e novo aceite; recusa auditada; nenhum segredo em log; semente só no perfil de teste, com guarda; nenhuma assinatura na U1 [Q3].
- **Confiabilidade:** um bloco que falha não derruba o outro; a criação REAL tem falha fechada (503) e a PAPER segue; leituras idempotentes; criação com `Idempotency-Key` [Q4].
- **Observabilidade:** duas métricas novas (recusas REAL por motivo e blocos indisponíveis por modo), sem dado pessoal e sem alarme novo [Q5].
- **Escala e pilha:** nada muda [Q6].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
