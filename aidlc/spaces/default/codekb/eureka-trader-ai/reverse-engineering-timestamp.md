# Reverse Engineering Timestamp

## Run Record

- Date: 2026-11-21
- Commit: 3497b2bc49052f01adb93de7094a05880532222b

Varredura focada para o intent `261121-paper-real-unico`, profundidade Standard, feita só por leitura de arquivos
(nenhum teste, build ou programa do projeto executado). Ela mescla na base de conhecimento existente, que estava
desatualizada: os caminhos analisados mudaram desde a construção de 2026-11-13 (commit `40e2553`, intent
`261113-trading-real-bot`, varredura completa). A prosa anterior fora da área analisada foi preservada e marcada como
estado de 2026-11-13; a cobertura profunda anterior (`./`) não pôde ser reconfirmada e foi rebaixada a superficial, aqui
expressa pelas pastas e arquivos de topo que ela cobria.

Área analisada a fundo nesta execução: os módulos de backend `bots`, `pnl`, `realexecution`, `ledger` e `realexits` e,
no app web, as features `bots`, `real` e `dashboard` e as rotas `app/pnl` e `app/bots`. Percorridos sem leitura a fundo:
as migrações do Liquibase, os testes do backend (contagens e nomes), o spec `e2e/real-swap`, `features/pnl`,
`app/real` e `lib/api`. Ficaram fora, sem abrir: saídas de build e dependências (`node_modules/`, `.next/`, `target/`,
`coverage/`, relatórios de teste), os tipos gerados `schema.d.ts` e a instalação do AI-DLC (`.claude/`, `.codex/`,
`.agents/`, `aidlc/` e as seções do AI-DLC em `AGENTS.md` e `.gitignore`).

Existem hoje componentes que nenhuma varredura descreveu a fundo: o módulo `botwallet`, o subpacote `riskcontrol/real`
e o app `apps/txsigner`.

A cobertura detalhada está no handoff
`aidlc/spaces/default/intents/261121-paper-real-unico/inception/reverse-engineering/developer-scan.md`. A profundidade
de cada componente está em [component-inventory.md](component-inventory.md).

## Scope of Analysis

```yaml
scope_version: 1
kind: partial
intent: 261121-paper-real-unico
fingerprint: c0bfeabe1720bcc4fb50677036b3e28665fa6785
analyzed:
  paths:
    - apps/api/src/main/java/com/memetrade/bots/
    - apps/api/src/main/java/com/memetrade/pnl/
    - apps/api/src/main/java/com/memetrade/realexecution/
    - apps/api/src/main/java/com/memetrade/ledger/
    - apps/api/src/main/java/com/memetrade/realexits/
    - apps/web/src/features/bots/
    - apps/web/src/features/real/
    - apps/web/src/features/dashboard/
    - apps/web/src/app/pnl/
    - apps/web/src/app/bots/
  components:
    - bots
    - pnl
    - realexecution
    - ledger
    - realexits
    - web-bots
    - web-real
    - web-dashboard
    - web-pnl-route
shallow:
  paths:
    - apps/api/
    - apps/web/
    - apps/admin/
    - infra/
    - scripts/
    - docs/
    - .github/
    - .githooks/
    - security/
    - docker-compose.yml
    - .env.example
    - PRD_MemeTrade_Analytics_v1.3_SaaS.md
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
    - README.md
    - PromptInicial.txt
    - apps/api/src/main/resources/db/changelog/changes/
    - apps/web/e2e/real-swap/
    - apps/web/src/features/pnl/
    - apps/web/src/app/real/
    - apps/web/src/lib/api/
```
