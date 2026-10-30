# Perguntas — NFR Design da U2 (trilha de auditoria)

Objetivo: escolher os padrões que cumprem os requisitos não funcionais da U2 ([performance-requirements.md](../nfr-requirements/performance-requirements.md), [security-requirements.md](../nfr-requirements/security-requirements.md), [scalability-requirements.md](../nfr-requirements/scalability-requirements.md), [reliability-requirements.md](../nfr-requirements/reliability-requirements.md), [observability-requirements.md](../nfr-requirements/observability-requirements.md), [tech-stack-decisions.md](../nfr-requirements/tech-stack-decisions.md)), a partir dos fluxos de [functional-spec.md](../functional-design/functional-spec.md) e do contrato C9 de [contract-summary.md](../../../inception/contract-design/contract-summary.md).

Já decidido e que não volta a ser perguntado: tabela única sem particionamento (Q1 dos requisitos); consulta com p95 até 2 s; entrega de 99% em até 5 s; agenda às 03:00 e 03:30 UTC com limite de 30 minutos e recuperação ao subir; ShedLock; usuário de retenção próprio com pool de 1 conexão; nenhuma prova criptográfica no beta; base da U1 (relay a cada 1 s em lotes de 100 com reserva de linhas, gatilho append-only em `audit_event`, ArchUnit, métricas Prometheus na porta de gestão). A revisão dos requisitos apontou lacunas que este estágio fecha: onde rodam as migrações (Q1), amplificação da trilha (Q2), logs do PostgreSQL (Q3), estado inicial das tarefas (Q4) e o instante de medida da entrega (Q5). Os demais pontos da revisão (índice do filtro com `event_id`, transação de leitura e escrita na consulta, carga concorrente na medição, mecanismo do teste do contexto de administrador) são resolvidos direto no desenho, sem escolha a fazer.

## Q1. [Segurança] Onde rodam as migrações do banco?

Hoje o Liquibase roda dentro do processo da API. Com a U2, as migrações usam o usuário dono do esquema, que pode desligar o gatilho da trilha. Se essa credencial estiver no ambiente da API, uma API comprometida consegue apagar ou alterar eventos, e a separação de NFR6.3 fica só no papel.

- A. Em um serviço próprio de migração no Docker Compose (o mesmo artefato da API em modo só migração), que roda o Liquibase com o usuário dono e termina; a API só sobe depois que ele termina com sucesso e recebe apenas as credenciais de aplicação e de retenção. Na CI e nos testes de integração, as migrações rodam com o usuário dono e a aplicação conecta com o usuário de aplicação. Um teste confere que a configuração da API não tem a credencial do dono
- B. Liquibase continua dentro da API com a credencial do dono no beta local, registrado como risco aceito ao lado de NFR6.9 e corrigido antes do G1
- X. Other (please specify)

[Answer]: A

## Q2. [Segurança] Como impedir que chamadas repetidas encham a trilha?

Cada `403` em rota administrativa gera um `REQUEST_FORBIDDEN`, e cada consulta gera um `AUDIT_QUERIED`. A trilha só se apaga depois de 12 meses, então um convidado que repete chamadas proibidas pode passar do teto de 100 mil eventos por dia.

- A. Limite de taxa por pessoa autenticada nas rotas administrativas: 30 requisições por minuto. Acima do limite, a resposta é `429` com `Retry-After`, sem evento novo, e só uma métrica conta as recusas. Mais uma métrica de eventos por dia, com alerta ao passar de 80% do teto
- B. Agregação: o primeiro `REQUEST_FORBIDDEN` de cada pessoa e rota numa janela de 1 minuto é gravado; os seguintes viram um único evento de resumo com a contagem no fim da janela. Mais a mesma métrica e alerta de volume diário
- C. Só a métrica e o alerta de volume diário, sem limite nem agregação
- X. Other (please specify)

[Answer]: B

## Q3. [Segurança] Como evitar que os logs do PostgreSQL guardem o endereço completo?

O banco pode registrar instruções, parâmetros e a linha que falhou numa restrição, o que copiaria `details` (com o endereço completo) para fora da trilha. A varredura de NFR7.3 hoje lê só os logs da aplicação.

- A. Configurar o PostgreSQL no Docker Compose e nos testes para não registrar instruções nem parâmetros, com mensagens de erro curtas (sem a linha que falhou), e incluir os logs do contêiner do banco na varredura
- B. Só incluir os logs do contêiner do banco na varredura e mudar a configuração se ela achar algo
- X. Other (please specify)

[Answer]: A

## Q4. [Confiabilidade] Onde fica a última execução de cada tarefa, e qual o estado no primeiro start?

A recuperação ao subir e a saúde precisam do horário da última execução bem-sucedida do expurgo e da limpeza, que sobreviva a reinícios. Num banco novo (primeiro start, CI, testes) ainda não existe execução anterior.

- A. Tabela própria do AuditTrail com uma linha por tarefa (última execução bem-sucedida, último resultado e horário); sem linha, a tarefa roda ao subir e a saúde fica degradada até a primeira execução bem-sucedida
- B. Mesma tabela, mas sem linha a tarefa espera o horário marcado e a saúde nasce saudável
- X. Other (please specify)

[Answer]: A

## Q5. [Observabilidade] A partir de qual instante se mede a entrega à trilha?

A meta de 5 s vale "depois do commit da ação". O instante de inserção na tabela de saída acontece antes do commit e inclui o resto da ação; o instante real do commit só fica disponível no PostgreSQL ligando o registro de horário de commit.

- A. Ligar o registro de horário de commit no PostgreSQL; o relay mede do commit real até a gravação na trilha
- B. Medir do instante da inserção na tabela de saída (relógio no momento da inserção), aceitando que a medida inclui o resto da ação
- X. Other (please specify)

[Answer]: A

## Q6. [Segurança] Onde fica a contagem da agregação de `REQUEST_FORBIDDEN`?

Na Q2 você escolheu a agregação: o primeiro `403` de cada pessoa e rota numa janela de 1 minuto vira evento, e os seguintes viram um evento de resumo com a contagem no fim da janela. Falta decidir onde a contagem fica até o resumo ser gravado. Isso decide se uma queda da API perde recusas da trilha e se a contagem funciona com várias instâncias.

- A. Na memória do processo de API; o resumo também é gravado no desligamento ordenado. Uma queda abrupta perde até 1 minuto de contagem, o que fica registrado como risco aceito no beta local. Com várias instâncias, cada uma grava o seu resumo
- B. No banco: uma tabela de contadores por pessoa, rota e janela, atualizada na mesma transação de cada recusa; uma tarefa a cada minuto publica os resumos das janelas fechadas e apaga as linhas. Nada se perde e funciona com várias instâncias, ao custo de uma gravação por recusa
- X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Migrações em um serviço próprio no Docker Compose (o mesmo artefato da API em modo só migração), com o usuário dono do esquema; a API só sobe depois que ele termina com sucesso e recebe apenas as credenciais de aplicação e de retenção; na CI e nos testes de integração as migrações rodam com o usuário dono e a aplicação conecta com o usuário de aplicação; um teste confere que a configuração da API não tem a credencial do dono (Q1).
- Amplificação: o primeiro `REQUEST_FORBIDDEN` de cada pessoa e rota numa janela de 1 minuto é gravado; os seguintes viram um único evento de resumo com a contagem no fim da janela; métrica de eventos por dia com alerta acima de 80% do teto de 100 mil (Q2).
- A contagem da agregação fica na memória do processo de API; o resumo também é gravado no desligamento ordenado; uma queda abrupta perde até 1 minuto de contagem, registrado como risco aceito no beta local; com várias instâncias, cada uma grava o seu resumo (Q6).
- PostgreSQL no Docker Compose e nos testes sem log de instruções nem de parâmetros e com mensagens de erro curtas, sem a linha que falhou; os logs do contêiner do banco entram na varredura (Q3).
- Tabela própria do AuditTrail com uma linha por tarefa (última execução bem-sucedida, último resultado e horário); sem linha, a tarefa roda ao subir e a saúde fica degradada até a primeira execução bem-sucedida (Q4).
- Registro de horário de commit ligado no PostgreSQL; o relay mede a entrega do commit real até a gravação na trilha (Q5).

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
