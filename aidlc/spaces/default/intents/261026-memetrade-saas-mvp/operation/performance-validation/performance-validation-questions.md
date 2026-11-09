# Perguntas do Performance Validation

Conversa: português (Brasil). Não existe ambiente parecido com produção: só a release local `v2026.11.08-1`, num notebook Windows com Docker. Não há CloudWatch nem X-Ray (`operation/observability-setup/`). As métricas Prometheus locais são a evidência disponível.

O que já foi medido (Build and Test, `construction/build-and-test/test-results.md` e `performance-test-instructions.md`, de 2026-11-06 a 2026-11-08):
- Aprovados: `u1` (desafio 45 ms, verificação 224 ms, lista 45 ms), `u3` (verificação com convite 259 ms), `u4` (lista 500 ms com 2 000 tokens, detalhe 11 ms), `u5` (ciclo p95 112 ms, canal ao vivo 1,9 s), `u6` (3 cargas, lote máximo 58 ms, `evaluate` p95 ≤ 21,93 ms), `u7` (`execute` 34 ms, ledger 201 ms) e `u7-jobs`.
- Não atendido: `growth` da U4, com 10 000 tokens a lista leva 10,02 s (U4-NFR3.5). Decisão: réplica de leitura junto com a hospedagem, nova medição antes do G1.
- Sem verificação: `scripts/perf-audit.sh` da U2 (NFR3.1, 3.3 e 4.2), interrompido por falta de memória no notebook.
- A release `8fd7a7b` muda só o `postcss` do frontend em relação a `0cd9ccf`. Nenhum código medido pelas cargas mudou.

## Pergunta 1
Qual o alcance desta etapa?

A) Consolidar as medições do Build and Test, que valem para `8fd7a7b` porque nenhum código medido mudou, numa matriz de validação de NFR, com plano de carga para o staging na AWS (ambiente parecido com produção) e os critérios de saída antes do G1. Sem novas cargas agora. O `perf-audit.sh` da U2 e o `growth` da U4 ficam como pendências abertas, com dono e momento (recomendado)
B) Rodar de novo todos os cenários agora, no notebook. São várias horas e há risco de falta de memória, como já aconteceu
C) Fazer A e, além disso, você roda de novo o `perf-audit.sh` com o mínimo de programas abertos, para tentar fechar a U2
X) Other (please specify)

[Answer]: A

## Pergunta 2
Quais padrões de tráfego valem?

A) Os dos requisitos do beta:
- regime normal com 20 usuários simultâneos;
- pico de 60 bots PAPER ativos;
- rajada de 20 verificações de login simultâneas;
- base de 2 000 tokens, e 10 000 no cenário de crescimento;
- uma hora contínua de auditoria no `perf-audit`.

No staging, um teste de resistência de 2 horas no regime normal (recomendado)

B) Definir outro perfil (diga qual)
X) Other (please specify)

[Answer]: A

## Pergunta 3
Quais percentis?

A) O p95 de cada NFR é o critério de aprovação. O p50 e o p99 são registrados como informação. No staging, o p99 ganha um limite de 3× a meta de p95 para pegar caudas longas. Nenhuma meta de p95 muda (recomendado)
B) Exigir o p99 como critério desde já
X) Other (please specify)

[Answer]: A

## Pergunta 4
Que vazão o sistema precisa sustentar?

A) A que sai dos requisitos:
- 20 usuários com as telas do beta;
- 60 bots com ciclo a cada fatia;
- auditoria com 99% das entregas em até 5 s;
- reconciliação e rodadas do ledger dentro de 10 s.

Sem meta em requisições por segundo além dessas até haver dados reais do beta (recomendado)

B) Fixar uma meta de requisições por segundo (diga qual)
X) Other (please specify)

[Answer]: A

## Pergunta 5
Onde estão os gargalos prováveis?

A) Os que as cargas já mostraram:
- leitura da lista da U4 com muitos tokens (banco, resolvido pela réplica de leitura);
- relay da auditoria sob carga contínua (a confirmar no `perf-audit`);
- memória do notebook e limites de 1 GB por contêiner;
- pool do HikariCP sob rajada.

Cada um entra na matriz com a métrica do painel que o acompanha (recomendado)

B) Investigar outros (diga quais)
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Alcance: consolidar as medições do Build and Test (válidas para `8fd7a7b`) numa matriz de NFR e escrever o plano de carga para o staging na AWS, com critérios de saída antes do G1; sem novas cargas agora; `perf-audit.sh` da U2 e `growth` da U4 ficam como pendências com dono e momento.
- Tráfego: 20 usuários, pico de 60 bots, rajada de 20 logins, 2 000 tokens (10 000 no crescimento), 1 hora de auditoria; resistência de 2 horas no staging.
- Percentis: p95 de cada NFR como critério; p50 e p99 registrados; no staging, p99 ≤ 3× a meta do p95.
- Vazão: a dos requisitos, sem meta em requisições por segundo.
- Gargalos: lista da U4 com muitos tokens, relay da auditoria, memória e limites de contêiner, pool do HikariCP, cada um com a métrica do painel.

- Looks correct
- Request changes

[Answer]: Looks correct
