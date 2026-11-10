# Pipeline — U2 `u2-retention`

Conversa: português (Brasil). A U2 usa o pipeline existente (`ci.yml`, `security.yml`, `load-test.yml`) e o `verify.sh`. Implementa `construction/u2-retention/nfr-design/` e a infraestrutura de `infrastructure-specification.md`.

## Pré-requisitos (responsável do projeto)

1. **`scripts/verify.sh`:** gerar e exportar uma chave efêmera `MEMETRADE_AUDIT_WALLET_HMAC_KEY` fora do bloco `if [ ! -f .env ]`, com `${MEMETRADE_AUDIT_WALLET_HMAC_KEY:-$(openssl rand -hex 32)}`, antes do ponto de controle da U2. Sem ela, `api` e `worker` não sobem.
2. **`.github/workflows/ci.yml`:** acrescentar `MEMETRADE_AUDIT_WALLET_HMAC_KEY` com valor de exemplo de 64 caracteres ao `env` do passo `Compose config` do job `backend`, antes do primeiro commit com a U2. Os jobs `smoke` e `e2e` usam o `verify.sh` e herdam o item 1.
3. **`.env` local:** acrescentar uma vez a chave, com 64 caracteres hexadecimais aleatórios.

A geração de código acrescenta a mesma geração efêmera a `scripts/load-test.sh` e a `scripts/perf-audit.sh`. A tabela completa está em `infrastructure-specification.md`.

## Etapas e portões

| Etapa | O que roda | Portão |
|---|---|---|
| backend | build, testes de unidade e integração (expurgos com relógio injetável, HMAC, validador, relay, recuperação, indicadores, métricas, teste de desempenho do expurgo com 100 mil linhas, teste de conformidade da retenção, teste da migração aditiva) e JaCoCo ≥ 80% | falha bloqueia o merge |
| contract | OpenAPI sem mudança (a U2 não muda rota) | idem |
| smoke e e2e | `verify.sh` com a chave efêmera; a busca do `verify.sh` por carteira completa nos logs continua | idem |
| segurança | varredura de segredos (a chave nunca entra no repositório), dependências e SAST | Crítico ou Alto bloqueia |
| carga (manual) | cenários `u1` e `u3` para conferir que o login não piorou (NFR3.4) | reprova acima das metas atuais |

## Implantação e rollback

- **Local:** recreate pelo Compose. A migração `0012` roda no serviço de migração existente.
- **Nuvem (desenho):** blue/green do produto. A chave vem do Secrets Manager.
- **Rollback:** voltar a imagem anterior. A migração é só aditiva, então a imagem anterior ignora a coluna, a tabela e os índices novos. O rollback do Liquibase está declarado, mas não é necessário.

## Segredos

Há um segredo novo, `MEMETRADE_AUDIT_WALLET_HMAC_KEY`. Ele fica fora do repositório (só o nome no `.env.example`), nunca vai para `NEXT_PUBLIC_*` e é bloqueado pelo gitleaks no pré-commit e na CI.
