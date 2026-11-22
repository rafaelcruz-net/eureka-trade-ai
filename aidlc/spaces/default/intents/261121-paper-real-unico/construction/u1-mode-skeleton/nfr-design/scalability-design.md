# Desenho de escala — U1 `u1-mode-skeleton`

Conversa: português (Brasil). Data: 2026-11-21.

A U1 não muda a escala do sistema. Ela roda dentro do monólito atual (API e worker no mesmo app Spring Boot, um PostgreSQL) e do `apps/web`, sem serviço, réplica, fila ou cache novos [Q6]. Este arquivo fixa como as rotas da U1 dividem os recursos que já existem, para que nenhuma delas tire capacidade das outras.

## Arquitetura de escala

| Aspecto | Decisão | NFR |
|---|---|---|
| Processos | As rotas C1 a C4 rodam no processo da API que já existe e escalam com ele (tarefas do ECS na nuvem, um contêiner no local). O worker só muda no ciclo (BR1.9), sem custo novo. | NFR1.11, NFR8.1 |
| Estado | Sem estado em memória entre pedidos. O pool dos blocos de C4 guarda só threads; nada de sessão, cache ou afinidade. | NFR1.11 |
| Dados | 0 tabelas, 0 colunas e 0 migrações. As gravações são as de hoje: o bot, o registro de idempotência e o evento de auditoria. | NFR1.13 |
| Volume de referência | Até 10 bots por pessoa e 2 anos de ordens do próprio bot. Acima disso, nenhuma meta da U1 é prometida. | NFR1.10 |
| Custo | Nenhum recurso AWS novo e nenhum custo recorrente; o orçamento de US$ 350 por conta não muda. | NFR8.1, NFR8.2 |

## Divisão das conexões do banco

O processo da API tem um pool Hikari de 20 conexões (`DB_POOL_MAX_SIZE`, entre 10 e 50), tamanho fixo, com espera de 2 s por conexão. A U1 usa esse pool assim:

| Caminho | Conexões por pedido | Teto no processo | Por quê |
|---|---|---|---|
| C4 (`by-mode`) | até 2, uma por bloco, nas threads do pool `pnl-mode-block`; a thread do pedido não segura conexão | 8 (`memetrade.pnl.by-mode.pool-size`) | o pool dos blocos é a antepara (bulkhead) que impede muitos pedidos de C4 ao mesmo tempo de tomarem as 20 conexões |
| C1 PAPER | 1 (a transação do comando) | o das threads HTTP, como hoje | sem leitura de elegibilidade |
| C1 REAL | 1; a elegibilidade usa um ponto de salvamento na **mesma** conexão do comando | o das threads HTTP | nenhuma conexão extra dentro do comando |
| Auditoria da recusa REAL | 1, depois que a conexão do comando já voltou ao pool | sequencial | nunca duas conexões ao mesmo tempo no mesmo pedido |
| C2, C3 | 1 (transação só de leitura curta) | o das threads HTTP | leituras pontuais com prazo de 500 ms |

Pior caso de C4 com o pool cheio: 8 conexões ocupadas pelos blocos e 12 livres para o resto da API.

## Contrapressão

- **Fila limitada.** O pool `pnl-mode-block` tem fila de `memetrade.pnl.by-mode.queue-capacity` (16). Com a fila cheia, o envio é recusado na hora e o bloco volta `UNAVAILABLE`, com resposta 200. Um pico de C4 nunca cria fila sem limite nem espera longa.
- **O prazo conta a espera.** O prazo de 800 ms começa no envio ao pool. Uma tarefa que esperou na fila ou pela conexão (o Hikari espera até 2 s) e começa depois do prazo desiste sem ler nada.
- **Sem tentativa automática no servidor.** Um bloco `UNAVAILABLE` só é pedido de novo quando a pessoa usa "Tentar de novo". O servidor não repete leituras, e não existe tempestade de repetições.
- **Comandos.** A criação continua com o `lock_timeout` de 2 s dos comandos (`memetrade.bots.lock-timeout`). A elegibilidade tem prazo próprio de 500 ms, então a reivindicação da chave fica presa no máximo esse tempo a mais.

## Concorrência da criação (NFR1.12)

- O mecanismo comum de idempotência (`IdempotencyService`, pela interface `IdempotencyGuard`) reivindica `(escopo, chave)` dentro da transação do comando.
- Um segundo pedido simultâneo com a mesma chave espera, no PostgreSQL, o fim da transação do primeiro, limitado pelo `lock_timeout` de 2 s. Depois:
  - se o primeiro terminou, o segundo devolve a resposta guardada (201 com o mesmo corpo);
  - se o primeiro ainda estiver em andamento, o segundo responde 409 `IDEMPOTENCY_CONFLICT` com `reason` `IN_PROGRESS`.
- Uma recusa (409 `REAL_NOT_ELIGIBLE`) ou um 503 desfazem a reivindicação. O pedido simultâneo seguinte reivindica a chave de novo e reavalia (NFR4.9).
- **Teste.** O teste de integração do NFR1.12 dispara 10 pedidos simultâneos (PAPER, mesma chave, mesmo corpo) com um `CountDownLatch` e confere:
  - exatamente 1 bot criado;
  - cada resposta é ou 201 com o corpo do bot criado, ou 409 `IDEMPOTENCY_CONFLICT`/`IN_PROGRESS`;
  - pelo menos uma resposta é 201;
  - qualquer outra saída falha o teste.
- **Variante REAL**, acrescentada por este estágio: com a pessoa elegível, o mesmo teste com `market = REAL` confere exatamente 1 bot REAL. Ela prova que a leitura da elegibilidade dentro do comando não abre uma janela de duplicação.

## Crescimento de dados (NFR1.13)

| Gravação | Tabela | Retenção | Novo na U1? |
|---|---|---|---|
| Bot criado (C1) | `bot` | a de hoje | não |
| Registro de idempotência | a tabela do mecanismo comum (`0002-idempotency.sql`) | `memetrade.idempotency` (a de hoje) | não; uma recusa ou um 503 não deixam registro |
| Auditoria (`BOT_CREATED`, recusa REAL) | outbox e trilha de auditoria | a política de retenção da auditoria | só a ação nova; nenhuma tabela nova |

As leituras C2, C3 e C4 não gravam nada (NFR4.8). O volume cresce só com o uso de hoje.

## Limites de capacidade e o que vem depois

- O custo de C4 cresce com o histórico da pessoa: o bloco REAL lê todas as ordens assentadas dela. Na referência (NFR1.10), são 14.600 ordens REAL.
- Acima da referência, a U1 não promete meta. A leitura em fluxo com o índice da tabela `real_entry_slot` e o cenário de carga com portão são da U2 (C12, C14, PQ-10, NFR1.6).
- Como a U1 nunca vai para um ambiente com usuários sem a U2 (NFR8.4), nenhum usuário roda o cálculo da U1 em volume real.
- Não há gatilho de autoescala novo. A escala da API continua a das definições de tarefa do ECS (`infra/terraform/`), que a U1 não muda.

## Ambientes (NFR8.3, NFR8.4)

- Os usuários usam só a mainnet. Nenhuma rota, tela ou configuração da U1 abre a devnet a uma conta de usuário.
- A U1 é verificada no local e na CI, no ponto de controle do esqueleto (`bash scripts/verify.sh`).
- Ela só vai para a mainnet num SHA que já contém a U2 com o Build and Test aprovado. O fundador pode implantá-la na devnet, que não tem usuários, para os próprios testes.

## Sources

- `nfr-design-questions.md`: Q1, Q2, Q3 e Q6, com o resumo confirmado [Q1] [Q2] [Q3] [Q6].
- `construction/u1-mode-skeleton/nfr-requirements/scalability-requirements.md`: NFR1.10 a NFR1.13, NFR8.1 a NFR8.4.
- `construction/u1-mode-skeleton/nfr-requirements/reliability-requirements.md`: NFR4.8, NFR4.9.
- `construction/u1-mode-skeleton/functional-design/rules.md`: BR1.4, BR10.3, BR10.4.
- `inception/contract-design/contract-summary.md`: C1, C4, PQ-10.
- Código lido:
  - `application.yml` (Hikari com 20 conexões de tamanho fixo e `connection-timeout` de 2 s, `memetrade.bots.lock-timeout`);
  - `identity/domain/idempotency/IdempotencyService.java` (reivindicação, repetição, `IN_PROGRESS`, liberação na falha);
  - `bots/application/BotCommandService.java` (`command`);
  - `administration/infra/AdministrationConfig.java` (pool próprio fora do executor do Spring).
- `aidlc/spaces/default/memory/team.md`: Deployment (contas, orçamento, devnet).

## Assumptions & Open Questions

1. [assumption] O teto de 8 conexões para os blocos de C4 deixa 12 das 20 conexões para o resto da API. Se a medição do NFR1.5 ou o cenário da U2 mostrarem disputa, o ajuste é em `memetrade.pnl.by-mode.pool-size` ou em `DB_POOL_MAX_SIZE`, sem mudar o desenho.
2. [assumption] A variante REAL do teste de concorrência é um acréscimo deste estágio ao NFR1.12. Ela não muda o critério do requisito; só cobre o caminho que lê a elegibilidade.
