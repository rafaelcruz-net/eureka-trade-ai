# Relatório de desvios

Conversa: português (Brasil). Resposta 3A. Não há AWS Config, porque não há conta. A comparação foi feita entre os artefatos aprovados e o repositório na release `v2026.11.08-1` (`operation/deployment-execution/deployment-log.md`). Os itens de monitoramento comparados são os de `operation/observability-setup/dashboards.md`, `alarms.md` e `slo-config.md`.

## Desvios

| # | Onde | Aprovado | No repositório | Efeito | Ação | Dono |
|---|---|---|---|---|---|---|
| D1 | `.github/workflows/ci.yml` | `construction/ci-pipeline/ci-config.md`: job web com `jest --coverage` completo, job `admin` novo, `needs` com `admin` | o job web roda a cobertura só em `src/features/auth`, `src/features/scanner`, `src/lib` e `src/middleware.test.ts`; não há job `admin`; `needs: [backend, frontend, contract, smoke]` | o piso de 80% do web e o admin não são verificados na CI; localmente estão em 96,03% e 91,34% | aplicar as mudanças do `ci-config.md` | responsável do projeto (só ele altera o `ci.yml`) |
| D2 | `inception/contract-design/contract-summary.md` | Contract Design aprovado | commit `26c9885` acrescentou 9 linhas: o registro da ordenação de `GET /api/v1/bots` e os acréscimos da U9 ao canal ao vivo (envelopes dos eventos, `429 RATE_LIMITED` por teto de canais) | o workflow marca o Contract Design e as etapas seguintes como "a revalidar"; as mudanças são aditivas, e o OpenAPI gerado do código e os testes de contrato da CI seguem verdes | revisar o Contract Design no início do próximo ciclo, antes de qualquer trabalho novo de contrato; pode ser pedido antes a qualquer momento | responsável do projeto |
| D3 | `docker-compose.yml`, `infra/` | `infrastructure-specification.md` das Units e `environment-inventory.md` | igual: portas em `127.0.0.1`, limites de memória, imagem do banco por digest, worker sem porta | nenhum | nenhuma | — |
| D4 | `docs/api/` e `traceability.json` | Build and Test | atualizados na volta 3 | nenhum | nenhuma | — |

## Desvios de configuração esperados (não são defeito)

- Monitoramento: o desenho prevê painéis, alarmes e rastreamento que só existem na AWS. No local, a leitura é manual (`dashboards.md`).
- Backups: o desenho prevê PITR e AWS Backup; no local, não há backup (`incident-plan.md`).

## Verificação contínua (quando a conta existir)

Regras do AWS Config para: criptografia do banco e dos backups, buckets sem acesso público, retenção dos grupos de log, grupos de segurança sem `0.0.0.0/0` fora do balanceador e tags obrigatórias (`env`, `service`, `commit`). Mais a detecção de desvio das pilhas da IaC a cada release.
