# Perguntas do desenho de infraestrutura — U5 Bots e decisão

O ambiente continua só local: Docker Compose no Windows e CI no GitHub Actions. O NFR Design está em [../nfr-design/](../nfr-design/).

Já resolvido sem pergunta (pode corrigir na revisão do resumo):
- **Serviço `worker` no Compose.**
  - Usa a mesma imagem da API, com o perfil `worker` e `restart: unless-stopped`.
  - O healthcheck é de liveness.
  - Tem o mesmo limite de memória da API (1 GB) e as mesmas variáveis de banco e de mercado.
  - Sobe depois do `db-migrate`.
- **API.** Fica com o agendador dos bots desligado (`memetrade.bots.scheduler.enabled=false`).
- **Migração.** A `0010-bots-decision.sql` só acrescenta.
- **Pool do worker.** Tem 14 conexões (NFR Design).
- **Cenário de carga `u5`.** Entra no `scripts/load-test.sh`, com o worker no perfil `loadtest`.
- **Achado da revisão do NFR Design.** Um reinício do worker no meio da fatia não redespacha os bots que faltavam, porque o ShedLock fica preso por 4 minutos. Eles decidem na fatia seguinte, e isso fica registrado.
- **Sem segredo novo.**

## Q1. [CI/CD] O que o smoke de ponta a ponta da U5 faz

A U5 tem telas, mas um ciclo real leva 5 minutos, o que é longo para o smoke.

A. O smoke entra com a conta de smoke e faz o caminho completo pelo assistente: cria o bot, inicia, pausa, retoma e encerra. Ele confere estados, motivos e o rótulo PAPER, sem esperar ciclo. Um teste de integração do backend roda os ciclos com o relógio controlado.
B. Como A, e além disso, só no perfil `test`, a fatia passa a 30 s. Assim o smoke espera a primeira decisão aparecer na tela do bot, com a fonte simulada no cenário de compra.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
