# Pipeline — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes: `.github/workflows/ci.yml` atual e `infrastructure-design-questions.md` (Q3, Q4).

## CI

O `ci.yml` não muda. Os jobs que já existem cobrem a U6:

| Job | O que cobre |
|---|---|
| `frontend` e `admin` | lint, com as regras novas das pastas do REAL e o teste delas (U6-NFR8.6); tipos; Jest com o piso de 80% |
| `contract` | `check:api` depois da regeneração dos tipos |
| `backend` | testes da rota nova, do `serverTime` e do CORS |
| `txsigner` | sem mudança (a U6 não toca o TxSigner) |
| `smoke` e `e2e` | `verify.sh` com o `e2e/real-bot-wallet` adaptado à tela nova e o spec `e2e/real-ui`; no push em `main`, os dois jobs rodam o `verify.sh` |

O texto pronto da linha do `verify.sh` está em `infrastructure-specification.md` e vai também no code-summary.

## Implantação

Ver `infrastructure-specification.md`.
- **Ordem:** API, depois web e admin.
- **Rollback:** na ordem inversa, sem migração.
