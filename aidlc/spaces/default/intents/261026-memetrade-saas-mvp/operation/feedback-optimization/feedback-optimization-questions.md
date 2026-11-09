# Perguntas do Feedback & Optimization

Conversa: português (Brasil). Última etapa. Alvo: a release local `v2026.11.08-1`, sem usuários reais e sem conta na AWS. Por isso não há Cost Explorer, AWS Config nem Trusted Advisor para consultar. Fontes: `operation/observability-setup/` (`slo-config.md`, `dashboards.md`, `alarms.md`), `operation/performance-validation/test-results.md`, `operation/deployment-execution/deployment-log.md` e `operation/incident-response/incident-plan.md`.

Desvios já detectados nesta leitura:
1. `.github/workflows/ci.yml` ainda roda só parte dos testes do web com cobertura e não tem o job `admin`. As mudanças descritas em `construction/ci-pipeline/ci-config.md` dependem do responsável do projeto.
2. `inception/contract-design/contract-summary.md` mudou depois que o Contract Design terminou (commit `26c9885`). O workflow marca o Contract Design e as etapas seguintes como "a revalidar".
3. Docker Compose e especificação de infraestrutura: sem desvio (`operation/environment-provisioning/validation-report.md`).

## Pergunta 1
Como relatar os SLOs?

A) O relatório mostra o cumprimento das metas das Units (nível 1 de `slo-config.md`), com a evidência das cargas e dos smokes: todas cumpridas, menos U4-NFR3.5 (Not Met) e U2-NFR3.1, 3.3 e 4.2 (Unverified). Os SLOs de produção (nível 2) ficam como "sem dados", com orçamento de erro não aplicável até haver tráfego real. A primeira medição é marcada para 30 dias depois da abertura do beta (recomendado)
B) Só registrar que não há dados
X) Other (please specify)

[Answer]: A

## Pergunta 2
Como tratar custos?

A) Custo atual: zero de nuvem; o único consumo é de minutos do GitHub Actions, cuja cota ainda é a confirmar (pendência da Sprint 0). Sem inventar preços: listar os vetores de custo da AWS desenhada (computação da API e do worker, banco gerenciado com réplica, NAT, logs, X-Ray, backups) e as alavancas de economia (staging em uma zona e desligado fora de uso, worker em Fargate Spot se a tolerância a interrupção permitir, retenção curta de logs, amostragem do X-Ray). A estimativa com números entra no próximo Environment Provisioning, com a calculadora ou a API de preços da AWS (recomendado)
B) Fazer já uma estimativa de custo da AWS com valores de referência
X) Other (please specify)

[Answer]: A

## Pergunta 3
O que fazer com o desvio do `contract-summary.md`?

A) Registrar no relatório de desvios com a recomendação de revisar o Contract Design no início do próximo ciclo, antes de qualquer trabalho novo de contrato. O código atual e os testes de contrato da CI seguem verdes; refazer o Contract Design agora reabriria as etapas seguintes. Você pode pedir para refazer a qualquer momento (recomendado)
B) Refazer o Contract Design agora, com revalidação das etapas seguintes
X) Other (please specify)

[Answer]: A

## Pergunta 4
Que sinais de uso alimentam o próximo ciclo?

A) Ainda não há usuários. O documento de retroalimentação define o que coletar no beta:
- feedback da U8;
- funil convite → conta → primeiro bot PAPER → primeiro P&L;
- uso dos filtros do scanner e proporção de `unknown` vista;
- pausas e kill switch;
- incidentes.

Leva para o próximo Ideation as pendências abertas: escala da U4, `perf-audit` da U2, `ci.yml`, revisão do Contract Design, FR14.x e FR15.1 sem critério de aceite, NFRs adiados, achados Menores da U6, teste instável da U9 e achados Baixos do `verify.sh` (recomendado)

B) Só a lista de pendências
X) Other (please specify)

[Answer]: A

## Pergunta 5
Que trabalho operacional repetitivo automatizar?

A) Candidatos, em ordem de valor:
1. Um script de release local que cria a tag e roda o ensaio de rollback e os três smokes, com o resultado gravado na tag. Os passos manuais desta release viram um comando só.
2. `perf-audit.sh` num workflow manual da CI, em executor com mais memória.
3. As mudanças pendentes do `ci.yml`.
4. Atualização automática de dependências, com espera para versões recém-publicadas.
5. Limpeza periódica de imagens e cache do Docker local.

Cada item vira tarefa no próximo ciclo, sem mudar código agora (recomendado)

B) Escolher outros (diga quais)
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- SLOs: relatório das metas das Units (todas cumpridas, menos U4-NFR3.5 Not Met e U2-NFR3.1, 3.3 e 4.2 Unverified); SLOs de produção sem dados, primeira medição 30 dias depois da abertura do beta.
- Custos: zero de nuvem hoje; vetores de custo e alavancas de economia da AWS desenhada, sem preços inventados; estimativa no próximo Environment Provisioning.
- Desvios: `ci.yml` pendente do responsável do projeto; `contract-summary.md` registrado, com revisão do Contract Design no início do próximo ciclo; Compose sem desvio.
- Retroalimentação: o que coletar no beta e todas as pendências abertas como entrada do próximo Ideation.
- Automação: script de release, `perf-audit` na CI manual, `ci.yml`, atualização de dependências e limpeza do Docker, como tarefas do próximo ciclo.

- Looks correct
- Request changes

[Answer]: Looks correct
