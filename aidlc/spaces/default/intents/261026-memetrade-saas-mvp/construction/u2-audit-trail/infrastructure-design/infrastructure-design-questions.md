# Perguntas — Infrastructure Design da U2 (trilha de auditoria)

Objetivo: decidir o que falta para levar o desenho de [logical-components.md](../nfr-design/logical-components.md), [security-design.md](../nfr-design/security-design.md), [reliability-design.md](../nfr-design/reliability-design.md), [performance-design.md](../nfr-design/performance-design.md), [scalability-design.md](../nfr-design/scalability-design.md) e [observability-design.md](../nfr-design/observability-design.md) ao ambiente local e à CI da U1.

Já decidido e que não volta a ser perguntado: só o ambiente local com Docker Compose; imagens por digest; GitHub Actions com os jobs `backend`, `frontend`, `contract`, `security` (Semgrep e OWASP Dependency-Check), `smoke` e `e2e`; workflow manual de carga; cota gratuita de minutos ainda a confirmar (U1). O serviço de preparo dos papéis usa a mesma imagem do PostgreSQL (que já tem `psql`); o smoke espera a primeira execução do expurgo e da limpeza, como já espera a primeira coleta; os parâmetros do PostgreSQL entram pela linha de comando do serviço no Compose e no Testcontainers.

## Q1. [Plataforma] Onde rodam os testes de desempenho da trilha?

A meta da consulta (p95 até 2 s) é medida com a trilha cheia, cerca de 37 milhões de eventos, em três cenários. A meta de entrega (99% em até 5 s) é medida numa janela de 1 hora de carga. Só a semeadura leva uns 15 minutos, e a cota gratuita do GitHub Actions é de cerca de 2.000 minutos por mês.

- A. Workflow manual `audit-perf` no GitHub Actions: semeia a trilha, mede a consulta nos três cenários e a entrega numa janela de 15 minutos (cerca de 45 minutos por execução). A janela completa de 1 hora roda só localmente, por script versionado, antes de fechar a U2, com o relatório guardado
- B. Tudo só localmente, por um script versionado (`scripts/perf-audit.sh`), com o relatório guardado no fechamento da U2; a CI não roda desempenho da trilha
- C. Workflow manual com tudo, inclusive a janela de 1 hora (cerca de 90 minutos por execução)
- X. Other (please specify)

[Answer]: B

## Consolidated Summary Confirmation

- Os testes de desempenho da trilha rodam só localmente, por um script versionado (`scripts/perf-audit.sh`): semeadura de cerca de 37 milhões de eventos, consulta nos três cenários e entrega numa janela de 1 hora. O relatório é guardado no fechamento da U2, e a CI não roda desempenho da trilha (Q1).
- Seguem da U1 e do desenho, sem nova escolha: o serviço de preparo dos papéis usa a imagem do PostgreSQL; o smoke espera a primeira execução do expurgo e da limpeza; os parâmetros do PostgreSQL entram pela linha de comando no Compose e no Testcontainers.

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
