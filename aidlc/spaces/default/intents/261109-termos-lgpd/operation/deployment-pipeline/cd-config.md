# Configuração de entrega contínua (CD)

Conversa: português (Brasil). Respostas em `deployment-pipeline-questions.md` (todas A). Entradas: `construction/ci-pipeline/ci-config.md` e `quality-gates.md`, os `infrastructure-specification.md` e `cicd-pipeline.md` das Units U1 a U5, o `team.md` (Deployment) e a CD do intent anterior (`261026-memetrade-saas-mvp/operation/deployment-pipeline/cd-config.md`), que continua valendo.

## Situação

- Sem conta de nuvem, região nem orçamento (C-10, C-12). O único ambiente é o local, com Docker Compose.
- A entrega de hoje é a release local por tag em `main`. O pipeline da AWS (`deploy.yml` com `publish`, `staging` e `production`) continua só desenhado.

## Matriz de ambientes e promoção

Igual à do intent anterior, com estes ajustes:

| Ambiente | O que muda neste intent |
|---|---|
| Local | o `.env` precisa de `MEMETRADE_AUDIT_WALLET_HMAC_KEY` (64 caracteres hexadecimais aleatórios, nunca no repositório); o smoke é `bash scripts/verify.sh` com os 19 specs |
| Staging (futuro) | a chave HMAC vem do gerenciador de segredos; o catálogo de termos usado é o real revisado, não o de teste |
| Produção do beta PAPER (futuro) | os campos de implantação dos documentos legais preenchidos (armazenamento, transferência, encarregado); sem eles a versão não é publicável e `legalDocuments` fica `DEGRADED` |
| Funções REAL | sem mudança: bloqueadas até G2 e G3 com revisor independente (C-14) |

## Release de hoje (local)

Os passos 1 a 6 do intent anterior, mais:

1. Antes de subir: conferir `MEMETRADE_AUDIT_WALLET_HMAC_KEY` no `.env`.
2. CI e `security.yml` verdes no commit da tag (exceções EX-001 a EX-013 válidas).
3. Registrar na tag a situação do piso de rollback (`rollback-runbook.md`): antes ou depois da primeira anonimização real.
4. Se a release trouxer uma versão nova dos termos: seguir o checklist de `docs/api/u1-legal-documents.md` e avisar os convidados de que todos passarão pela tela de novo aceite.

## Segredos

| Segredo | Onde fica hoje | Onde fica na AWS |
|---|---|---|
| `MEMETRADE_AUDIT_WALLET_HMAC_KEY` | `.env` local (ignorado pelo git); efêmera no `verify.sh` e no `load-test.sh` | gerenciador de segredos, lida só pela `api` e pelo `worker` |
| Os do intent anterior (banco, assinatura de token) | sem mudança | sem mudança |

Nenhum segredo em variável `NEXT_PUBLIC_*` nem no código entregue ao navegador (`project.md`).

## Feature flags

Resposta 5A: sem serviço de flags no beta. REAL continua desligado por configuração padrão; o kill switch da U8 pausa os bots sem nova implantação. A trava de novo aceite não é uma flag: ela vale sempre que a versão vigente muda.

## Pendências para o Environment Provisioning

As do intent anterior, mais:
- gerenciador de segredos com a chave HMAC da trilha;
- orçamento de conexões do banco gerenciado com `DB_POOL_MAX_SIZE` × réplicas da `api`;
- catálogo real dos termos v2 com os campos de implantação preenchidos antes da publicação.
