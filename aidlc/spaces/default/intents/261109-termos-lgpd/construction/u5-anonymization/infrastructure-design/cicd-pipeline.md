# Pipeline — U5 `u5-anonymization`

Conversa: português (Brasil). A U5 usa o pipeline existente (`ci.yml`, `security.yml`, `load-test.yml`) e o `verify.sh`. Ela implementa `construction/u5-anonymization/nfr-design/` e a infraestrutura de `infrastructure-specification.md`.

## Pré-requisitos (responsável do projeto)

A U5 traz um pré-requisito novo no `verify.sh`, e continuam pendentes os das Units anteriores, que valem também para o ponto de controle da U5:
1. **`scripts/verify.sh`:**
   - exportar `MEMETRADE_AUDIT_WALLET_HMAC_KEY` fora do bloco do `.env` (U2);
   - gerar um segundo código de convite e exportá-lo como `SMOKE_ANONYMIZATION_INVITE_CODE`, do mesmo jeito que gera `SMOKE_INVITE_CODE` (U5);
   - a lista de specs do Playwright termina em `... e2e/legal-documents e2e/reacceptance e2e/data-requests e2e/anonymization`.
2. **`.github/workflows/ci.yml`:** `MEMETRADE_AUDIT_WALLET_HMAC_KEY` no passo `Compose config`.
3. **`.github/workflows/load-test.yml`:** as opções `reacceptance` e `data-requests`.

Enquanto eles faltam, o ponto de controle roda com uma chave efêmera exportada só no ambiente do comando, e o spec `e2e/anonymization` fica fora até o responsável do projeto acrescentá-lo; essa lacuna é registrada no resumo do código.

## Etapas e portões

| Etapa | O que roda | Portão |
|---|---|---|
| backend | testes de unidade e de integração da U5: validações e recusas, transação única, falha em cada um dos cinco pontos, repetição, concorrência (login, renovação, ciclo de bot), `lock_timeout`, migração e rollback, `CHECK`, função de feedback depois de duas execuções do preparo de papéis, índices com `EXPLAIN`, latência, métricas, auditoria, `AnonymizationNoWalletLeftIT`, `LogScanIT`; `U8PrivilegesIT` e `RolesBootstrapIT` atualizados; JaCoCo ≥ 80% | falha bloqueia o merge |
| frontend (admin) | lint, tipos, Jest com cobertura ≥ 80% (`AnonymizeAccountButton`, `AnonymizeAccountDialog`, listagem com "Conta anonimizada") | idem |
| contract | OpenAPI de P2 com a rota de anonimização, tipos gerados no admin; blocos J1 a J3 e J6 do resumo de contratos no mesmo commit | idem |
| smoke e e2e | `verify.sh` com `e2e/anonymization` (conta descartável, ver `infrastructure-specification.md`) | idem |
| segurança | varredura de segredos, dependências e SAST | Crítico ou Alto bloqueia |
| carga | sem cenário novo | — |

## Implantação e rollback

- **Local:** recreate pelo Compose; o `db-bootstrap` aplica o papel novo e a migração `0015` roda no serviço de migração.
- **Rollback da imagem antes da primeira anonimização:** voltar a imagem anterior funciona sobre o esquema `0015`, porque todas as linhas de `wallet` ainda têm endereço e provedor, a imagem antiga não lê `anonymized_at` e o `CHECK` de status mais largo não a afeta. O `MigrationsBackwardCompatIT` é estendido para a `0015`: aceita o relaxamento de `NOT NULL` em `wallet.address` e `wallet.provider` (só relaxar é permitido) e prova que login, renovação e a listagem de contas da imagem anterior continuam funcionando sobre o esquema novo sem conta anonimizada.
- **Depois da primeira anonimização:** não há rollback de imagem nem de Liquibase. Nenhuma imagem anterior à U5 trata carteira nula, então o caminho é só **correção para frente**: o piso de rollback passa a ser a própria imagem da U5. O registro de release da U5 diz isso.
- **Rollback do Liquibase:** só antes da primeira anonimização, porque voltaria o `NOT NULL` da carteira.

## Segredos

Nenhum segredo novo.
