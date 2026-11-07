# Instruções de testes de segurança

Conversa: português (Brasil). As metas vêm de `construction/<unit>/nfr-requirements/security-requirements.md` e das regras `Forbidden`/`Mandated` de `project.md`.

## Varreduras (CI, `.github/workflows/security.yml`)

| Verificação | Ferramenta | Bloqueia |
|---|---|---|
| Segredos | Gitleaks (`.gitleaks.toml`), também no pré-commit | qualquer achado |
| SAST | CodeQL (Java e TypeScript) | Crítico ou Alto |
| Dependências do backend | OWASP Dependency-Check, com exceções em `SecurityExceptionsTest` (validade de no máximo 90 dias) | Crítico ou Alto |
| Dependências do frontend | `pnpm audit --audit-level high` em `apps/web` e `apps/admin` | Alto ou acima |

As ações de terceiros são fixadas por SHA, e cada workflow declara `permissions` mínimas. A execução dessas varreduras antes do merge fica com o estágio CI Pipeline.

## Testes automatizados de segurança (na suíte)

- **Autenticação:** nonce reutilizado, assinatura expirada e assinatura de login usada para operar são rejeitados (`AuthVerifyIT`, `Ed25519SignatureVerifierTest`). O token fica só em memória e só no cabeçalho `Authorization` (testes do cliente web), e um `401` gera uma única renovação (`renewal.test.ts`).
- **Autorização e tenant:** testes negativos entre tenants e entre pessoas do mesmo tenant em todas as rotas de dados de cliente. O tenant vem só do token. O papel ADMIN fica restrito à entrada administrativa.
- **Entradas:** `ProblemDetail` sem stack trace nem SQL, CORS do convidado (`GuestCorsPolicyTest`), cabeçalhos de segurança e CSP (`SecurityHeadersFilterTest`), leitor SSE sem execução de conteúdo.
- **Logs:** `LogScanIT` procura token, `Authorization`, nonce e assinatura nos logs.
- **Código entregue ao navegador:** sem `EventSource`, sem segredo em `NEXT_PUBLIC_*`; regras ESLint e testes estáticos.
- **REAL desligado:** nenhum caminho assina ou envia transação com a configuração padrão. O SDK Solana fica só no código de teste (ArchUnit).

## Como rodar

```bash
apps/api/mvnw -f apps/api/pom.xml verify            # inclui LogScanIT, ArchUnit e os testes negativos
apps/api/mvnw -f apps/api/pom.xml -Dtest=SecurityExceptionsTest test
corepack pnpm@10.18.3 --dir apps/web audit --audit-level high
corepack pnpm@10.18.3 --dir apps/admin audit --audit-level high
```

## Fora deste estágio

DAST, varredura de imagens e SBOM por release são candidatos antes do G1 (`team.md`, Deployment). Eles ainda não têm ferramenta definida.

## Resultado desta execução

Todos os testes de segurança da suíte passaram; as 2 falhas do Failsafe não são de segurança. As varreduras de CI ficam com CI Pipeline (`N/A` aqui).

O achado R-11 da U1 (filtros `ModeGuardFilter` e `IdempotencyKeyFilter` usam o `getRequestURI()` bruto) continua em aberto. Ele foi aceito na aprovação da U1.
