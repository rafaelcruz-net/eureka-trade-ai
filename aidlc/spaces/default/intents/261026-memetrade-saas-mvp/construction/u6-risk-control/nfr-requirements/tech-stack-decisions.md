# Decisões de stack — U6 Barreira de limites

A U6 não traz dependência nova. Tudo abaixo já está no projeto (U1 a U7), conforme o `team.md` e as decisões das Units anteriores.

| Área | Escolha | Por quê |
|---|---|---|
| Linguagem e framework | Java 21 e Spring Boot, módulo `com.memetrade.riskcontrol` no mesmo código do API e do worker | Monólito modular do projeto; a barreira roda na transação do ciclo da U5 |
| Valores | `BigDecimal` com escala e `RoundingMode.HALF_EVEN` explícitos; colunas `numeric` | Decimal exato (`project.md`); mesma convenção da U7 |
| Persistência | PostgreSQL 17 com JDBC, inserção com `ON CONFLICT DO NOTHING`, gatilhos e privilégios para a imutabilidade | Unicidade sem abortar a transação (BR2.8) e registros imutáveis (NFR4.3) |
| Migrações | Flyway, padrão expand-contract | API e worker podem rodar versões vizinhas (C20) |
| Tarefa de expurgo | Agendador do Spring com ShedLock | Execução única, padrão da U2 e da U4 |
| Auditoria | Interface publicadora da trilha com tabela de saída (C9) | Evento na mesma transação |
| Contrato REST | OpenAPI gerado do código e tipos do frontend gerados dele | Regra do `team.md` |
| Testes | JUnit 5, AssertJ, Testcontainers com PostgreSQL real, ArchUnit, JaCoCo (80% de linhas) | Postura de testes do `team.md`; TDD no núcleo financeiro |
| Teste de carga | Script de carga local do projeto (`scripts/load-test.sh`), com um cenário `u6` | Mede NFR2.1 e NFR3.1 |

## Qualidade e portabilidade

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR12.1 | Testes antes do código e cobertura | Catálogo E1 a E21 e testes de risco escritos antes da implementação; cobertura de linhas do backend ≥ 80% com a U6 incluída, sem exclusão nova para o pacote `riskcontrol` | NFR12, `team.md` |
| NFR13.1 | Mesmo resultado no Windows e no Linux | A suíte da U6 passa no Windows local e no Linux da CI; nenhum teste depende de fuso, local ou fim de linha | NFR13 |

## Rejeitado

| Alternativa | Motivo |
|---|---|
| Cache dos limites ou da visão de risco | Resultados poderiam usar dado velho; a avaliação lê tudo uma vez por chamada (NFR10.5) |
| Biblioteca de regras (motor de regras) | Onze verificações fixas não justificam a dependência; o código é mais fácil de testar antes |
| Chave comum de idempotência da identidade | A C14 é chamada interna sem `Idempotency-Key`; a chave natural é a decisão (BR2.8) |
