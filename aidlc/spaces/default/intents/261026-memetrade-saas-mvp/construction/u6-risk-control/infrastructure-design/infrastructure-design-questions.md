# Perguntas do desenho de infraestrutura — U6 Barreira de limites

O ambiente continua só local, como nas Units anteriores: Docker Compose no Windows e CI no GitHub Actions. O desenho não funcional está em [../nfr-design/](../nfr-design/), com uma revisão READY que deixou um achado Major e três Minor a resolver aqui, em [review-01.md](../nfr-design/reviews/review-01.md).

Já resolvido sem pergunta (pode corrigir na revisão do resumo):
- **Achado Major (privilégios do expurgo).** Ficam assim:
  - `risk_retention`: `SELECT (evaluation_id)` em `order_rejection`, `SELECT` e `DELETE` em `order_evaluation`, e `INSERT`, `SELECT` e `DELETE` em `risk_purge_run`.
  - Aplicação: só `SELECT` em `risk_purge_run`. A migração revoga o resto e o `TRUNCATE`.
  - `risk_purge_run` é "só inserção, exceto a limpeza das linhas com mais de 90 dias pelo próprio expurgo", com gatilho que recusa `UPDATE`.
  - O teste por papel cobre as quatro tabelas.
- **Minor do relógio.** O corte do expurgo é calculado no SQL com o mesmo `now()` do gatilho, e a seleção pega só linhas com mais de 31 dias. O teste de integração grava as linhas com `evaluated_at` explícito.
- **Minor do preparo dos papéis.** O `10-roles.sql` cria `risk_retention` sem login e o concede só ao login de retenção. Ele revoga o papel do dono e da aplicação, dá `USAGE` no esquema ao grupo, e um teste de preparo confere que a aplicação não é membro.
- **Minor da precedência da BR4.3.** A geração de código registra na rastreabilidade que a BR4.3 do `security-design.md` prevalece sobre a do `rules.md`. O aviso do 404 depois do expurgo está no contrato C21 de `logical-components.md`.
- **Correção.** As migrações do projeto usam Liquibase, não Flyway como dizia `tech-stack-decisions.md`. A U6 ganha a migração `0009-risk-control.sql`, que só acrescenta.
- **Nenhum serviço novo no Compose.** A barreira roda no worker, quando a U5 o criar, e a rota e o expurgo rodam na API.
- **Expurgo na conexão de retenção.** Usa a conexão que a U2 já tem para a trilha (pool de 1), às 03:30 UTC, depois da purga da trilha às 03:00.
- **Cenário de carga `u6`.** É acrescentado ao `scripts/load-test.sh`, com um ciclo de teste do perfil `loadtest`, como a U7 fez.
- **Sem segredo novo.**

## Q1. [CI/CD] O smoke de ponta a ponta tem um teste da U6?

A U6 não tem tela. A única parte visível por fora é a rota de recusas (C21), que só terá recusas de verdade quando a U5 criar o ciclo dos bots.

A. Um smoke Playwright da U6 (`e2e/u6-risk-control`), só pela API, entra com a conta de smoke da U1 e confere que a rota de recusas de um bot nunca avaliado responde 404 sem dado. Ele entra no `verify.sh` por ação do responsável do projeto, como nas Units anteriores.
B. Sem smoke da U6 agora. A rota e a barreira ficam cobertas pelos testes de integração do backend, e o fluxo com recusas entra no smoke da U5.
X. Other (please specify)

[Answer]: B

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
