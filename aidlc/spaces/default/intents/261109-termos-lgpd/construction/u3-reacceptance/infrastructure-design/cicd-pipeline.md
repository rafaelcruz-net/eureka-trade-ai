# Pipeline — U3 `u3-reacceptance`

Conversa: português (Brasil). A U3 usa o pipeline existente (`ci.yml`, `security.yml`, `load-test.yml`) e o `verify.sh`. Ela implementa `construction/u3-reacceptance/nfr-design/` e a infraestrutura de `infrastructure-specification.md`.

## Pré-requisitos (responsável do projeto)

1. **`scripts/verify.sh`:** antes do ponto de controle da U3, acrescentar `e2e/reacceptance` ao fim da lista de specs da linha do Playwright:
   ```
   ... e2e/u3-identity-access e2e/legal-documents e2e/reacceptance
   ```
   Não é preciso nenhuma variável nova: o spec usa o `DATABASE_URL` que o script já passa.
2. **`.github/workflows/load-test.yml`:** antes do Build and Test, acrescentar `- reacceptance` à lista `options` e trocar o fim da descrição por `..., u5, legal or reacceptance)`.

O `scripts/load-test.sh`, o `scripts/load/load-test.mjs`, o `scripts/load/reacceptance.mjs` e o `SmokeAccountSeeder` mudam na geração de código. Os detalhes estão em `infrastructure-specification.md`, seção "Dados de teste para a trava".

## Etapas e portões

| Etapa | O que roda | Portão |
|---|---|---|
| backend | testes de unidade e de integração (trava, aceite, idempotência, `409`, teste de todas as rotas do convidado, banco parado, migração, métricas) e JaCoCo ≥ 80% | falha bloqueia o merge |
| frontend (web) | lint, tipos, Jest com cobertura ≥ 80% (`ReacceptanceScreen`, `TermsConsentBoxes`, `LegalSummary`, `TermsStatusPanel`, tratador do `403`) | idem |
| contract | OpenAPI com `POST /api/v1/terms/acceptances`, o campo `terms` da Conta e `currentVersion` no problema; os tipos gerados batem com o `schema.d.ts` | idem |
| smoke e e2e | `verify.sh` com o spec `e2e/reacceptance`: a pessoa desatualizada recebe a tela de novo aceite, aceita e volta | idem |
| segurança | varredura de segredos, dependências e SAST (existente) | Crítico ou Alto bloqueia |
| carga (manual) | cenário `reacceptance` | reprova com p95 do aceite acima de 500 ms ou da trava acima de 5 ms |

## Implantação e rollback

- **Local:** recreate pelo Compose; a migração `0013` roda no serviço de migração.
- **Rollback:** voltar a imagem anterior. A migração não é desfeita depois do primeiro novo aceite. A imagem anterior ignora a coluna `source` (que tem padrão) e o nulo em `invite_id` só aparece em aceites novos.
- **Publicar uma versão nova dos termos:** é uma implantação com a nova pasta no catálogo, revisada, e ativa a trava para todos. O checklist está em `docs/api/u1-legal-documents.md`.

## Segredos

Nenhum segredo novo.
