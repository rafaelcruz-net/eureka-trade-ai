# Relatório de validação do ambiente

Conversa: português (Brasil). Validação do ambiente local em 2026-11-08. A AWS não foi provisionada (decisão da pessoa); por isso não há log de pilha nem detecção de deriva de nuvem.

## Resultado

**Ambiente local validado.** Staging e produção continuam pendentes (ver `environment-inventory.md`).

## Verificações

| Verificação | Resultado | Evidência |
|---|---|---|
| Ambiente sobe a partir do código (Compose) e fica saudável | sim: `postgres`, `api`, `web` e `admin` saudáveis; `db-bootstrap` e `db-migrate` terminaram com sucesso; `worker` subiu | `bash scripts/verify.sh` no ponto de controle do esqueleto, 2026-11-08 23:06–23:12 UTC, saída 0 |
| Fonte simulada coletada e tarefas de auditoria concluídas | sim | mesmas etapas do `verify.sh` ("first collection", "audit retention purge and outbox cleanup") |
| Smokes de ponta a ponta | 10/10 (U1, U2, U3, U4, U5, U7, U8, U9) | mesma execução |
| Nenhum endereço de carteira completo, texto de feedback ou código de convite nos logs de api, worker e admin | sim | varredura de logs do `verify.sh` |
| Ambiente removido no fim (sem sobra) | sim: "environment removed and smoke key deleted" | mesma execução |
| Portas publicadas só em `127.0.0.1` | sim: 5432, 8080, 8081, 3000, 3001 (e 8082 só no overlay de teste) | `docker-compose.yml`, `infra/docker-compose.test.yml` |
| `worker` sem porta no Compose principal | sim | `docker-compose.yml`; teste `ComposeFilesTest` |
| Imagem do banco fixada por digest | sim | `docker-compose.yml` |
| Limites de memória por serviço | sim: 1 GB (postgres, api, worker), 512 MB (web, admin) | `docker-compose.yml` |
| `.env` e `.claude/settings.local.json` ignorados pelo git | sim | `git check-ignore`: `.gitignore:30` e `.gitignore:26` |
| `.env.example` sem valores secretos | sim: só nomes e comentários para senhas e chave | `.env.example` |
| Nenhum segredo em `NEXT_PUBLIC_*` | sim: a origem da API é lida no servidor, em tempo de execução, nos dois apps | `apps/web/src/app/layout.tsx`, `apps/admin/src/app/layout.tsx`, `docker-compose.yml` |
| Segredos efêmeros na CI e no `verify.sh` | sim: sem `.env`, o `verify.sh` gera segredos por execução; a CI não usa segredo | `ci.yml` (comentário do job `smoke`), `scripts/verify.sh` |
| Dependências sem vulnerabilidade Alta ou Crítica | sim: `pnpm audit --audit-level high` limpo nos dois apps depois do `postcss` 8.5.28 | CI Pipeline, `ci-config.md` |

## Postura de segurança (revisão do engenheiro de segurança)

- Nenhuma exposição de rede além da máquina local; nenhum fornecedor real (C-06) nem carteira real; REAL desligado por padrão.
- Segredos fora do repositório, com varredura de segredos no pré-commit (Gitleaks) e na CI.
- Riscos registrados para a AWS: segredos no Secrets Manager com papel de execução por serviço; CI por OIDC; banco e worker em sub-rede privada; só o balanceador exposto; DAST, varredura de imagens e SBOM antes do G1.

## Não validado (depende da AWS)

| Item | Motivo |
|---|---|
| VPC, sub-redes, grupos de segurança, NACLs | não existem: sem conta de nuvem |
| Secrets Manager e Parameter Store | não existem |
| Conectividade entre contas e VPCs | não se aplica hoje |
| Logs de implantação de pilhas e deriva | sem IaC aplicada |
| Saúde de staging e produção | ambientes pendentes |
