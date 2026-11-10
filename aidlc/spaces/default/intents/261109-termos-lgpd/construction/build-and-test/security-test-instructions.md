# Instruções de testes de segurança

Conversa: português (Brasil). As metas vêm de `construction/<unit>/nfr-requirements/security-requirements.md` das Units U1 a U5 e das regras `Forbidden`/`Mandated` de `project.md`.

## Varreduras (CI, `.github/workflows/security.yml`)

| Verificação | Ferramenta | Bloqueia |
|---|---|---|
| Segredos | Gitleaks (`.gitleaks.toml`), também no pré-commit | qualquer achado |
| SAST | CodeQL (Java e TypeScript) | Crítico ou Alto |
| Dependências do backend | OWASP Dependency-Check | Crítico ou Alto |
| Dependências do frontend | `pnpm audit --audit-level high` em `apps/web` e `apps/admin` | Alto ou acima |

A execução dessas varreduras antes do merge fica com o estágio CI Pipeline. Localmente:

```bash
gitleaks detect --no-banner --redact
corepack pnpm@10.18.3 --dir apps/web audit --audit-level high
corepack pnpm@10.18.3 --dir apps/admin audit --audit-level high
```

## Testes automatizados de segurança (na suíte)

| Área | Testes |
|---|---|
| Dado pessoal fora de logs e eventos | `LogScanIT` (agulhas da U2, U4 e U5), `WalletAuditIT`, `AnonymizationObservabilityIT` |
| A anonimização não deixa o endereço da carteira em nenhuma tabela nem log (teste obrigatório do time) | `AnonymizationNoWalletLeftIT` |
| A versão nova dos termos exige novo aceite antes de qualquer outra rota (teste obrigatório do time) | `TermsReacceptanceRoutesIT` |
| Isolamento entre tenants e pessoas | `DataRequestIT`, `TermsAcceptanceIT`, testes negativos do `route-registry.yml` |
| Papel ADMIN nas rotas administrativas | `AdminGuestRoutesIT`, `AdminDataRequestIT`, `AdminAnonymizationIT` |
| Assinatura de verificação ≠ assinatura de login | `IdentityVerificationIT` |
| Trava de novo aceite falha fechada | `TermsReacceptanceFilterTest` (banco parado → `503`) |
| Limitador das rotas legais | `LegalDocumentsRateLimiterIT`, `e2e/legal-documents` (porta publicada → `429`) |
| Feedback só apagado pela função | `FeedbackEraseFunctionIT`, `U8PrivilegesIT`, `RolesBootstrapIT` |
| Texto sempre como texto (sem injeção de HTML) | testes de componente com `<script>` nos dois apps |
| Chave HMAC: falha rápida e nunca exposta | `WalletHasherTest` e a varredura de `actuator/env` |

## Ponta a ponta

O `verify.sh` varre os logs da API, do worker e do admin atrás de endereço completo de carteira e do texto do feedback.

## O que fica para estágios seguintes

- Varreduras da CI antes do merge: CI Pipeline.
- DAST, varredura de imagens e SBOM: candidatos antes do G1 (Deployment, `team.md`).
