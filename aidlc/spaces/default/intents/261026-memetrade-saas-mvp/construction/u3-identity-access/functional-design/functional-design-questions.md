# Perguntas do Functional Design — U3 Convites, contas e sessões

Unit `u3-identity-access`. Histórias: US7.1 (regras e rotas de convite), US1.1, US1.3 e US1.5. Contratos: C1 (verificação com convite), C3 (convite, termos e conta), C8 (rotas administrativas de convite, telas na U8) e C17 (`IdentityAdministration`).

Já decidido nas fases anteriores e na U1 (não vira pergunta):
- O convite vale até o instante `expiresAt`, inclusive: em T é aceito, em T + 1 s é negado como expirado (AC1.1.2).
- A conta nasce em `POST /api/v1/auth/verify` com `inviteCode` e `acceptedTermsVersion`, numa única transação: consumir o convite, criar a pessoa, vincular a carteira, registrar o aceite e auditar (AC1.1.3). Dois usos simultâneos geram uma conta só (AC1.1.4).
- A revogação vale até o último instante: a verificação confere o estado do convite dentro da mesma transação, então assinar depois de uma revogação não cria conta (AC7.1.3).
- Carteira já vinculada a outra conta é recusada com `WALLET_ALREADY_LINKED` (AC1.1.5).
- Sessão, renovação e saída seguem a U1 (BR1.9, BR1.12, BR1.14, BR1.15). Não existe "desconectar carteira" no beta (Q13 das histórias).
- Rotas de convite são só para administrador, com 403 auditado (AC7.1.4). As telas administrativas ficam na U8.
- Nenhum campo aceita frase secreta ou chave privada (AC1.2.5).

## Q1 — De onde vem o tenant do convite

O administrador escolhe o tenant ao emitir o convite (ADR-002), mas nenhuma Unit cria tenants.

A. Um tenant `Beta` é semeado pela migração, e o administrador escolhe entre os tenants ativos existentes. Não há cadastro de tenant no beta.
B. O administrador pode criar um tenant novo (só o nome) ao emitir o convite.
C. Cada convite cria o seu próprio tenant.
X. Other (please specify)

[Answer]: A

## Q2 — Código do convite

A. O código é aleatório, com pelo menos 128 bits, seguro para URL. O banco guarda só o resumo (hash) do código. O administrador vê o link completo uma única vez, na emissão; depois a lista mostra só o identificador e os 4 últimos caracteres.
B. O código fica guardado em claro, e o administrador pode ver o link de novo na lista.
X. Other (please specify)

[Answer]: A

## Q3 — Validade do convite

A. O administrador escolhe `expiresAt` entre 1 hora e 30 dias a partir da emissão; o formulário sugere 7 dias.
B. A validade é fixa em 7 dias.
C. O administrador escolhe livremente, sem limite.
X. Other (please specify)

[Answer]: A

## Q4 — Código de status do convite já usado

O contrato C1 diz `409` para "convite já usado", e a regra de C3 e a tabela de erros dizem `422 INVITE_INVALID` com `reason: USED`.

A. Usar `422 INVITE_INVALID` com `reason` (`EXPIRED`, `REVOKED`, `USED`) para todos os casos do convite, inclusive o perdedor da concorrência. `409` fica só para `WALLET_ALREADY_LINKED`. Corrijo a descrição do C1.
B. Usar `409` para o convite já usado e `422` para expirado e revogado.
X. Other (please specify)

[Answer]: A

## Q5 — Código inexistente e tentativas de adivinhar

`GET /api/v1/invites/{code}` responde sem sessão.

A. Código inexistente responde `404 INVITE_NOT_FOUND` com a mesma mensagem genérica de "convite não encontrado". A rota tem limite por IP de 10 consultas por minuto, como a entrada (429 `RATE_LIMITED`). O código nunca aparece em log nem na trilha; só o identificador do convite.
B. Código inexistente responde como `422 INVITE_INVALID`, sem distinguir de expirado, para não revelar se existe.
X. Other (please specify)

[Answer]: A

## Q6 — Versões dos termos e do aviso de risco

A. Termos e aviso de risco ficam versionados juntos num arquivo do repositório (por exemplo `terms/v1`), com uma única versão vigente. O aceite registra a versão e o horário. Se uma versão nova for publicada, quem já tem conta continua entrando sem novo aceite no beta.
B. Como A, mas uma versão nova exige novo aceite no próximo login, antes de entrar.
X. Other (please specify)

[Answer]: A

## Q7 — Revogar convites que não estão pendentes

A. Só um convite pendente e ainda válido pode ser revogado. Revogar de novo um convite já revogado devolve o mesmo convite, sem novo evento. Convite usado ou expirado responde `409 INVITE_NOT_REVOCABLE`.
B. Qualquer convite pode ser revogado; revogar um usado não afeta a conta já criada.
X. Other (please specify)

[Answer]: A

## Q8 — Anotação do convite para o administrador

A. Sem anotação. O convite tem só tenant, validade, estado e datas, para não guardar dado pessoal.
B. Uma anotação opcional de até 80 caracteres, só visível ao administrador, com aviso para não escrever dado pessoal.
X. Other (please specify)

[Answer]: A

## Q9 — Voltar à tela de origem depois da sessão expirada

AC1.3.1 pede voltar à tela onde a pessoa estava. FR1.6 também cita preservar o rascunho de bot.

A. A U3 entrega o retorno genérico: o 401 leva à entrada com `origem` (só caminhos internos do app, nunca URL externa), e depois de entrar a pessoa volta ao caminho. A preservação do rascunho de bot fica para a U5, que usa esse mesmo retorno.
B. A U3 também entrega um mecanismo genérico de rascunho no navegador, que a U5 só usa.
X. Other (please specify)

[Answer]: A

## Q10 — Validade exibida na tela Conta

A sessão termina no que vier primeiro: 8 horas sem uso da renovação ou 12 horas desde a criação (BR1.9).

A. A tela Conta mostra o fim absoluto (12 horas desde a entrada) e a frase "ou antes, depois de 8 horas sem uso".
B. A tela mostra o menor dos dois instantes, recalculado a cada renovação.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
