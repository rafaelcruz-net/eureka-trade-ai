# Retenção, expurgos e auditoria sem carteira completa (U2 do intent `261109-termos-lgpd`)

Esta página reúne o que a U2 `u2-retention` mudou e o que precisa ser conferido antes de abrir o beta. Os nomes de propriedades, tabelas, métricas e eventos estão em inglês, como no código.

## O que mudou

- **Auditoria com carteira só mascarada e HMAC (BR2.1 a BR2.4).** Um evento que envolve uma carteira leva o endereço num campo próprio do `AuditEventCommand`, que nunca é gravado. O publicador grava no `actor` a forma mascarada (4 + 4 caracteres) e põe no payload de `audit_outbox` o campo `walletHmac`: o HMAC-SHA-256 do endereço com a chave `MEMETRADE_AUDIT_WALLET_HMAC_KEY`, em hexadecimal minúsculo. O relay copia esse valor para a coluna `audit_event.wallet_hmac`. A mesma carteira gera o mesmo HMAC enquanto a chave não muda.
- **Recusa de endereço completo (BR2.3).** O publicador recusa, com o motivo `FULL_WALLET`, um evento que tenha em `details` ou no `actor` uma palavra Base58 de 32 a 44 caracteres que decodifica para 32 bytes. O log registra o campo e o motivo, nunca o valor (alerta `AUDIT_FULL_WALLET_REJECTED`).
- **Login recusado com texto que não é carteira (BR2.15).** O evento leva `actor=anonymous`, nenhum `walletHmac` e `details.addressFormat=INVALID`. O texto recebido nunca é gravado.
- **Expurgos novos do Identity (BR2.5 a BR2.8, BR2.16).**
  - `loginChallengePurge`, às 03:20 UTC: apaga os desafios de login 24 horas depois de expirarem, em qualquer estado.
  - `sessionPurge`, às 03:40 UTC: apaga as sessões (de convidado e de administrador) 30 dias depois do fim, e antes os digests de renovação usados delas. O fim é `revoked_at` quando a sessão foi revogada; senão, o menor entre os dois vencimentos.
  - Os dois rodam só no processo `api`, uma vez entre as instâncias (ShedLock, 35 e 5 minutos), em lotes de até 1000 linhas, cada lote numa transação. No fim publicam `LOGIN_CHALLENGES_PURGED` ou `SESSIONS_PURGED` pelo `system`, só com contagens, e gravam a execução em `identity_job_run`.
  - A saúde fica nos componentes `loginChallengePurge` e `sessionPurge`. Eles ficam `DEGRADED` (HTTP 200) quando passam mais de 48 horas sem sucesso e mais de 48 horas desde a subida. Na subida, um expurgo sem linha, sem sucesso ou com sucesso há mais de 24 horas roda na hora.
  - Métricas: `memetrade_purge_deleted_total{purge}`, `memetrade_purge_runs_total{purge,result}`, `memetrade_purge_last_success_seconds{purge}` e `memetrade_purge_batch_duration_seconds{purge}`.
- **Catálogo de retenção do Privacy (BR2.9 a BR2.12, BR2.17).** O módulo novo `com.memetrade.privacy` tem o `RetentionCatalog`, com as 12 linhas da política. A tabela de `/api/v1/legal/**` vem dele (porta J5). Uma versão vigente legacy continua sem tabela (BR1.15). Cada expurgo declara o prazo que aplica pela interface compartilhada `RetentionEnforcerDeclaration` (contrato J7). O `RetentionConformanceTest` falha quando o catálogo, as declarações, a lista de pendências ou o `retention.tsv` de uma versão não legacy divergem.
- **Pool do agendador.** O processo `api` passa a ter 18 jobs agendados. O padrão de `SCHEDULER_POOL_SIZE` é 18, e a API recusa menos que isso.

## Checklist de abertura do beta (BR2.13, AC3.2.3)

Some estes itens ao checklist de `docs/api/u1-legal-documents.md`:

1. **Banco novo.** Os dados de desenvolvimento e de teste são descartados antes da abertura. A migração `0012` não reescreve eventos antigos (BR2.14); por isso eles precisam ficar fora do banco do beta.
2. **Chave no gerenciador de segredos.** `MEMETRADE_AUDIT_WALLET_HMAC_KEY` existe no gerenciador de segredos (Secrets Manager na nuvem), com pelo menos 32 bytes (por exemplo, 64 caracteres hexadecimais aleatórios). A chave é a mesma para `api` e `worker` e nunca entra no repositório, em `NEXT_PUBLIC_*` ou em log.
3. **Consultas de conferência vazias.** Rodar as duas consultas abaixo no banco do beta, depois do primeiro dia de uso. As duas precisam devolver `0`. Elas procuram palavras com a forma de um endereço Base58 completo. Se alguma devolver mais que zero, não copie valores para fora do banco: abra um incidente e investigue a ação (`action`) dos eventos encontrados.

```sql
-- Trilha: nenhum endereço completo em details nem no actor.
select count(*) from audit_event
where details::text ~ '(^|[^0-9A-Za-z])[1-9A-HJ-NP-Za-km-z]{32,44}([^0-9A-Za-z]|$)'
   or actor ~ '(^|[^0-9A-Za-z])[1-9A-HJ-NP-Za-km-z]{32,44}([^0-9A-Za-z]|$)';

-- Saída da trilha: nenhum endereço completo no payload.
select count(*) from audit_outbox
where payload::text ~ '(^|[^0-9A-Za-z])[1-9A-HJ-NP-Za-km-z]{32,44}([^0-9A-Za-z]|$)';
```

## Runbook: troca da chave HMAC da auditoria (BR2.19, NFR6.3)

Não há rotação programada no beta. A chave só muda por incidente (vazamento ou suspeita de vazamento), como parte do RB-09 "Suspeita de vazamento de segredo".

1. **Efeito antes de trocar.** Com a chave nova, a mesma carteira passa a gerar outro `walletHmac`. Os eventos anteriores e os posteriores à troca deixam de ser ligados pelo HMAC. Os eventos antigos não são reescritos (BR2.14).
2. **Registrar o instante.** Anote no registro do incidente (`docs/incidents/AAAA-MM-DD-<slug>.md`) o instante UTC da troca. Toda busca por carteira na trilha passa a precisar das duas chaves, separadas por esse instante.
3. **Gerar e guardar a chave nova.** Gere 64 caracteres hexadecimais aleatórios (`openssl rand -hex 32`) e grave no gerenciador de segredos, ou no `.env` local. Nunca cole a chave em chat, ticket ou log.
4. **Reiniciar `api` e `worker` juntos.** Os dois validam a chave na subida e não sobem sem ela. Conferir que o componente `auditRelay` fica `UP` e que `audit_publish_rejected_total` não cresce.
5. **Descartar a chave antiga.** Guarde a chave antiga só pelo tempo que o incidente exigir para ligar eventos antigos. Depois, apague-a do gerenciador de segredos.
6. **Pendência registrada.** O tratamento do `walletHmac` e do `actor` mascarado na anonimização fica para a `u5-anonymization`.

## Pré-requisitos do responsável do projeto

Estes arquivos são do responsável do projeto, e a geração de código não os alterou:

1. **`scripts/verify.sh`:** gerar e exportar a chave efêmera **fora** do bloco `if [ ! -f .env ]`, antes do ponto de controle da U2:

   ```sh
   export MEMETRADE_AUDIT_WALLET_HMAC_KEY="${MEMETRADE_AUDIT_WALLET_HMAC_KEY:-$(openssl rand -hex 32)}"
   ```

   Sem ela, o `docker compose` recusa subir `api` e `worker` (a variável é obrigatória).
2. **`.github/workflows/ci.yml`:** acrescentar ao `env` do passo `Compose config` do job `backend`, antes do primeiro commit com a U2:

   ```yaml
   MEMETRADE_AUDIT_WALLET_HMAC_KEY: 0000000000000000000000000000000000000000000000000000000000000000
   ```

3. **`.env` local:** acrescentar uma vez `MEMETRADE_AUDIT_WALLET_HMAC_KEY=` com 64 caracteres hexadecimais aleatórios.
4. **`scripts/perf-audit.sh`:** acrescentar, junto dos outros segredos efêmeros (depois de `ACCESS_TOKEN_SIGNING_KEY`), a mesma linha do item 1. A geração de código não conseguiu editar esse arquivo: o harness bloqueou a escrita. O `scripts/load-test.sh` já tem a linha. Depois da mudança, inclua o `perf-audit.sh` na lista do teste `ComposeFilesTest.theLoadScriptGeneratesAnEphemeralKey`.
5. **Runbook de incidentes:** acrescentar ao RB-09 de `aidlc/spaces/default/intents/261026-memetrade-saas-mvp/operation/incident-response/runbooks.md` um passo com link para a seção "Runbook: troca da chave HMAC da auditoria" desta página. A geração de código não edita registros de outro intent.

## Manutenção do catálogo de retenção

- **Mudar um prazo** é mudar o código e a política juntos. Altere a linha em `RetentionCatalog`, a propriedade do expurgo (que alimenta a declaração dele) e o `retention.tsv` de cada versão não legacy. O `RetentionConformanceTest` falha enquanto os três não concordarem e aponta a linha, o prazo declarado e o prazo aplicado.
- **Entregar um expurgo planejado** (por exemplo, a U4 com os pedidos de titular): registre a declaração `RetentionEnforcerDeclaration` do expurgo, troque a linha de `PLANNED` para `PURGE` e retire a linha de `PendingRetention`, no mesmo commit. O teste falha se a declaração existir com a linha ainda pendente.
