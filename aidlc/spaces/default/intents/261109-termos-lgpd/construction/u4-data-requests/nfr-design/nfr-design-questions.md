# Perguntas de NFR Design — U4 `u4-data-requests`

Conversa: português (Brasil). Todas as respostas são as recomendadas (A), pelo pedido do responsável do projeto em 2026-11-10: "Siga sempre as recomendações sem me pergunta".

## Q1 — Executor dos jobs do Privacy

A. Um executor comum do Privacy (`PrivacyJobRunner`), no padrão do `IdentityJobRunner` da U2: trava, transação curta para a execução e o evento, métricas e `FAILED` sem propagar. Ele serve o job de 15 minutos (`dataRequestTimers`) e o expurgo diário (`dataSubjectRequestPurge`), com a tabela `privacy_job_run`. (recomendado)
B. Reaproveitar o executor do Identity.
X. Other (please specify)

[Answer]: A

## Q2 — Onde os apps chamam as rotas

A. O app do convidado usa o cliente gerado de P1 pelo `guest-api` existente. O app do administrador usa o cliente gerado de P2 pelo cliente administrativo existente. As duas telas são renderizadas no cliente, e os dados dos pedidos nunca vão para cache. (recomendado)
B. Renderizar no servidor do Next.
X. Other (please specify)

[Answer]: A
