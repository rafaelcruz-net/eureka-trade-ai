# Decisões de stack — U5 Bots e decisão

A U5 não traz dependência nova.

| Área | Escolha | Por quê |
|---|---|---|
| Backend | Java 21 e Spring Boot. Módulos `bots` (BotManagement) e `decision` (DecisionEngine) no mesmo código | Monólito modular |
| Processo worker | O mesmo artefato, com o perfil `worker`. Ele liga o agendador do ciclo, a meia-noite, a conferência e o expurgo. A API não roda essas tarefas | C20, ADR-005 |
| Agendamento | `TaskScheduler` próprio do worker, com 4 linhas para ciclos e 2 para tarefas. ShedLock nas tarefas únicas | Q3, NFR10.5 |
| Persistência | PostgreSQL 17, JDBC, migração Liquibase `0010` | Padrão do projeto |
| Valores | `BigDecimal` com `HALF_EVEN`; `numeric` | `project.md` |
| Frontend | Next.js, telas em `apps/web/src/features/bots` e `app/bots`, tipos gerados do OpenAPI | `team.md` |
| Canal ao vivo | Rota SSE no processo de API, aberta com `fetch` em leitura contínua | C7, `project.md` |
| Testes | JUnit 5, Testcontainers, ArchUnit, JaCoCo, Jest, Playwright | `team.md` |

## Qualidade, portabilidade e interface

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR11.1 | Acessibilidade básica | Assistente, lista e tela do bot só com teclado, com foco visível, rótulos e unidades visíveis, erro em texto e cor nunca sozinha; diálogos com foco preso e Esc | NFR11 |
| NFR12.1 | Testes e cobertura | Catálogos D e L antes do código; cobertura de linhas ≥ 80% no backend e no frontend, sem exclusão nova | NFR12 |
| NFR13.1 | Windows e Linux | A suíte passa no Windows local e no Linux da CI; nada depende de fuso ou fim de linha | NFR13 |
| NFR14.1 | pt-BR | Todos os textos das telas em pt-BR; termos de mercado explicados ao passar o mouse e ao receber foco | NFR14 |
