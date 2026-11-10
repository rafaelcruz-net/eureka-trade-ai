# Perguntas de NFR Design — U3 `u3-reacceptance`

Conversa: português (Brasil). Todas as respostas são as recomendadas (A), pelo pedido do responsável do projeto em 2026-11-10: "Siga sempre as recomendações sem me pergunta".

## Q1 — Consulta da trava

A. A trava faz uma única consulta `EXISTS` em `terms_acceptance` por `(user_id, terms_version)`, pela restrição única nova. Ela vai por uma porta do domínio (`TermsAcceptanceLookup`), com o tenant e a pessoa do contexto de autenticação. A vigente efetiva vem do `CurrentTerms` da U1, em memória. (recomendado)
B. Um cache por pessoa.
X. Other (please specify)

[Answer]: A

## Q2 — Tratamento do 403 no app

A. O cliente `fetch` do app (o utilitário que já trata `401` e renovação) reconhece o `ProblemDetail` com `code=TERMS_REACCEPTANCE_REQUIRED` e chama `router.replace('/novo-aceite?voltar=...')`. Assim, toda tela usa o mesmo caminho. (recomendado)
B. Cada tela trata o 403.
X. Other (please specify)

[Answer]: A
