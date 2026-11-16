# Pipeline — U5 `u5-real-operations`

Conversa: português (Brasil). Fontes: `construction/u5-real-operations/nfr-design/security-design.md` (SHA da imagem), `.github/workflows/ci.yml` atual e `infrastructure-design-questions.md` (Q3).

## CI

Esta Unit não edita o `ci.yml` nem o `verify.sh`, que são do responsável do projeto. As mudanças nesses dois arquivos vão com o texto pronto no code-summary:
1. `ci.yml`: `env: BUILD_SHA: ${{ github.sha }}` nos jobs `smoke` e `e2e`. A API recebe o valor pelo `build.args` do `docker-compose.yml`, que esta Unit muda.
2. `ci.yml`: `real-ops-alerts` na lista do passo `Alert rules (promtool)`.
3. `verify.sh`: `--build-arg BUILD_SHA="${BUILD_SHA:-}"` no `docker build` do TxSigner e o spec `e2e/real-ops`.

Nenhum token do GitHub entra na CI. O `verify.sh` usa o `ConfiguredCiStatus` com `GREEN`.

O job `backend` roda os testes da U5:
- unidade e integração com Testcontainers;
- o teste de privilégios e gatilhos da 0021;
- `NoWalletAddressAfterAnonymizationIT`;
- `RealOpsAlarmParityTest`.

## Implantação

Ver `infrastructure-specification.md`:
- **Ordem:** migração 0021, worker, API.
- **Rollback:** desligar o REAL e voltar as imagens; o rollback da 0021 devolve o gatilho da 0005.
