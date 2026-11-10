# Pipeline — U4 `u4-data-requests`

Conversa: português (Brasil). A U4 usa o pipeline existente (`ci.yml`, `security.yml`, `load-test.yml`) e o `verify.sh`. Ela implementa `construction/u4-data-requests/nfr-design/` e a infraestrutura de `infrastructure-specification.md`.

## Pré-requisitos (responsável do projeto)

1. **`scripts/verify.sh`:** antes do ponto de controle da U4, a lista de specs do Playwright precisa terminar assim, já com o spec da U3 que ainda falta:
   ```
   ... e2e/u3-identity-access e2e/legal-documents e2e/reacceptance e2e/data-requests
   ```
2. **`.github/workflows/load-test.yml`:** antes do Build and Test, a lista `options` ganha `- reacceptance` (U3) e `- data-requests`. A descrição termina em `..., u5, legal, reacceptance or data-requests)`.

O `scripts/load-test.sh`, o `scripts/load/load-test.mjs` e o `scripts/load/data-requests.mjs` mudam na geração de código.

## Etapas e portões

| Etapa | O que roda | Portão |
|---|---|---|
| backend | testes de unidade e de integração (criação, isolamento, transições, verificação, assinatura recusada no login, vencimento, expurgo, limite, idempotência, migração, métricas, `LogScanIT` com as agulhas novas) e JaCoCo ≥ 80% | falha bloqueia o merge |
| frontend (web e admin) | lint, tipos, Jest com cobertura ≥ 80% (T5, T6 a T6c) | idem |
| contract | OpenAPI de P1 e P2 (sem a rota de anonimização), com os tipos gerados nos dois apps; blocos P1, P2 e J1 do resumo de contratos no mesmo commit | idem |
| smoke e e2e | `verify.sh` com `e2e/data-requests` | idem |
| segurança | varredura de segredos, dependências e SAST | Crítico ou Alto bloqueia |
| carga (manual) | cenário `data-requests` | reprova acima das metas |

## Implantação e rollback

- **Local:** recreate pelo Compose; a migração `0014` roda no serviço de migração.
- **Rollback:** voltar a imagem anterior. As tabelas novas ficam e são ignoradas. O rollback do Liquibase só serve antes do primeiro pedido real, porque apagaria pedidos.

## Segredos

Nenhum segredo novo.
