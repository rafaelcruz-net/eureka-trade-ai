# Runbook de rollback

Conversa: português (Brasil). Resposta 3A de `deployment-pipeline-questions.md`. O runbook base é o do intent anterior (`261026-memetrade-saas-mvp/operation/deployment-pipeline/rollback-runbook.md`): rollback só de imagens, sem desfazer migração, com ensaio antes de cada release. Aqui ficam as regras novas deste intent.

## Quando fazer rollback

Os mesmos sinais do runbook base, mais:

| Sinal | Ação |
|---|---|
| A `api` ou o `worker` não sobem por falta ou erro de `MEMETRADE_AUDIT_WALLET_HMAC_KEY` | não é rollback: corrigir a chave no `.env` ou no gerenciador de segredos e subir de novo |
| `legalDocuments` em `DEGRADED` (versão não publicável) | não derruba a API; corrigir o catálogo numa release seguinte |
| Trava de novo aceite bloqueando quem já aceitou (aceite registrado e `403` continua) | rollback da imagem e investigação |
| Anonimização com falha (contador `memetrade_account_anonymization_failures_total` subindo) | parar novas anonimizações; correção para frente (ver piso abaixo) |

## Piso de rollback

| Situação | Até onde pode voltar |
|---|---|
| Antes da primeira anonimização real | qualquer imagem a partir da U4 do intent anterior; o Liquibase também pode voltar a `0015` |
| Depois da primeira anonimização real | **nenhuma imagem anterior à U5**: só correção para frente. Nenhuma imagem antiga trata carteira nula |
| Depois do primeiro pedido real do titular (U4) | o rollback do Liquibase da `0014` não é usado (apagaria pedidos); rollback de imagem continua possível |
| Depois do primeiro novo aceite (U3) | a `0013` não é desfeita; rollback de imagem continua possível |

O registro de cada release diz em qual situação ela está.

## Rollback local

Os passos 1 a 7 do runbook base, com dois cuidados:

1. Antes do passo 3, confirmar na tabela acima que a tag de destino respeita o piso.
2. O `.env` precisa ter `MEMETRADE_AUDIT_WALLET_HMAC_KEY`; uma imagem anterior à U2 não usa a chave, mas o Compose atual a exige.

## A chave HMAC da trilha

- Não tem rotação no beta. A troca só acontece por incidente (vazamento) e quebra a correlação entre eventos antes e depois da troca.
- O procedimento está em `docs/privacy/u2-retencao-e-auditoria.md`, seção "Runbook: troca da chave HMAC da auditoria". A Incident Response acrescenta o passo ao RB-09.

## Ensaio (antes de cada release)

O ensaio do runbook base, com uma condição: **só antes da primeira anonimização real**. Depois dela, o ensaio vira: subir a release candidata, rodar `bash scripts/verify.sh`, e conferir que a correção para frente sobe sobre o banco atual. O `verify.sh` cobre a anonimização com uma conta descartável do perfil `test`.

## Contatos e escalonamento

Os mesmos do runbook base: a decisão de rollback é do responsável do projeto; incidente P1/P2 exige revisão pós-incidente com linha do tempo, causa raiz e prevenção (`phases/operation.md`).
