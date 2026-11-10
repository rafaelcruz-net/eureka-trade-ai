## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-10T07:06:49Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/nfr-design/security-design.md > NFR2.5 | O desenho diz que a consulta da trava "não captura exceção" e que um `DataAccessException` vira o `503` do `ProblemDetailsHandler`. Isso não acontece com um filtro de servlet. O `ProblemDetailsHandler` é um `@RestControllerAdvice` e só vê exceções lançadas dentro do controlador. Os outros filtros do projeto, como `ModeGuardFilter` e `AuthenticationFilter`, escrevem a resposta com o `ProblemWriter`, que tem o comentário "filters run before the controller advice". Com o banco parado, a exceção sai do filtro e o contêiner devolve um erro genérico, que não é `ProblemDetail` nem `503`. O NFR2.5 (falha fechada com `503`) e a regra de RFC 7807 ficam sem desenho válido. | Trocar o desenho: o `TermsReacceptanceFilter` captura `DataAccessException` (`CannotGetJdbcConnectionException`, `QueryTimeoutException`, `TransientDataAccessException`), escreve `ErrorCode.SERVICE_UNAVAILABLE` pelo `ProblemWriter`, registra só o tipo da exceção e não chama a cadeia. O teste do NFR2.5 deve conferir o corpo `ProblemDetail` e o status `503`. | New |
| R-02 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/nfr-design/security-design.md > NFR1.1 (`currentVersion` como extensão) | O desenho exige `currentVersion` como extensão do `ProblemDetail` nos dois erros novos, mas não diz como. `ApiException` tem só `code`, `fields`, `reason` e `headers`, e `ProblemResponse` não tem campo de extensão. O `ApiError` do cliente web também não tem campo para esse valor. Sem isso, o filtro e o serviço não conseguem devolver a versão, e a tela `/novo-aceite` não consegue lê-la. O contrato OpenAPI (e portanto o tipo gerado) também precisa descrevê-la. | Registrar no desenho como a versão sai: um campo opcional novo em `ApiException` e em `ProblemResponse`, a declaração no OpenAPI dos dois erros e o campo correspondente em `ApiError` do cliente, com um teste de API que confere `currentVersion` no `403` e no `409`. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/nfr-design/nfr-design-questions.md > Q2 e logical-components.md > "Cliente de API do app (ampliado)" | O desenho manda o "cliente fetch" chamar `router.replace`. O cliente real (`apps/web/src/lib/api/guest-api.ts`, `withRenewal`) é um módulo sem roteador e só trata `401`. O canal ao vivo do painel e a renovação única também passam por outros caminhos. O desenho não diz onde o `403` é interceptado nem como o roteador chega ao cliente. | Dizer que o `ApiProvider` injeta um tratador do `403 TERMS_REACCEPTANCE_REQUIRED` no `guest-api` (ou em `withRenewal`), que o tratador cobre também o canal ao vivo e que a rota de volta passa por `safeReturnPath`. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/nfr-design/performance-design.md > NFR3.2 | O cenário de carga reprova com p95 do timer `memetrade.terms.reacceptance.check` acima de 5 ms, mas o desenho não configura percentis nem histograma para esse timer. Sem isso, o `/actuator/prometheus` só traz soma e contagem, e o p95 não existe. | Configurar `publishPercentileHistogram` (ou `publishPercentiles(0.95)`) no timer e dizer como o cenário lê o valor. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/nfr-design/security-design.md > NFR2.2 e NFR2.6 | O worker não registra filtros web, mas o desenho não diz que o filtro novo leva a anotação `@ProcessRole(API)`, como o `AuthenticationFilter`. Sem ela, o filtro pode subir no processo do worker. | Registrar a anotação `@ProcessRole(API)` no desenho do filtro e cobrir no teste do ciclo do worker. | New |
| R-06 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/nfr-design/reliability-design.md > NFR5.1 (rollback) | O rollback de `DROP NOT NULL` em `invite_id` falha depois que existir um aceite `REACCEPTANCE` com `invite_id` nulo. O desenho pede rollback em cada changeset, mas não diz a condição. | Registrar que o rollback só vale antes do primeiro novo aceite (ou que a coluna não volta a `NOT NULL` com dados). | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Rastreabilidade (`traceability.json` contra os 21 IDs de NFR) | Os 21 IDs aparecem, todos com `OK` | Cobertura formal completa; os dois achados Major são de viabilidade contra o código, não de cobertura. |
| Conferência contra o código (`FilterOrder`, `AuthenticationFilter`, `ProblemWriter`, `ProblemDetailsHandler`, `ApiException`, `0007-identity-access.sql`, `guest-api.ts`) | A ordem +16 entre +15 e +17 está livre. `uq_terms_acceptance_invite` e o `REVOKE` existem como descritos. Os pontos de falha estão nos dois Major. | A ordem do filtro e a migração são implementáveis. |

### Summary

A ordem do filtro, a migração e a cobertura dos NFR são sólidas. Há dois Major que um desenvolvedor teria de adivinhar: o `503` da trava não chega ao `ProblemDetailsHandler` num filtro de servlet, e `currentVersion` não tem caminho em `ApiException`, `ProblemResponse`, OpenAPI e `ApiError`. Com no máximo dois Major e nenhum Critical, o veredito é READY, e os dois devem ser corrigidos antes da geração de código.
