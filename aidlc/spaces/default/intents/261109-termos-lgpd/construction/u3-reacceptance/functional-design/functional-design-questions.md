# Perguntas do Functional Design — U3 `u3-reacceptance`

Conversa: português (Brasil). Todas as respostas são as recomendadas (A), pelo pedido do responsável do projeto em 2026-11-10: "Siga sempre as recomendações sem me pergunta".

A U3 cobre US2.3, US4.1 e US4.2 (FR2.3, FR4.1 a FR4.4) e os contratos L2 e L3. O que o código tem hoje:
- **Tabela `terms_acceptance`:** só aceita inserção. Tem `invite_id NOT NULL` e é única por convite, então hoje só existe o aceite feito no convite.
- **Convite:** já tem as duas caixas e recusa com `TERMS_NOT_ACCEPTED`.
- **Conta:** mostra a versão aceita.

## Q1 — Onde fica o novo aceite

A. Numa migração só aditiva (expand):
- `terms_acceptance.invite_id` passa a aceitar nulo (só relaxa a regra);
- entra a coluna `source` (`INVITE` ou `REACCEPTANCE`, padrão `INVITE` para as linhas existentes);
- entra uma restrição única em `(user_id, terms_version)`.

A tabela continua só de inserção. (recomendado)
B. Uma tabela nova só para os novos aceites.
X. Other (please specify)

[Answer]: A

## Q2 — Trava de novo aceite (FR4.1, L3)

A. Um filtro `TermsReacceptanceFilter` no Identity, depois do `AuthenticationFilter`, só para tokens de convidado (`BETA_USER`). Ele não se aplica ao administrador nem ao worker. A cada requisição, o filtro lê do banco a última versão aceita pela pessoa, por uma consulta indexada sem cache. Se ela difere da vigente efetiva (BR1.16), a resposta é `403 TERMS_REACCEPTANCE_REQUIRED` com `currentVersion`. Os prefixos liberados são configuráveis em `memetrade.terms.reacceptance.allowed-prefixes`: `/api/v1/legal/`, `/api/v1/terms/acceptances`, `/api/v1/account`, `/api/v1/auth/logout` e `/api/v1/auth/refresh`. A U4 acrescenta `/api/v1/privacy/`. (recomendado)
B. Ler a versão aceita do token e exigir renovação depois do aceite.
X. Other (please specify)

[Answer]: A

## Q3 — Bots durante a trava (FR4.4)

A. O worker não passa pelo filtro, então os bots PAPER ativos continuam rodando. Toda rota de bot do convidado, de leitura ou de escrita, recebe o `403` até o aceite, como as demais rotas bloqueadas. (recomendado)
B. Liberar a leitura dos bots.
X. Other (please specify)

[Answer]: A

## Q4 — Rota de aceite (FR4.2, L2)

A. `POST /api/v1/terms/acceptances` com `Idempotency-Key`, pelo mecanismo comum de idempotência do Identity (correção registrada no projeto):
- **versão vigente:** grava um aceite `REACCEPTANCE`, publica `TERMS_ACCEPTED` e responde `200 TermsStatus`;
- **versão já aceita:** devolve o mesmo resultado sem nova gravação;
- **versão que não é a vigente:** `409 TERMS_VERSION_OUTDATED`, com `currentVersion` no `ProblemDetail`.

O tenant e a pessoa vêm da autenticação. (recomendado)
B. Outra forma.
X. Other (please specify)

[Answer]: A

## Q5 — Tela de novo aceite no app (FR4.3, T7)

A. O cliente da API do app trata o `403 TERMS_REACCEPTANCE_REQUIRED` em um lugar só e leva à rota `/novo-aceite?voltar=<rota>`. A tela mostra:
- o resumo da versão vigente (termos e política) e os links para `/termos` e `/privacidade`;
- as mesmas duas caixas do convite, com o botão "Aceitar e continuar" desabilitado até as duas estarem marcadas e o motivo em texto;
- estados de carregando e de erro.

Um `409` recarrega os documentos e pede o aceite de novo. Depois do aceite, a tela volta à rota anterior. A navegação funciona só pelo teclado, com foco visível. (recomendado)
B. Um modal sobre a tela atual.
X. Other (please specify)

[Answer]: A

## Q6 — Aceite no convite (US2.3)

A. O convite mantém as duas caixas. Mudanças:
- mostra o resumo da versão vigente, lido de L1;
- as caixas ficam desabilitadas, com texto, enquanto os documentos carregam ou quando falham;
- os links abrem em outra aba (`target="_blank"`, `rel="noopener"`), para a pessoa voltar sem perder as marcações;
- a conta nasce com o aceite `INVITE` da versão vigente efetiva, como hoje.

(recomendado)
B. Os links na mesma aba, guardando as marcações no navegador.
X. Other (please specify)

[Answer]: A

## Q7 — Conta

A. A resposta de `GET /api/v1/account` ganha o campo aditivo `terms: TermsStatus`. A tela Conta mostra a versão aceita, a data e, quando a pessoa não está em dia, o link "Aceitar a nova versão". (recomendado)
B. Sem mudança na Conta.
X. Other (please specify)

[Answer]: A
