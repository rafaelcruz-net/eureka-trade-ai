# Análise de custos

Conversa: português (Brasil). Resposta 2A. Sem conta de nuvem (C-10, C-12): o custo de infraestrutura é zero. O único consumo é o GitHub Actions, num repositório privado no plano gratuito.

## GitHub Actions

| Workflow | Duração típica neste intent | Observação |
|---|---|---|
| `ci` | cerca de 15 a 20 min (o `smoke` e o `e2e` sobem o Compose inteiro) | roda em todo push e pull request |
| `security` | cerca de 1 h, quase todo no job `dependencies` | sem `NVD_API_KEY`, o Dependency-Check baixa a base da NVD devagar; o cache ajuda nas execuções seguintes |
| `load-test` | sob demanda | não roda em push |

Em 2026-11-11 e 2026-11-12, o repositório rodou várias execuções completas de `ci` e `security` por causa das correções deste intent. O uso de novembro ainda estava dentro da cota gratuita (`gh api users/<conta>/settings/billing/usage`, sem custo líquido).

## Recomendações

1. **Cadastrar a `NVD_API_KEY`** (gratuita) como segredo do repositório: o `security.yml` já lê a variável, e o job `dependencies` cai de cerca de 1 h para poucos minutos.
2. Acompanhar a cota de minutos a cada mês; a regra da CI em todo push é do `team.md` e não muda.
3. Quando houver AWS, estimar o custo do staging e da produção do beta antes de criar a conta (desenho de `operation/deployment-pipeline/cd-config.md`).
