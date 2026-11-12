# Relatório de validação dos ambientes

Conversa: português (Brasil). Data: 2026-11-12. Validação do ambiente local e da CI com as mudanças das Units U1 a U5. Nenhum recurso de nuvem existe para validar (resposta 1A).

## Resultado

**Ambiente local: válido. CI: válida até o último commit verde. AWS: pendente (C-10, C-12).**

| Verificação | Resultado | Evidência |
|---|---|---|
| Docker Desktop disponível | ok, servidor 29.9.2 | `docker version` |
| Compose principal e overlay de teste resolvem | ok | `docker compose -f docker-compose.yml -f infra/docker-compose.test.yml config --quiet` com valores fictícios |
| `api` e `worker` recusam subir sem a chave HMAC | ok | `ComposeFilesTest` (`${MEMETRADE_AUDIT_WALLET_HMAC_KEY:?}` nos dois serviços, nos dois arquivos) |
| `web`, `admin`, `postgres`, `db-bootstrap` e `db-migrate` não recebem a chave | ok | `ComposeFilesTest` |
| `.env` ignorado pelo git; `.env.example` sem valor | ok | `git check-ignore .env`; `ComposeFilesTest` |
| Scripts que sobem a pilha geram chave efêmera | ok | `ComposeFilesTest` (`verify.sh`, `load-test.sh`, `perf-audit.sh`) |
| Ambiente inteiro saudável e smoke | ok | `bash scripts/verify.sh`, 19 de 19 (ponto de controle da U3, 2026-11-11T20:24Z) |
| Saúde dos indicadores novos | ok | `legalDocuments`, `loginChallengePurge`, `sessionPurge`, `dataRequests` testados na suíte; `verify.sh` espera saúde `UP` |
| Orçamento de conexões | ok, 43 de 100 | `docker-compose.yml`; `ComposeFilesTest` |
| Postura de segurança | ok | Gitleaks sem achado; Dependency-Check sem CVSS ≥ 7 fora das exceções EX-001 a EX-013; `pnpm audit` sem Alto (Build and Test) |
| CI no GitHub Actions | verde até o commit `63a7710` (execução `43067822342`) | as mudanças posteriores ainda não têm commit |

## Itens que dependem do responsável

1. Criar o `.env` local com `MEMETRADE_AUDIT_WALLET_HMAC_KEY` antes de subir o ambiente de desenvolvimento com `docker compose up` (`environment-inventory.md`).
2. Commit e push das mudanças do Build and Test em diante, com a CI e o `security.yml` verdes.

## Postura de segurança da AWS

Não validada: não há ambiente. O desenho (OIDC para a CI, Secrets Manager, Parameter Store, portas só internas) é o do intent anterior, mais os segredos e parâmetros deste intent (`environment-inventory.md`).
