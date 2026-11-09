# Reverse Engineering Timestamp

## Run Record

- Date: 2026-11-08
- Commit: ffde88f89326bb3bb4bad499c0c36496ea2bc077

Varredura completa e primeira do repositório (sem base anterior), feita para o intent `termos-lgpd`, profundidade
Standard, só por leitura de arquivos (nenhum teste, build ou programa executado). Ficaram fora, sem abrir: saídas de
build e dependências (`node_modules/`, `.next/`, `target/`, `coverage/`, relatórios de teste), ferramentas locais e
IDE (`.tools/`, `.vscode/`), `.env`, relatórios gerados (`docs/perf/`, `load-test-report.json`), a cópia PDF do PRD,
os lockfiles e a instalação do AI-DLC (`.claude/`, `.codex/`, `.agents/`, `aidlc/`, `AGENTS.md`).

A cobertura detalhada (o que foi lido a fundo e o que foi só percorrido) está no handoff
`aidlc/spaces/default/intents/261109-termos-lgpd/inception/reverse-engineering/developer-scan.md`. A profundidade
de cada componente está em [component-inventory.md](component-inventory.md).

## Scope of Analysis

```yaml
scope_version: 1
kind: full
intent: termos-lgpd
fingerprint: bd2204a77a0f52aa955663b652bb7575c5fde7be
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
    - apps/api/src/main/java/com/memetrade/marketdata/
    - apps/api/src/main/java/com/memetrade/scanner/
    - apps/api/src/main/java/com/memetrade/riskassessment/
    - apps/api/src/main/java/com/memetrade/riskcontrol/
    - apps/api/src/main/java/com/memetrade/bots/
    - apps/api/src/main/java/com/memetrade/decision/
    - apps/api/src/main/java/com/memetrade/ledger/
    - apps/api/src/main/java/com/memetrade/pnl/
    - apps/api/src/main/java/com/memetrade/administration/
    - apps/api/src/main/java/com/memetrade/ops/
    - apps/api/src/test/
    - apps/web/src/features/bots/
    - apps/web/src/features/dashboard/
    - apps/web/src/features/pnl/
    - apps/web/src/features/scanner/
    - apps/web/src/features/feedback/
    - apps/web/e2e/
    - scripts/
    - PRD_MemeTrade_Analytics_v1.3_SaaS.md
```
