# Regras de negócio — U5 `u5-anonymization`

Conversa: português (Brasil). Fontes:
- `inception/user-stories/stories.md` (US6.1 a US6.3);
- `inception/requirements-analysis/requirements.md` (FR6, NFR1, NFR5, NFR9);
- `inception/contract-design/contract-summary.md` (P2, J1 a J3, J6);
- `entities.md`;
- `functional-design-questions.md` (Q1 a Q5).

## Regras

```yaml
rules:
  - id: BR5.1
    statement: A anonimização só parte de um pedido de Exclusão em andamento, com dupla confirmação ANONIMIZAR
    category: authorization
    applies_to: Account
    trigger: POST administrativo de anonimização
    logic: >
      SE o corpo não traz confirmation igual a ANONIMIZAR ENTÃO 422 UNPROCESSABLE_FIELDS; SE o pedido não é DELETION ou
      não está IN_PROGRESS ENTÃO 409 INVALID_TRANSITION (ou 409 DATA_REQUEST_CLOSED se encerrado, verificado antes); SE a
      origem é EMAIL e a identidade não foi confirmada ENTÃO 409 IDENTITY_NOT_VERIFIED; SE a origem é ACCOUNT ENTÃO a
      verificação não é exigida; o pedido precisa estar ligado a uma conta de papel BETA_USER (uma conta ADMIN, inclusive a
      do próprio administrador, dá 409 INVALID_TRANSITION). Nada muda em nenhum caso de recusa
    violation: 422 ou 409, sem mudança
    source: FR6.1; AC6.1.1; AC6.1.2; AC6.1.3; AC6.1.4
  - id: BR5.2
    statement: A anonimização roda numa só transação, na ordem Bots, Feedback, Identity e Audit, e conclui o pedido
    category: policy
    applies_to: Account
    trigger: anonimização aceita
    logic: >
      Numa transação aberta pelo Privacy: J2 trava os bots da pessoa, leva os não parados a STOPPED e renomeia todos para
      "Bot anonimizado"; J3 apaga o feedback da pessoa; J1 trava a conta, grava ANONYMIZED, apaga endereço e provedor,
      revoga as sessões e apaga os desafios do endereço; J6 publica ACCOUNT_ANONYMIZED sem dado pessoal; o pedido vai para
      DONE com a resposta modelo. Qualquer exceção desfaz tudo
    violation: rollback completo; o pedido continua IN_PROGRESS
    source: FR6.2; FR6.4; AC6.2.1; AC6.2.4; AC6.1.5
  - id: BR5.3
    statement: O ledger, as ordens, os fills, as recusas, os aceites e a trilha não são alterados nem apagados
    category: constraint
    applies_to: Account
    trigger: anonimização
    logic: Nenhum UPDATE ou DELETE nessas tabelas; as posições PAPER abertas ficam sem ordem nova de fechamento; a reconciliação continua em 0 divergência
    violation: os gatilhos de append-only recusam e a transação desfaz
    source: FR6.2; NFR5; AC6.2.3; Q1
  - id: BR5.4
    statement: Depois da anonimização, o endereço completo da carteira não aparece em nenhuma tabela nem log consultável
    category: policy
    applies_to: Wallet
    trigger: teste obrigatório do time
    logic: A carteira fica sem endereço; a trilha e a saída da trilha guardam só máscara e HMAC (U2); os logs nunca têm o endereço completo
    violation: o teste obrigatório falha
    source: FR6.2; NFR9; AC6.2.2; Q4
  - id: BR5.5
    statement: Repetir a anonimização numa conta já ANONYMIZED devolve o mesmo resultado, sem mudança nem evento
    category: constraint
    applies_to: AnonymizationResult
    trigger: anonimização repetida (mesma ou outra chave de idempotência)
    logic: SE a conta já está ANONYMIZED ENTÃO devolve o AnonymizationResult gravado com alreadyAnonymized verdadeiro e o pedido como está, sem nova escrita nem novo ACCOUNT_ANONYMIZED
    violation: não se aplica
    source: AC6.2.5
  - id: BR5.6
    statement: Login, renovação de sessão e ciclo de bot concorrentes nunca deixam sessão ou ordem nova ligada à conta depois da anonimização
    category: constraint
    applies_to: Account
    trigger: concorrência
    logic: >
      Ordem global de travas da anonimização, a mesma dos caminhos concorrentes: primeiro os bots pela LockOrder do
      módulo Bots (sistema, bot_owner e depois as linhas de bot); depois as sessões da pessoa (user_session FOR UPDATE,
      ordenadas por session_id), como a renovação, que trava a sessão antes de ler a conta; por último a conta
      (app_user FOR UPDATE). A renovação lê a conta depois da sessão e recusa ANONYMIZED. O login lê a conta com trava
      compartilhada antes de criar sessão e não acha conta ativa para o endereço apagado. O ciclo do bot relê o estado
      sob a trava do bot e não age num bot STOPPED. Um lock_timeout (SQLSTATE 55P03) em qualquer passo desfaz toda a
      transação e responde 503 SERVICE_UNAVAILABLE, que pode ser repetido com a mesma chave. O token de acesso já emitido
      (até 15 minutos) deixa de valer na próxima requisição: a consulta por requisição da trava da U3 passa a exigir
      app_user.status = ACTIVE e responde 401 SESSION_REVOKED para conta não ativa
    violation: não se aplica
    source: AC6.2.6
  - id: BR5.7
    statement: Uma conta ANONYMIZED não entra mais; a mesma carteira só volta com convite novo, como conta nova
    category: authorization
    applies_to: Account
    trigger: login ou convite com a carteira de uma conta anonimizada
    logic: SE a carteira não tem conta ativa (o endereço foi apagado da conta anonimizada) ENTÃO o login sem convite recusa como conta inexistente; um convite novo cria conta nova, sem histórico da antiga
    violation: login recusado
    source: FR6.3; AC6.3.1; AC6.3.2
  - id: BR5.8
    statement: O feedback só é apagado pela anonimização ou pelo expurgo de retenção
    category: constraint
    applies_to: Account
    trigger: DELETE em feedback_entry
    logic: >
      O gatilho não muda: só um membro do papel feedback_retention apaga. A migração cria a função
      erase_feedback_of_user(tenant uuid, person uuid) com SECURITY DEFINER e search_path fixo. Ela pertence ao dono do
      esquema, que passa a ser membro de feedback_retention, e o EXECUTE fica só para o papel da aplicação (REVOKE de
      PUBLIC). A função apaga só as linhas daquela pessoa e devolve a contagem. A J3 deleteByUser chama a função dentro da
      transação da anonimização; um DELETE direto pela aplicação continua recusado pelo gatilho
    violation: o gatilho recusa
    source: FR6.2; Q2
  - id: BR5.9
    statement: A tela de anonimização só envia com ANONIMIZAR digitado e funciona só pelo teclado
    category: validation
    applies_to: Account
    trigger: diálogo T8
    logic: O botão de envio fica desabilitado até o texto exato; o diálogo prende o foco, fecha com Esc e devolve o foco ao botão que o abriu
    violation: nada é enviado
    source: FR6.1; AC6.1.1; NFR4
```

## Resumo

| ID | Regra | Categoria |
|---|---|---|
| BR5.1 | Só de pedido de Exclusão em andamento, com ANONIMIZAR e identidade quando EMAIL | authorization |
| BR5.2 | Transação única na ordem Bots, Feedback, Identity e Audit; conclui o pedido | policy |
| BR5.3 | Ledger, ordens, aceites e trilha intactos | constraint |
| BR5.4 | Endereço completo em lugar nenhum | policy |
| BR5.5 | Repetição idempotente | constraint |
| BR5.6 | Concorrência com login e bots | constraint |
| BR5.7 | Conta anonimizada não entra; volta só por convite novo | authorization |
| BR5.8 | Feedback apagado só pela anonimização ou pela retenção | constraint |
| BR5.9 | Diálogo com ANONIMIZAR e teclado | validation |
