# Estratégia de implantação

Conversa: português (Brasil). Respostas 1A a 5A de `deployment-pipeline-questions.md`. Vale a estratégia do intent anterior (`261026-memetrade-saas-mvp/operation/deployment-pipeline/deployment-strategy.md`): recreate local hoje, blue/green na AWS no futuro, migrações só de expand. Este documento registra o que as Units U1 a U5 deste intent mudam.

## Componentes e ordem de subida

Igual ao intent anterior: `postgres` saudável → `db-bootstrap` (papéis, inclusive o papel novo da U5) → `db-migrate` (migrações `0012` a `0015`) → `api` saudável → `worker`, `web`, `admin`. A `api` e o `worker` só sobem com `MEMETRADE_AUDIT_WALLET_HMAC_KEY` (o Compose recusa sem ela). Na subida, a `api` aquece o caminho da consulta da trava de novo aceite (`TermsReacceptanceWarmup`, até cerca de 2 a 4 s antes de ficar pronta).

## Migrações deste intent

| Migração | Unit | Natureza | Volta da imagem anterior |
|---|---|---|---|
| `0012-retention` | U2 | expand (coluna `wallet_hmac` e índices) | sim |
| `0013-reacceptance` | U3 | relaxa `invite_id` para nulo; coluna `source` com padrão; restrição única | sim; a migração não é desfeita depois do primeiro novo aceite |
| `0014-data-requests` | U4 | expand (tabelas novas) | sim; as tabelas ficam e são ignoradas; o rollback do Liquibase só antes do primeiro pedido real |
| `0015-anonymization` | U5 | relaxa `NOT NULL` de `wallet.address` e `wallet.provider`; papel e função de apagar feedback | **só antes da primeira anonimização**; depois dela, só correção para frente |

`MigrationsBackwardCompatIT` prova que a imagem anterior funciona sobre o esquema novo sem conta anonimizada.

## Conteúdo legal

- O catálogo de termos e da política vai **dentro da imagem**. Publicar uma versão nova é uma release (resposta 4A): pasta nova revisada, checklist de `docs/api/u1-legal-documents.md`, campos de implantação preenchidos (armazenamento, transferência, encarregado), e só então a versão vira vigente.
- Uma versão vigente nova ativa a trava de novo aceite para todos os convidados. Os bots PAPER ativos continuam rodando durante a trava.
- O rollback da imagem traz junto o texto anterior.

## Pool de conexões

A `api` mantém um pool fixo de `DB_POOL_MAX_SIZE` conexões (padrão 20, aceito de 10 a 50). O orçamento do PostgreSQL em `docker-compose.yml` já conta as 20 da `api`. Na AWS, com mais de uma réplica da `api`, o orçamento é por réplica × `DB_POOL_MAX_SIZE`, e precisa caber no `max_connections` do banco gerenciado (Environment Provisioning).

## Portões de promoção

Iguais ao intent anterior (resposta 2A), com o `security.yml` verde como condição explícita do merge (exceções EX-001 a EX-013 válidas até 2027-02-09).

## Janela e comunicação

- Publicar termos novos em horário combinado com os convidados: todos passam pela tela de novo aceite no próximo acesso.
- Antes da primeira anonimização real, registrar na release que o piso de rollback passa a ser a imagem da U5.
