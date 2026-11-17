# Pipeline — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes: `.github/workflows/ci.yml` atual e `infrastructure-design-questions.md` (Q3, Q4).

## CI

Esta Unit não edita o `ci.yml` nem o `verify.sh`, que são do responsável do projeto. Os textos prontos estão em `infrastructure-specification.md` e vão também no code-summary:
- o `promtool` com `real-exits-alerts`;
- os specs `e2e/real-exports` e `e2e/real-exits` no `verify.sh`.

Os jobs que já existem cobrem a U7:

| Job | O que cobre |
|---|---|
| `backend` | testes da `realexits`, das interfaces de extensão, da migração 0022, do limitador e da paridade dos alarmes |
| `txsigner` | testes de `POLICY_NOT_RECORDED` e da carga do arquivo; depois da gravação, os testes de ataque da `TriggerPolicy` |
| `frontend`, `admin`, `contract` | telas, tipos regenerados e `check:api` |
| `smoke` e `e2e` | `verify.sh` com os specs novos |

Nenhum segredo da Jupiter entra na CI.

## Implantação

Ver `infrastructure-specification.md`.
- **Ordem:** migração 0022, worker e API, TxSigner, web e admin.
- **Rollback:** na ordem inversa, com a regra da tabela vazia na 0022.
