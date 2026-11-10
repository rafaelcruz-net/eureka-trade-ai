# Resumo do código — U2 `u2-retention`

Conversa: português (Brasil). Código, nomes e logs em inglês. Os passos 1 a 9 e 11 de `code-generation-plan.md` estão concluídos; os passos 10 e 12 estão parciais (ver "Pendências"). A lista de arquivos está em `source-manifest.json` e a ligação com os requisitos em `traceability.json`.

## Arquivos criados e alterados

- **Migração `0012-retention.sql`** (só aditiva, com rollback): coluna e índice parcial `audit_event.wallet_hmac`, tabela `identity_job_run` e índice de expressão `user_session_end_idx`.
- **Audit:**
  - `WalletHasher` (HMAC-SHA-256 com `Mac` por thread), `WalletHmacKeySanitizer` e `AuditWalletConfig`;
  - `AuditEventCommand` com o campo `wallet`, que nunca é serializado, e as fábricas `anonymousWallet`/`withWallet`; as fábricas antigas continuam;
  - validador com a recusa `FULL_WALLET` em `details` e no `actor`;
  - codec com `DecodedAuditEvent`, relay que grava `wallet_hmac` e publicador com `walletHmac` no payload.
- **Identity:**
  - domínio `identity/domain/purge/*`;
  - `IdentityJobRunner` (ShedLock de 35 e 5 minutos, lotes `REQUIRES_NEW`, evento e linha da execução numa transação curta, métricas, `FAILED` sem propagar);
  - `LoginChallengePurgeJob` (03:20 UTC) e `SessionPurgeJob` (03:40 UTC);
  - `JdbcIdentityJobRunStore`, `PurgeHealthIndicator` (`loginChallengePurge`, `sessionPurge`) e `IdentityJobRecovery`;
  - logins e convite passam a usar o campo `wallet`; um endereço inválido vira `anonymous` com `addressFormat=INVALID`.
- **Retenção:**
  - contrato `shared/retention` (`RetentionEnforcerDeclaration`, `RetentionTerm`, `RetentionAnchor`) e uma declaração por expurgo em Audit, Feedback, Bots, RiskControl e Identity;
  - módulo novo `privacy`: `RetentionCatalog` (12 linhas), `PendingRetention`, `RetentionConformance` e `PrivacyRetentionTableSource`, que implementa a J5 e vence a implementação padrão da U1;
  - bloco J7 no resumo de contratos.
- **Configuração:** pool do agendador 18 (`application.yml`, `RuntimeSettingsGuard`, `docker-compose.yml`, `.env.example`); chave obrigatória `MEMETRADE_AUDIT_WALLET_HMAC_KEY` em `api` e `worker`; geração efêmera da chave em `scripts/load-test.sh`.
- **Documentação:** `docs/privacy/u2-retencao-e-auditoria.md`, com o checklist de abertura, as consultas de conferência, o runbook de troca da chave e os pré-requisitos; item 7 no checklist de `docs/api/u1-legal-documents.md`.

## Testes executados

| Comando | Resultado |
|---|---|
| Testes do Passo 2 antes do Passo 3 (Red) | falharam por compilação: faltavam `IdentityPurge`, `PurgeRun`, `PurgeHealthPolicy`, `IdentityJobRunner` e os jobs |
| Testes do Passo 5 antes do Passo 6 (Red) | falharam por compilação: faltavam `WalletHasher`, `Reason.FULL_WALLET` e `AuditEventCommand.wallet()` |
| Comando da Unit (`unit-test-instructions.md`) | 147 de unidade e 20 de integração, 0 falhas |
| `sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml clean verify` | saída 0, com JaCoCo `check`, Spotless e failsafe; cobertura de linhas do backend **93,76%** (classes da U2: 96,81%) |

O `clean verify` precisou de três rodadas. As duas primeiras pararam em testes quebrados pela própria U2 (`RouteRegistryTest`, `CurrentTermsActivationTest`, `ProcessRoleContextIT`), que foram corrigidos.

O teste de desempenho `SessionPurgePerformanceIT` rodou com 100 mil sessões e 100 mil desafios:
- p95 por lote dentro de 500 ms;
- total dentro de 5 minutos;
- o plano da consulta usa `user_session_end_idx`.

## Testes escritos e não executados nesta etapa

- **`bash scripts/verify.sh`:** roda no ponto de controle da U2, depois dos pré-requisitos do responsável.
- **Cenários de carga `u1` e `u3` (NFR3.4):** ficam para o Build and Test.

## Desvios do plano

1. **Chave de teste.** Ficou em `apps/api/src/test/resources/config/application.yml`, só no classpath de teste, e não em `application-test.yml`. Os testes de integração não ativam o perfil `test`, e o arquivo de teste esconderia o de produção de mesmo nome.
2. **Validação da chave.** É feita no construtor do `WalletHasher`, sem `@Validated`. O analisador de falha do Spring imprimiria o valor recusado; a mensagem atual nomeia a propriedade e a variável, nunca o valor.
3. **Recusa `FULL_WALLET`.** Só vale para uma palavra alfanumérica inteira de 32 a 44 caracteres Base58 que decodifica para 32 bytes. Delimitar por caractere fora do alfabeto daria falso positivo em cerca de 10% dos hashes hexadecimais; há teste para esse caso. O `actor` também é conferido.
4. **Evento `*_PURGED` também na falha.** O evento sai com `result` nos detalhes, no padrão do `AUDIT_RETENTION_PURGED`.
5. **Versão vigente legacy.** Continua sem tabela de retenção (BR1.15); as outras versões mostram o catálogo do Privacy.
6. **Prazo declarado do `riskcontrol-purge`.** É o `trigger-age` (30 dias), não a margem de seleção de 31 dias.
7. **Id do expurgo dos pedidos.** O id `dataSubjectRequestPurge`, ainda pendente, foi escolhido agora; a U4 o adota ou renomeia.
8. **Testes ajustados além do plano.** Seis outros testes fixavam o pool em 16 (`U4SettingsTest`, `U6SettingsTest`, `U7SettingsTest`, `U8SettingsTest`, `U6ContextIT`, `U7ContextIT`). Também mudaram:
   - `route-registry.yml` e `ProcessRoleContextIT`, com os jobs novos;
   - `LogScanIT`, agora mais rígido: nenhuma carteira em `details`;
   - `OutboxEvents`, que busca pelo HMAC.

## Pendências

1. **`scripts/perf-audit.sh`:** a edição foi bloqueada para o agente. O responsável acrescenta a mesma geração efêmera da chave usada no `load-test.sh`.
2. **Link do runbook:** o runbook RB-09 do intent `261026` precisa de um link para o passo "troca da chave HMAC" em `docs/privacy`.
3. **Pré-requisitos do ponto de controle:**
   - `scripts/verify.sh`: chave efêmera fora do bloco `if [ ! -f .env ]`;
   - `.github/workflows/ci.yml`: valor de exemplo no passo `Compose config`;
   - `.env` local: a chave.

   Sem eles, a API e o worker não sobem.
