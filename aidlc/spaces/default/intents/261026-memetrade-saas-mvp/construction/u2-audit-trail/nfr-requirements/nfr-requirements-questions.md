# Perguntas — NFR Requirements da U2 (trilha de auditoria)

Objetivo: fixar metas mensuráveis e escolhas técnicas que a U2 precisa para ser construída, a partir do design funcional ([functional-spec.md](../functional-design/functional-spec.md), [rules.md](../functional-design/rules.md), [entities.md](../functional-design/entities.md)), dos requisitos FR2.3, FR3.3, NFR4, NFR7 e NFR9 de [requirements.md](../../../inception/requirements-analysis/requirements.md) e do contrato C9 de [contract-summary.md](../../../inception/contract-design/contract-summary.md).

Já decidido e que não volta a ser perguntado: a stack e as ferramentas de base da U1 (Java 21 com Spring Boot, PostgreSQL, Liquibase, JUnit 5 com Testcontainers, JaCoCo com piso de 80%); relay da auditoria a cada 1 s em lotes de até 100 com reserva de linhas e desduplicação por `event_id` (NFR Design da U1); saúde da trilha degradada quando a pendência mais antiga passa de 60 s (NFR Design da U1); logs JSON com `correlationId`, métricas expostas localmente e nenhum rastreamento distribuído (U1); nenhuma meta de disponibilidade enquanto o beta roda só localmente (NFR10); retenção de 12 meses, expurgo diário em lotes de até 1000 com papel de banco separado, limpeza da tabela de saída entregue há mais de 7 dias, consulta só pelo administrador com período de até 31 dias e varredura de logs, métricas e eventos (design funcional da U2, Q1 a Q9).

## Q1. [Arquitetura] Para que volume de eventos a trilha deve ser dimensionada?

Cada ordem recusada é uma ação sensível com exatamente um evento (FR2.3). Com 60 bots ativos (NFR2) e um ciclo por minuto, o pior caso passa de 86 mil recusas por dia, somadas a logins, comandos de bot e ações administrativas. Com retenção de 12 meses, isso dá dezenas de milhões de linhas, e o volume decide índices, metas da consulta e forma do expurgo.

- A. Até 100 mil eventos por dia (cerca de 37 milhões em 12 meses) numa tabela única, com índice por (`occurred_at` decrescente, `event_id` decrescente) e índice por (`action`, `occurred_at`); particionamento fica para quando houver hospedagem
- B. Até 100 mil eventos por dia, com a trilha particionada por mês desde já (o expurgo passa a descartar partições inteiras em vez de apagar em lotes, o que muda a regra BR3.2 do design funcional)
- X. Other (please specify)

[Answer]: A

## Q2. [Desempenho] Qual meta de tempo de resposta vale para a consulta da trilha pelo administrador?

A rota `GET /api/v1/admin/audit-events` não tem tela, é de uso raro e lê um período de até 31 dias, mais recentes primeiro, paginado por cursor (BR2.2, BR2.3). A U1 usa p95 até 300 ms nas rotas do convidado.

- A. p95 até 500 ms por página de até 100 itens, com período de 31 dias, filtro por ação ou sem filtro, e a trilha cheia no volume da Q1 (12 meses), medido no ambiente local
- B. p95 até 2 s por página, nas mesmas condições (uso administrativo raro, sem tela)
- C. Sem meta própria; só o tempo limite do cliente de 10 s das regras comuns da API
- X. Other (please specify)

[Answer]: B

## Q3. [Confiabilidade] Em quanto tempo um evento confirmado deve chegar à trilha?

O relay roda a cada 1 s, e a saúde da trilha fica degradada quando a pendência mais antiga passa de 60 s (U1). Falta uma meta de entrega que diga quando o atraso já é um problema, mesmo antes da saúde degradar.

- A. 99% dos eventos na trilha em até 5 s depois do commit da ação, sob a carga da Q1; saúde degradada acima de 60 s, como na U1
- B. 99% dos eventos na trilha em até 60 s; o mesmo limite da saúde
- C. Sem meta de entrega; só a saúde degradada acima de 60 s
- X. Other (please specify)

[Answer]: A

## Q4. [Operações] Quando o expurgo e a limpeza rodam, e quando a saúde da trilha acusa que pararam?

O beta roda só no computador local no Windows, que pode estar desligado no horário marcado. O design funcional diz que uma falha deixa a saúde degradada até a próxima execução bem-sucedida, mas não diz o horário, o tempo máximo nem o que acontece quando a execução não ocorre.

- A. Expurgo às 03:00 UTC e limpeza da tabela de saída às 03:30 UTC, cada um com limite de 30 minutos por execução; ao subir o processo, roda logo se a última execução bem-sucedida tiver mais de 24 h; saúde degradada se a última execução falhou ou se a última bem-sucedida tiver mais de 26 h
- B. Mesmo horário e limite, sem execução ao subir; saúde degradada só quando a última execução falhou
- X. Other (please specify)

[Answer]: A

## Q5. [Plataforma] Como garantir que só uma execução do expurgo e da limpeza rode por vez?

BR3.2 diz que uma execução em andamento impede outra. Hoje há um único processo de API, mas a escala prevê duas ou mais instâncias antes do beta multiusuário.

- A. Bloqueio consultivo do PostgreSQL obtido no início de cada execução (sem biblioteca nova); funciona com uma ou várias instâncias
- B. Biblioteca de bloqueio de tarefas agendadas com tabela própria no banco (por exemplo ShedLock)
- C. Trava na memória do processo agora; trocar quando houver a segunda instância
- X. Other (please specify)

[Answer]: B

## Q6. [Segurança] Como o expurgo obtém o papel de banco de retenção?

BR3.2 exige um papel de banco separado, que só apaga eventos anteriores ao corte e não pode inserir nem alterar. A forma de chegar a esse papel decide se o resto da aplicação também consegue apagar eventos.

- A. Usuário de banco próprio para a retenção, com senha própria no `.env`, só com leitura e exclusão em `audit_event`, num pool de conexões separado de 1 conexão usado só pelo expurgo; o usuário da aplicação não pode assumir esse papel
- B. O usuário da aplicação recebe o papel de retenção e o assume só durante o expurgo (uma credencial só, mas qualquer código da aplicação poderia assumi-lo)
- X. Other (please specify)

[Answer]: A

## Q7. [Segurança] A trilha precisa de prova de adulteração além do bloqueio de alteração?

Hoje a trilha é protegida por um gatilho que recusa alteração e exclusão (U1) e, com a U2, por papéis de banco separados. Quem tiver acesso de administrador ao próprio banco ainda poderia mudar eventos sem deixar rastro.

- A. Sem prova criptográfica no beta: gatilho, papéis separados e usuário da aplicação sem permissão de alterar a estrutura do banco; a cadeia de resumos fica como candidata para o G1
- B. Cadeia de resumos desde já: cada evento guarda o resumo SHA-256 do anterior, na ordem de gravação; uma verificação diária confere a cadeia, e o expurgo registra em `AUDIT_RETENTION_PURGED` o último resumo apagado
- X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Volume: a trilha é dimensionada para até 100 mil eventos por dia (cerca de 37 milhões em 12 meses) numa tabela única, com índice por (`occurred_at` decrescente, `event_id` decrescente) e por (`action`, `occurred_at`); particionamento só quando houver hospedagem (Q1).
- Consulta do administrador: p95 até 2 s por página de até 100 itens, com período de 31 dias, com ou sem filtro por ação e a trilha cheia no volume da Q1, medido no ambiente local (Q2).
- Entrega: 99% dos eventos na trilha em até 5 s depois do commit da ação, sob a carga da Q1; saúde degradada acima de 60 s, como na U1 (Q3).
- Agenda: expurgo às 03:00 UTC e limpeza da tabela de saída às 03:30 UTC, com limite de 30 minutos cada; ao subir o processo, roda logo se a última execução bem-sucedida tiver mais de 24 h; saúde degradada se a última execução falhou ou se a última bem-sucedida tiver mais de 26 h (Q4).
- Execução única: biblioteca de bloqueio de tarefas agendadas com tabela própria no banco (ShedLock), válida para uma ou várias instâncias (Q5).
- Papel de retenção: usuário de banco próprio, com senha própria no `.env`, só leitura e exclusão em `audit_event`, num pool separado de 1 conexão usado só pelo expurgo; o usuário da aplicação não pode assumir esse papel (Q6).
- Adulteração: sem prova criptográfica no beta; gatilho append-only, papéis separados e usuário da aplicação sem permissão de alterar a estrutura do banco; a cadeia de resumos fica como candidata para o G1 (Q7).

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
