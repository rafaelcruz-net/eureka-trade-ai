# Reverse Engineering Timestamp

## Run Record

- Date: 2026-11-13
- Commit: 40e25530928b5fcb5d82bcc4b7afc283a6e12369

Varredura completa (full rescan) do repositório para o intent `261113-trading-real-bot`, profundidade Comprehensive,
feita só por leitura de arquivos (nenhum teste, build ou programa do projeto executado). Esta execução substitui por
inteiro os 9 artefatos do mapa anterior (2026-11-08, commit `ffde88f`), que foi lido só como contexto.

Ficaram fora, sem abrir: saídas de build e dependências (`node_modules/`, `.next/`, `target/`, `coverage/`,
relatórios de teste), ferramentas locais e IDE (`.tools/`, `.vscode/`), `.env`, relatórios gerados (`docs/perf/`,
`load-test-report.json`), a cópia PDF do PRD, os lockfiles, os tipos gerados `schema.d.ts` e a instalação do AI-DLC
(`.claude/`, `.codex/`, `.agents/`, `aidlc/` e as seções do AI-DLC em `AGENTS.md` e `.gitignore`).

A cobertura detalhada (o que foi lido a fundo e o que foi só percorrido) está no handoff
`aidlc/spaces/default/intents/261113-trading-real-bot/inception/reverse-engineering/developer-scan.md`. A profundidade
de cada componente está em [component-inventory.md](component-inventory.md).

## Scope of Analysis

```yaml
scope_version: 1
kind: full
intent: 261113-trading-real-bot
fingerprint: 633944d96db12b9877eb7b6f746a51f5dae21b6d
analyzed:
  paths:
    - ./
  components:
    - shared
    - identity
    - audit
    - marketdata
    - scanner
    - riskassessment
    - riskcontrol
    - bots
    - decision
    - ledger
    - pnl
    - administration
    - feedback
    - privacy
    - ops
    - db-migrations
    - api-resources
    - api-tests
    - web-app
    - admin-app
    - e2e-tests
    - db-bootstrap
    - compose-stack
    - ci-workflows
    - dev-scripts
    - project-docs
shallow:
  paths:
    - apps/api/src/main/java/com/memetrade/identity/
    - apps/api/src/main/java/com/memetrade/audit/
    - apps/api/src/main/java/com/memetrade/privacy/
    - apps/api/src/main/java/com/memetrade/feedback/
    - apps/api/src/main/java/com/memetrade/scanner/
    - apps/api/src/main/java/com/memetrade/riskassessment/
    - apps/api/src/main/java/com/memetrade/decision/
    - apps/api/src/test/
    - apps/web/src/
    - apps/web/e2e/
    - apps/admin/src/
    - infra/postgres/
    - scripts/
    - docs/
    - README.md
    - PromptInicial.txt
```
