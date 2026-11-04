## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-04T04:54:10Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Medium | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u3-identity-access/code-generation/code-summary.md > "Mudança pendente do responsável do projeto no `scripts/verify.sh`" e traceability.json > NFR3.4 (Deferred) | O smoke de ponta a ponta da U3 (`apps/web/e2e/u3-identity-access/smoke.spec.ts`) foi escrito mas nunca rodou, e o `scripts/verify.sh` ainda não o chama. A única evidência de ponta a ponta do convite até a sessão, e da ausência do código no log da `api`, é de testes de integração. A tentativa de rodar `bash scripts/verify.sh` nesta revisão foi bloqueada pelo ambiente, então o esqueleto da U1 também não foi reconfirmado aqui. | Antes de aprovar o ponto de controle da U3, aplicar a mudança preparada em `docs/api/u3-convites-contas-e-sessoes.md` ao `scripts/verify.sh`, rodar `bash scripts/verify.sh` e registrar o resultado. | New |
| R-02 | Medium | apps/api/src/main/resources/terms/v1 (`terms.txt`, `risk-notice.txt`, `version.properties`) | O texto dos termos e do aviso de risco é uma "versão provisória, pendente de revisão jurídica", e já é a versão vigente que a API exige no aceite (BR3.1) e grava em `terms_acceptance`. Contas de beta criadas com esse texto ficam vinculadas a uma versão que ainda não foi revisada. | Registrar um dono e um prazo para a revisão jurídica e publicar uma `v2` revisada antes de convidar usuários fora do ambiente local. Não alterar o texto de uma versão que já tenha aceites. | New |
| R-03 | Low | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u3-identity-access/code-generation/code-summary.md > "Testes escritos e não executados" (NFR2.4, NFR3.3) | Os critérios de p95 de 100 ms das contagens e das rotas administrativas são verificados por média de 10 ações, que não prova um p95. O cenário de carga `LOAD_SCENARIO=u3` (NFR3.1, NFR3.2) também não rodou. | Manter NFR2.4, NFR3.1, NFR3.2 e NFR3.3 como pendentes de medição no Build and Test e não tratá-los como atendidos antes disso. | New |
| R-04 | Low | apps/api/src/main/java/com/memetrade/identity/web/AdminAuthorization.java, apps/api/src/main/resources/tenancy/route-registry.yml, application.yml, ScannerScreen | Vários caminhos do `source-manifest.json` têm no mesmo arquivo alterações não commitadas da U4 (`refuseOnGuestRoute`, rota de avaliação do token, `ScannerRateLimitEvictionJob`), e o Spotless reformatou arquivos da U4. A mudança da U3 não se separa por arquivo. | Ao integrar, separar o diff da U3 do da U4 (por hunk) para o commit da Unit não carregar trabalho da U4 sem revisão. | New |
| R-05 | Low | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u3-identity-access/code-generation/code-summary.md > "Desvios do plano" (driver PostgreSQL, `sed -i`, `ErrorCode`) | O driver PostgreSQL com escopo `compile` só é usado em `infra/JdbcGuestAccountRepository` (reconhecimento da restrição por nome), sem defeito. O `sed -i` no `ProblemResponse.java` foi declarado e o resultado está verde. `ErrorCode.code()` só muda o código de máquina do 422 para `VALIDATION_FAILED`, conforme o contrato C8. Nenhum dos três é defeito real. | Nenhuma ação de código. Manter a regra de arquitetura que impede o domínio de importar o driver. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u3 test-compile surefire:test failsafe:integration-test failsafe:verify` | PASS: "Tests run: 50, Failures: 0, Errors: 0, Skipped: 0" (unitários) e "Tests run: 83, Failures: 0, Errors: 0, Skipped: 0" (integração); BUILD SUCCESS | Confere com o `code-summary.md`. Cobre a ordem da BR3.2, o desafio consumido na recusa, a corrida pela carteira e a idempotência da emissão. |
| `corepack pnpm@10.18.3 --dir apps/web exec jest src/features/auth src/features/account src/app/convite src/app/conta src/content src/middleware.test.ts` | PASS: 9 suítes, 101 testes; avisos de `act()` em `SessionProvider.tsx` sem falha | Os 101 testes são só do subconjunto pedido. O número de 169 do resumo inclui mais arquivos e não foi reproduzido. |
| `bash scripts/verify.sh` | NÃO EXECUTADO: bloqueado pelo ambiente | Ver o primeiro achado. A cobertura total (95,97% backend, 97,35% frontend) é do resumo e não foi reproduzida aqui. |
| Verificação dos alvos de `traceability.json` | Todos os 100+ IDs de `upstream_ids` têm linha; os alvos de arquivo existem. Só NFR3.1, NFR3.2, NFR3.4 (Deferred) e NFR9.6, NFR10.1 (N/A) trazem justificativa em vez de caminho | Cobertura declarada de forma honesta. |

### Summary

Tentei refutar os pontos pedidos e não achei defeito bloqueante. O código do convite só existe como SHA-256 mais o final de 4 caracteres, e o `toString` de `IssuedInvite`, `VerifyCommand` e `InviteResponse` o omite. A resposta guardada na idempotência não inclui o código, e a repetição nunca o devolve. A rota pública do `AuthenticationFilter` é só `GET /api/v1/invites/{um segmento}`. A ordem de conferências da BR3.2 confere com o código: convite, estado, administrador, carteira, tenant e termos. A violação de `uq_wallet_address` desfaz a transação e uma segunda transação consome o desafio e grava `LOGIN_REJECTED`. O tenant vem do token, com `tenantFromClient` só na emissão de ADMIN. REAL fica sempre `UNAVAILABLE`, e `safeOrigin` decodifica uma vez e só aceita `/scanner` e `/conta`. Os pontos abertos são a evidência de ponta a ponta (smoke da U3 fora do `verify.sh`), a revisão jurídica dos termos provisórios e as medições de p95 e carga pendentes.
