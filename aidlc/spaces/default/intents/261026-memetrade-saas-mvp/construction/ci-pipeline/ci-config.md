# Configuração da CI

Conversa: português (Brasil). Respostas confirmadas em `ci-pipeline-questions.md` (todas A): GitHub Actions; tronco `main` com squash-merge e nenhum merge com a CI vermelha; portões de hoje mais `apps/admin` e a suíte inteira do `apps/web`; a pessoa aplica a mudança no `.github/workflows/ci.yml`; `postcss` corrigido por `pnpm.overrides`; artefatos só no GitHub Actions.

## Situação atual

| Workflow | Gatilho | Jobs | Observação |
|---|---|---|---|
| `.github/workflows/ci.yml` | `pull_request` e `push` em `main` | `backend`, `frontend`, `contract`, `smoke`, `e2e` (só push em `main`) | ações fixadas por SHA, `permissions: contents: read`, `concurrency` por ref |
| `.github/workflows/security.yml` | `pull_request`, `push` em `main` e agenda | `secrets` (Gitleaks), `sast` (CodeQL), `dependencies` (OWASP Dependency-Check e `pnpm audit` dos dois apps) | Crítico ou Alto bloqueia; exceções versionadas com validade |
| `.github/workflows/load-test.yml` | `workflow_dispatch` | `load` por cenário (`u1`, `u3`, `u4`, `growth`, `u5`, `u6`, `u7`, `u7-jobs`) | só manual; não bloqueia merge |

Lacunas que esta etapa fecha:
1. `apps/admin` não roda na CI (lint, formatação, tipos, Jest com piso de 80%, conferência dos tipos gerados do contrato administrativo).
2. O job `frontend` roda o Jest só em parte das pastas do `apps/web`; o piso de 80% não vale para a suíte inteira (391 testes).
3. Os 2 avisos High do `postcss` vindo do `next` bloqueavam o job `dependencies`.

## Mudança feita nesta etapa: `postcss` (opção 5A)

- `apps/web/package.json` e `apps/admin/package.json`: `"pnpm": { "overrides": { "postcss": "8.5.28" } }`. A versão 8.5.28 é de 2026-09-03, mais de dois meses antes, e cumpre a espera de alguns dias do `team.md`. O `next` 15.5.28 trazia `postcss` 8.4.31.
- Lockfiles atualizados com `pnpm install`; `install --frozen-lockfile` volta a passar.
- Resultado local (2026-11-08):
  - `pnpm audit --audit-level high`: sem High nem Crítico nos dois apps (restam 1 low e 3 moderate, que só geram aviso);
  - `tsc`, `eslint` e `format:check` limpos nos dois apps;
  - Jest: `apps/web` 391/391, linhas 96,03%; `apps/admin` 85/85, linhas 91,34%;
  - `next build`: compila nos dois apps; no Windows sem modo desenvolvedor, a cópia do `standalone` falha por EPERM de symlink, um limite do Windows local. O build que vale é o do Docker (Linux) no `scripts/verify.sh` e na CI;
  - `bash scripts/verify.sh` pelo ponto de controle do esqueleto (2026-11-08 23:06–23:12 UTC): as imagens `web` e `admin` foram construídas com o `postcss` 8.5.28, saída 0, 10/10 testes Playwright. A U1 foi revisada (READY, sem achados novos) e o esqueleto aprovado de novo pela pessoa.

## Mudança preparada para o `.github/workflows/ci.yml` (a pessoa aplica)

O agente não edita o `ci.yml`. A pessoa aplica os três trechos abaixo.

### 1. Job `frontend`: Jest na suíte inteira do `apps/web`

Trocar a linha 70:

```yaml
        run: pnpm --dir apps/web exec jest --coverage src/features/auth src/features/scanner src/lib src/middleware.test.ts
```

por:

```yaml
        run: pnpm --dir apps/web exec jest --coverage
```

O `jest.config.ts` do `apps/web` já tem `coverageThreshold.global.lines = 80`, então o comando falha abaixo do piso.

### 2. Job novo `admin`, logo depois do job `frontend`

```yaml
  admin:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
      - uses: pnpm/action-setup@a7487c7e89a18df4991f7f222e4898a00d66ddda # v4.1.0
        with:
          package_json_file: apps/admin/package.json
      - uses: actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020 # v4.4.0
        with:
          node-version-file: .nvmrc
          cache: pnpm
          cache-dependency-path: apps/admin/pnpm-lock.yaml
      - run: pnpm --dir apps/admin install --frozen-lockfile
      - run: pnpm --dir apps/admin lint
      - run: pnpm --dir apps/admin format:check
      - run: pnpm --dir apps/admin typecheck
      - name: Jest with the 80% line floor
        run: pnpm --dir apps/admin exec jest --coverage
      - name: Generated administrator types equal schema.d.ts
        run: pnpm --dir apps/admin check:api
```

Usa as mesmas ações fixadas por SHA dos outros jobs. Não usa segredo e herda `permissions: contents: read`.

### 3. Job `e2e`: esperar também o job `admin`

Trocar a linha 129:

```yaml
    needs: [backend, frontend, contract, smoke]
```

por:

```yaml
    needs: [backend, frontend, admin, contract, smoke]
```

### Proteção da branch `main` (configuração no GitHub, feita pela pessoa)

Em Settings → Branches → `main`, exigir como status checks obrigatórios: `backend`, `frontend`, `admin`, `contract`, `smoke` (de `ci`) e `secrets`, `sast`, `dependencies` (de `security`). Exigir branch atualizada antes do merge e permitir só squash-merge.

## Artefatos (opção 6A)

| Artefato | Job | Retenção |
|---|---|---|
| Relatório JaCoCo do backend | `backend` | 7 dias |
| Relatório do Playwright | `smoke` | 7 dias |
| Relatório do Dependency-Check | `dependencies` | padrão do workflow |
| Relatório da carga | `load` (manual) | padrão do workflow |

Imagens de contêiner já são marcadas com o SHA do commit (`IMAGE_TAG: ${{ github.sha }}`), mas só existem dentro do job. Registro de imagens fica para o Deployment Pipeline, quando houver conta de nuvem (C-10, C-12).

## Fora desta etapa

- Rodar o `perf-audit.sh` da U2 (pendência aceita no Build and Test; falta memória no computador local).
- Medir a escala da U4 com a réplica de leitura (decisão da pessoa, com a hospedagem).
- Carga a cada merge: a pessoa escolheu manter a carga manual.
