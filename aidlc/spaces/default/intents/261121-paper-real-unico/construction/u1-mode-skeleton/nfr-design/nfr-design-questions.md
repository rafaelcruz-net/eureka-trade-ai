# Perguntas do NFR Design — U1 `u1-mode-skeleton`

Conversa: português (Brasil). As respostas seguem a instrução do responsável, "Responda todos com os recomendados"; os pontos críticos continuam com ele.

Fontes:
- `construction/u1-mode-skeleton/nfr-requirements/` (NFR1.1 a NFR8.12);
- `construction/u1-mode-skeleton/functional-design/` (BR1.1 a BR10.5);
- `inception/contract-design/contract-summary.md` (C1 a C4, C8 a C10, C17);
- `inception/domain-design/components.md`.

## Q1. Desempenho do `by-mode`

Como a resposta do `by-mode` fica dentro do p95 de 1 s?

A. O ModePnlView monta os dois blocos lado a lado, cada um com o seu próprio tempo limite. O bloco PAPER usa o P&L PAPER por bot que já existe. O bloco REAL usa o `RealPnlCalculator` filtrado por `bot_id`, com uma consulta por pessoa e índice. Não há cache: os valores precisam ser atuais e exatos. Não há travas de leitura. (recomendado)
B. Cache de 30 s
X. Other (please specify)

[Answer]: A

## Q2. Isolamento de falhas entre os blocos

Como a falha de um bloco fica isolada do outro?

A. Cada bloco roda com um tempo limite próprio, por exemplo 800 ms, abaixo do p95. Quando um bloco passa do limite ou dá erro, ele vira `UNAVAILABLE` com o `correlationId` e a resposta continua 200. Não há circuit breaker novo, porque as duas fontes são o próprio banco. O valor exato do limite fica na configuração e é testado. (recomendado)
B. Circuit breaker por modo
X. Other (please specify)

[Answer]: A

## Q3. Elegibilidade na criação REAL

Como a elegibilidade REAL é conferida na criação?

A. A criação lê a elegibilidade numa leitura curta, dentro do comando idempotente, com um tempo limite. Uma falha ou um tempo esgotado devolve 503 e não grava nada. A recusa de domínio volta como 409, e a auditoria dela é gravada numa transação separada (`REQUIRES_NEW`). Nem a recusa nem o 503 ficam guardados como resposta idempotente. (recomendado)
B. Elegibilidade em cache
X. Other (please specify)

[Answer]: A

## Q4. Segurança

Como a U1 protege os dados e as rotas novas?

A. Usa o que já existe:
   - token de acesso e filtro do novo aceite dos termos nas rotas novas;
   - tenant tirado da autenticação, e as consultas filtram pela pessoa;
   - 404 para bot de outra pessoa;
   - validação da entrada na fronteira (o `market` só aceita PAPER ou REAL);
   - nenhum dado da carteira em log;
   - a semente de teste só no perfil `test`, com um teste de guarda e uma regra ArchUnit ou teste de contexto que confere que a semente não existe no perfil padrão.

   (recomendado)
B. Mecanismo novo
X. Other (please specify)

[Answer]: A

## Q5. Observabilidade

Como a U1 fica observável?

A. Assim:
   - dois contadores Micrometer, `bots_real_create_refusals_total{reason}` e `pnl_mode_block_unavailable_total{market}`;
   - logs estruturados com o `correlationId`, que já existe no MDC;
   - nenhum painel nem alarme novo;
   - o p95 do teste de integração fica só registrado no log.

   (recomendado)
B. Painel novo
X. Other (please specify)

[Answer]: A

## Q6. Componentes lógicos e raio de impacto

Onde a U1 roda e até onde uma falha dela chega?

A. A U1 vive dentro do monólito (API e worker) e do `apps/web`, sem serviço novo. As falhas ficam isoladas assim:
   - uma falha da elegibilidade só afeta a criação REAL;
   - uma falha do bloco REAL só afeta esse bloco;
   - o ciclo REAL passa a ler os limites do bot só no ramo PAPER (BR1.9), e o teste do percurso protege essa mudança.

   (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Desempenho:** os dois blocos são montados lado a lado, cada um com o seu próprio tempo limite. O REAL usa o cálculo atual filtrado por bot, com índice. Não há cache nem trava de leitura [Q1].
- **Isolamento:** cada bloco tem um tempo limite próprio, por exemplo 800 ms, configurável e testado. Quem falha vira `UNAVAILABLE` e a resposta continua 200. Não há circuit breaker novo [Q2].
- **Criação REAL:** a elegibilidade é lida com tempo limite dentro do comando idempotente. Falha devolve 503 e não grava nada. Recusa devolve 409, com a auditoria numa transação separada. Nenhuma das duas fica guardada como resposta [Q3].
- **Segurança:** usa o que já existe (token, novo aceite, tenant da autenticação, 404, validação do `market`, nada da carteira em log). A semente só existe no perfil de teste, com um teste de guarda [Q4].
- **Observabilidade:** dois contadores e logs com `correlationId`. Nenhum painel ou alarme novo; o p95 fica só no log [Q5].
- **Componentes:** tudo dentro do monólito e do app web, sem serviço novo. Uma falha da elegibilidade só afeta a criação REAL, e uma falha do bloco REAL só afeta esse bloco [Q6].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
