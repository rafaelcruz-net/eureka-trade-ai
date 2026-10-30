# Decisões de tecnologia — U2 Trilha de auditoria

Tecnologias que a Unit `u2-audit-trail` acrescenta à base da U1 para implementar os fluxos de [functional-spec.md](../functional-design/functional-spec.md) e as regras de [rules.md](../functional-design/rules.md). A stack principal vem das restrições de [requirements.md](../../../inception/requirements-analysis/requirements.md) e das decisões da U1; a forma da tabela de saída e do publicador segue o contrato C9 de [contract-summary.md](../../../inception/contract-design/contract-summary.md). As escolhas novas vêm das respostas Q1, Q4, Q5, Q6 e Q7 de [nfr-requirements-questions.md](nfr-requirements-questions.md). Versões exatas são fixadas no `pom.xml` na geração de código.

## Stack

| Assunto | Escolha | Por quê | Alternativa rejeitada |
|---|---|---|---|
| Base | Java 21, Spring Boot, PostgreSQL, Liquibase (U1) | Já estabelecida | — |
| Forma da trilha | Tabela única `audit_event`, sem particionamento, com índice por (`occurred_at` decrescente, `event_id` decrescente) e por (`action`, `occurred_at`) | Volume de até 100 mil eventos por dia atende às metas sem particionar (Q1) | Particionamento mensal desde já, que mudaria a regra BR3.2 |
| Execução única das tarefas | ShedLock com provedor JDBC e tabela própria criada por migração Liquibase | Funciona com uma ou várias instâncias e registra quem detém o bloqueio (Q5) | Bloqueio consultivo do PostgreSQL; trava em memória |
| Agendamento | Agendador do próprio Spring no processo de API, com horários em UTC explícitos (03:00 e 03:30) | Mesmo agendador do relay da U1; UTC independente do fuso da máquina (NFR13.1) | Agendamento no sistema operacional ou no contêiner do banco |
| Papel de retenção | Login de banco próprio com senha no `.env` e um segundo pool de conexões de tamanho 1, usado só pelo expurgo | A aplicação não consegue apagar eventos nem assumir o papel (Q6) | Papel assumido pelo usuário da aplicação |
| Migrações | Liquibase com um usuário de migração dono do esquema; a aplicação conecta com um usuário só de dados | A aplicação não pode alterar a estrutura nem desligar o gatilho (Q7) | Migrações com o usuário da aplicação |
| Prova de adulteração | Nenhuma no beta; gatilho e papéis separados | Decisão da Q7; cadeia de resumos SHA-256 é candidata no G1 | Cadeia de resumos desde já |
| Consulta | SQL parametrizado com busca a partir do cursor (`occurred_at`, `event_id`), sem `OFFSET` | Tempo estável em qualquer página (NFR3.1) | Paginação por deslocamento |

## Qualidade e segurança

| Assunto | Escolha | Origem |
|---|---|---|
| Testes do backend | JUnit 5 e Testcontainers com PostgreSQL real, usando as mesmas migrações e os três usuários de banco (migração, aplicação, retenção) | `team.md`, NFR6.2, NFR6.3 |
| Varredura de logs, métricas e eventos | Extensão de teste que, ao fim da suíte, lê os logs capturados, a saída de métricas e as tabelas `audit_outbox` e `audit_event` | BR4.2, NFR7.3 |
| Carga sintética | Script de semeadura da trilha no volume da Q1, usado só no teste de desempenho | NFR3.1 |
| Portões da CI | CodeQL, OWASP Dependency-Check e Gitleaks, como na U1, cobrindo ShedLock | NFR6.8 |

## Requisitos de qualidade

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR12.1 | Piso de cobertura | 80% de linhas no backend, com as exclusões listadas na configuração do JaCoCo | NFR12, `team.md` |
| NFR12.2 | Testes obrigatórios por risco que a U2 toca | Trilha sem edição (`UPDATE` e `DELETE` fora do expurgo falham); exatamente um evento por ação; nenhum segredo, token, nonce ou assinatura no evento; varredura de logs, métricas e eventos; convidado recebe `403` auditado na rota da trilha; restrições dos usuários de retenção e de aplicação; fronteira do corte de 12 meses; limpeza nunca apaga pendentes | NFR12, `team.md` |
| NFR12.3 | Testes determinísticos | Relógio injetável para o corte, a agenda e a recuperação ao subir; o gatilho do banco, que usa o relógio do banco, é testado com eventos de 13 e de 11 meses, longe da fronteira; nenhuma rede real | `team.md`, NFR13.1 |

A U2 não faz parte do núcleo financeiro, então segue a ordem test-after da `Testing Posture` do `team.md`.

## Pendências para o NFR Design

- Tempo máximo e mínimo de bloqueio do ShedLock para o expurgo e a limpeza, coerentes com o limite de 30 minutos.
- Onde fica o horário da última execução bem-sucedida de cada tarefa, para sobreviver a reinícios (NFR4.5).
- Definição exata dos índices e do plano de consulta, conferidos com a carga sintética.
- Configuração dos três usuários de banco no Docker Compose e no `.env.example`.
