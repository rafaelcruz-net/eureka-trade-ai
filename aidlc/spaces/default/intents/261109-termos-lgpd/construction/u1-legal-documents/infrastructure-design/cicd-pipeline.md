# Pipeline — U1 `u1-legal-documents`

Conversa: português (Brasil). Usa o pipeline existente (`ci.yml`, `load-test.yml`, `security.yml`) e o `verify.sh`. Implementa `construction/u1-legal-documents/nfr-design/` (`performance-design.md`, `security-design.md`) e a infraestrutura de `infrastructure-specification.md`. Respostas em `infrastructure-design-questions.md` (Q2).

## Pré-requisitos (responsável do projeto)

1. Antes do primeiro commit deste trabalho, aplicar no `ci.yml` as mudanças de `aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/ci-pipeline/ci-config.md`: Jest completo com cobertura no web, job `admin` com lint, tipos, Jest e `check:api`, e `needs` com `admin`.
2. Antes do ponto de controle da U1, acrescentar `e2e/legal-documents` à lista de specs do Playwright no `scripts/verify.sh`.
3. Antes do Build and Test, acrescentar a opção `legal` à lista `options` e à descrição do `.github/workflows/load-test.yml`. O cenário roda do host, contra o ambiente subido com o perfil `loadtest`, cujo limite alto vem de `application-loadtest.yml`.

## Etapas e portões

| Etapa | O que roda | Portão |
|---|---|---|
| backend | build, testes de unidade e integração (inclusive catálogo, ativação, limitador, cabeçalhos de cache, indicador de saúde), JaCoCo ≥ 80% | falha bloqueia o merge |
| frontend (web) | lint, tipos, Jest com cobertura ≥ 80% (inclui `LegalDocument`, `SiteFooter`, páginas) | idem |
| admin | lint, tipos, Jest com cobertura ≥ 80%, `check:api` | idem (depende do pré-requisito 1) |
| contract | OpenAPI gerado igual ao versionado e tipos dos apps regenerados, com as rotas `/api/v1/legal/**` | idem |
| smoke e e2e | `verify.sh` com o spec `e2e/legal-documents` | idem |
| segurança | varredura de segredos, dependências e SAST (existente) | Crítico ou Alto bloqueia |
| carga (manual) | `load-test.yml` com o cenário `legal` como nova opção | reprova com p95 de página > 2 s, API > 300 ms ou 5xx |

## Implantação e rollback

- **Local:** recreate pelo Compose. A faixa fixa de rede exige `docker compose down` antes da primeira subida com ela. Se a faixa padrão colidir com outra rede Docker da máquina ou da CI ("Pool overlaps"), troque `COMPOSE_SUBNET` e `COMPOSE_APPS_SUBNET` no `.env`.
- **Nuvem (desenho):** blue/green do produto; os campos de implantação vêm do Parameter Store, com a exigência de publicação ligada.
- **Rollback:** voltar a imagem anterior. O catálogo vai na imagem, então o rollback traz a versão de texto anterior junto. Nenhuma migração nesta Unit.

## Segredos

Nenhum segredo novo. As variáveis da U1 são configuração e ficam no Parameter Store na nuvem.
