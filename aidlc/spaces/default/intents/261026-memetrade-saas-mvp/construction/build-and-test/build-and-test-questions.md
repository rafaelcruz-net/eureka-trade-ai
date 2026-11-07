# Perguntas — Build and Test

## Q1. Medição de abertura do painel (NFR3.1 da U9)

A medição `DashboardOpenLatencyIT` passa na suíte completa, mas, rodando sozinha, fica entre 2,02 e 2,08 s contra a meta de 2 s. Em Build and Test, uma meta não cumprida reabre a geração de código. Como tratar?

A. Otimizar a abertura: a meta de 2 s continua como portão; se falhar, Build and Test reabre a U9 para acelerar a abertura do quadro de bots.
B. Medição informativa: a medição continua rodando e registrando o p95, mas deixa de bloquear o merge; a decisão fica registrada no NFR3.1.
X. Other (please specify)

[Answer]: B

## Q2. Smoke da U9 no comando de verificação

`scripts/verify.sh` ainda não roda `e2e/u9-dashboard`, e o arquivo é protegido. A pessoa acrescenta antes de Build and Test rodar?

A. Já acrescento: a pessoa edita o script e Build and Test roda o smoke da U9 junto com os demais.
B. Rodar à parte: Build and Test roda o smoke da U9 por um comando separado e o script fica como pendência.
X. Other (please specify)

[Answer]: X. Other — "Por enquanto registre como pendência não consigo editar neste momento pois não estou no computador". O script fica como está; a inclusão de `e2e/u9-dashboard` é uma pendência da pessoa, e o smoke da U9 fica não verificado neste Build and Test.

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
