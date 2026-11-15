# Pipeline — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes: `construction/u4-real-execution/nfr-design/logical-components.md` (testes do TxSigner na CI), `construction/u1-bot-wallet/code-generation/code-summary.md` (texto do job `txsigner`), `construction/u2-real-market-data/code-generation/code-summary.md` (etapa do `promtool`) e `infrastructure-design-questions.md` (Q4).

## CI

Nesta Unit, o `ci.yml` e o `verify.sh` não mudam [Q4]. O que entra:
- **Job `backend`:** os testes da API rodam no Maven `verify` que já existe, com piso de 80% e ArchUnit. Isso inclui os testes de estados, idempotência, queda e concorrência com Testcontainers, os de privilégios do banco e o `RealExecutionAlarmParityTest`.
- **TxSigner:** os testes de ataque da `SwapPolicy` e os de conformidade com os vetores comuns rodam localmente na Code Generation. O resultado fica registrado no code-summary.

Três mudanças ficam para o responsável do projeto aplicar. O texto pronto vai no code-summary:
1. o job `txsigner` com `mvnw verify` e o piso de 80%, pendente desde a U1;
2. o arquivo `real-execution-alerts.yml` na etapa do `promtool`, pendente desde a U2;
3. no `verify.sh`: o spec `e2e/real-swap/`, a espera do `jupiter-stub` e o serviço novo na varredura de log.

**Até essas mudanças entrarem:**
- os testes do TxSigner e as regras de alerta não rodam na CI;
- o swap de ponta a ponta só é provado localmente.

## Implantação

A ordem e o rollback estão em `infrastructure-specification.md`:
- **Ordem:** migração, TxSigner, worker e API.
- **Rollback:** desligar o REAL, esperar as ordens enviadas terminarem e voltar as imagens.
