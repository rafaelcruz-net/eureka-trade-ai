# Perguntas do desenho funcional — U8 Administração e feedback

Unit `u8-admin-feedback`. Histórias: US7.1 (telas de convite), US7.2 (kill switch), US7.4 (estado do sistema), US6.1 (feedback) e o AC1.2.6 (entrada do administrador), que a U1 passou para esta Unit. Contratos: C6 (feedback), C8 (API administrativa), C9 (trilha), C11 (fontes), C16 (`BotAdministration`, U5) e C17 (`IdentityAdministration`, U3).

Já decidido nas etapas anteriores, sem pergunta:
- As rotas de convite já existem (U3). A U8 cria as telas, a rota dos tenants ativos e o resto da API administrativa.
- O kill switch aplica a pausa de sistema pela `BotAdministration` da U5, que é repetível; desligar não retoma bots (ADR-007).
- Nenhuma rota administrativa age sobre o bot de uma pessoa nem lê ledger, operações ou carteiras (AC7.4.2).
- O código do convidado não contém operações administrativas (ADR-009). O app do administrador é um segundo app Next.js.
- O feedback tem de 1 a 2.000 caracteres, tela de origem S1 a S9 e horário UTC, e o administrador o lê como texto puro.

## Q1. [Segurança] Como o administrador entra no app administrativo?

O AC1.2.6 diz que a carteira de administrador da configuração entra sem convite. O C8 prevê as rotas `/api/v1/admin/auth/challenge`, `verify`, `refresh` e `logout`, com a mesma forma da entrada do convidado.

A. Reutilizar o mecanismo da entrada do convidado (desafio com nonce de uso único, assinatura Ed25519, sessão no servidor), com rotas e cookie de renovação próprios em `/api/v1/admin/auth/...`. Só os endereços de `ADMIN_WALLET_ADDRESSES` recebem desafio válido, e qualquer outro recebe `403 FORBIDDEN_ROLE` auditado. A sessão do administrador dura menos que a do convidado: 15 minutos de token de acesso e 8 horas de sessão.
B. Igual à A, com os mesmos tempos da sessão do convidado.
X. Other (please specify)

[Answer]: A

## Q2. [Domínio] Como o estado do kill switch é guardado?

A U5 já tem a própria cópia (`SystemPauseState`), usada pelo ciclo. A Administration é dona do registro (estado, quem, quando, motivo).

A. A U8 guarda uma linha única do estado atual (estado, administrador, horário, motivo) e um histórico só de acréscimo com cada acionamento e desligamento. A tela mostra o estado atual, e a trilha de auditoria tem o evento de cada mudança.
B. Só a linha do estado atual; o histórico fica só na trilha de auditoria.
X. Other (please specify)

[Answer]: A

## Q3. [Processo] O que acontece quando a pausa de sistema falha em alguns bots?

`applySystemPause` devolve `failed` com os bots cuja transação falhou, e a U8 repete até a lista ficar vazia.

A. A rota repete até 3 vezes na mesma requisição. Se ainda sobrar falha, ela responde 200 com o estado ON e a quantidade pendente, e um trabalho da U8 repete a cada 30 s até zerar. A tela mostra "Pausa pendente em N bots". O estado ON vale desde a primeira gravação, então o ciclo já não opera nenhum bot.
B. A rota repete até zerar, sem limite, e só então responde.
X. Other (please specify)

[Answer]: A

## Q4. [Processo] Pedir ON com o kill switch já ligado (ou OFF já desligado)

A. É idempotente: devolve o estado atual sem nova linha de histórico nem novo evento do administrador. Um ON repetido ainda roda `applySystemPause`, para corrigir uma falha pendente.
B. Recusa com `409` e um código próprio.
X. Other (please specify)

[Answer]: A

## Q5. [Escopo] Contagens do estado do sistema que o contrato não tem

O mockup A1 mostra "Encerramento pendente" e "Ordens recusadas hoje". O C8 tem só convidados, convites por estado, bots por estado, kill switch, fontes e atraso da trilha. "Encerramento pendente" já é um estado de bot (`CLOSING_PENDING`).

A. "Encerramento pendente" sai de `botCountsByState`. "Ordens recusadas hoje" entra como `rejectedOrdersToday` no C8, lida por uma contagem nova da U6 (total de todos os tenants desde 00:00 UTC, sem identificador). O C8 e a interface da U6 ganham esse acréscimo.
B. Tirar "Ordens recusadas hoje" da tela, sem mexer em outra Unit.
X. Other (please specify)

[Answer]: A

## Q6. [Segurança] Limite de envio de feedback

A. Até 10 envios por pessoa por hora. Acima disso a resposta é `429 RATE_LIMITED`, a tela mostra o motivo e preserva o texto. A chave de idempotência evita duplicar um reenvio.
B. Sem limite além da chave de idempotência.
X. Other (please specify)

[Answer]: A

## Q7. [Frontend] Onde o convidado abre o feedback antes da U9?

A fronteira diz que o item de menu entra na navegação da U9, que vem depois.

A. A U8 entrega o diálogo de feedback como componente e o coloca já no cabeçalho atual do app do convidado ("Enviar feedback"). A tela de origem vem da rota aberta. A U9 só move o item para o menu novo.
B. A U8 entrega só o componente; o item aparece na U9.
X. Other (please specify)

[Answer]: A

## Q8. [Domínio] Retenção e exibição do feedback para o administrador

A. O feedback fica guardado por 180 dias e depois é apagado por um trabalho diário (com evento de auditoria agregado). A lista do administrador mostra o nome do tenant, a tela e o horário, nunca a carteira nem o identificador da pessoa, e vem do mais novo para o mais antigo.
B. Guardar sem prazo; a lista mostra só o `tenantId`.
X. Other (please specify)

[Answer]: A

## Q9. [Teste] Smoke do app do administrador

O `verify.sh` sobe a pilha e roda o Playwright.

A. Um smoke novo `e2e/u8-admin-feedback` com a carteira de administrador de teste (gerada só no código de teste), passando por: entrar, ver o estado do sistema, emitir e revogar um convite, acionar e desligar o kill switch, ler um feedback enviado por um convidado e confirmar que um token de convidado recebe 403. O app do administrador entra no Docker Compose. A inclusão do smoke no `verify.sh` fica com o responsável do projeto.
B. Só testes de componente e de integração, sem smoke.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

[Answer]: Looks correct
