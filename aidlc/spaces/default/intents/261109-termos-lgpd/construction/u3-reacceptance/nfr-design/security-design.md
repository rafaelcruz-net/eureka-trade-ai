# Desenho de segurança — U3 `u3-reacceptance`

Conversa: português (Brasil). Implementa `construction/u3-reacceptance/nfr-requirements/security-requirements.md` e `reliability-requirements.md` (NFR1.1, NFR2.1 a NFR2.6, NFR9.1, NFR9.2). Respostas em `nfr-design-questions.md`.

| Requisito | Desenho |
|---|---|
| NFR1.1 | Dois códigos novos, `ErrorCode.TERMS_REACCEPTANCE_REQUIRED` (403, tipo `terms`) e `ErrorCode.TERMS_VERSION_OUTDATED` (409, tipo `terms`). A `currentVersion` sai no corpo por um campo opcional `currentVersion` novo:<ul><li>`ProblemResponse` ganha o campo, serializado só quando presente;</li><li>`ApiException` ganha um mapa opcional de extensões, que o `ProblemDetailsHandler` copia;</li><li>o `ProblemWriter` dos filtros recebe o mesmo valor;</li><li>o OpenAPI descreve o campo no esquema do problema, e o `ApiError` do cliente web, gerado do contrato, passa a tê-lo.</li></ul>O evento `TERMS_ACCEPTED` leva só `{version, source}` em `details`, pela pessoa (sem campo `wallet`) |
| NFR2.1 | O `TermsAcceptanceController` lê a pessoa e o tenant do `AuthenticatedPrincipal`; o corpo é só `{version}` (Bean Validation, 1 a 32 caracteres). O `TermsAcceptanceService` grava com o tenant do principal |
| NFR2.2 | O `TermsReacceptanceFilter` leva `@ProcessRole(API)`, como o `AuthenticationFilter`, então não sobe no worker. Ele só age quando o principal tem papel `BETA_USER`; o papel `ADMIN` e as requisições sem principal passam direto. O `ProcessRoleContextIT` confere que o filtro não existe no contexto do worker |
| NFR2.3 | A migração `0013` mantém o `REVOKE UPDATE, DELETE, TRUNCATE` de `terms_acceptance`. O teste existente que tenta `UPDATE` e `DELETE` continua |
| NFR2.4 | `safeReturnPath(value)` no app aceita só `^/(?!/)[^\s]*$` e cai no painel `/` em qualquer outro caso. O ponto de interceptação do `403` é o `guest-api` (`apps/web/src/lib/api/guest-api.ts`). O `ApiProvider` registra nele um tratador `onTermsReacceptance(currentPath)`, que usa o `router.replace` do Next. O `withRenewal` e o canal ao vivo do painel chamam o mesmo tratador quando a resposta traz `code=TERMS_REACCEPTANCE_REQUIRED` |
| NFR2.5 | Um filtro roda antes do `@RestControllerAdvice`, então a trava captura `DataAccessException` e escreve ela mesma o `503 SERVICE_UNAVAILABLE` pelo `ProblemWriter`, como o `ModeGuardFilter`, sem chamar o resto da cadeia. A requisição nunca segue sem a conferência. O log registra só a classe da exceção |
| NFR2.6 | As rotas de bot ficam fora da lista liberada, e o worker roda sem a camada web |
| NFR9.1 | `TermsReacceptanceRoutesIT` carrega o `RouteRegistry` e chama cada rota do convidado com uma pessoa desatualizada. As rotas fora da lista recebem `403` com o código, e as liberadas não recebem |
| NFR9.2 | O plano de código põe primeiro os testes de BR3.1, BR3.4, BR3.5 e BR3.6 |
