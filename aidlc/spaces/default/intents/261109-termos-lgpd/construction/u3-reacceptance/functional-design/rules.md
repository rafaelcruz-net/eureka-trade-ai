# Regras de negócio — U3 `u3-reacceptance`

Conversa: português (Brasil). Fontes:
- `inception/user-stories/stories.md` (US2.3, US4.1, US4.2);
- `inception/requirements-analysis/requirements.md` (FR2.3, FR4.1 a FR4.4, NFR3, NFR4, NFR9);
- `inception/contract-design/contract-summary.md` (L2, L3);
- `entities.md`;
- `functional-design-questions.md` (Q1 a Q7).

## Regras

```yaml
rules:
  - id: BR3.1
    statement: Um convidado com versão aceita diferente da vigente recebe 403 TERMS_REACCEPTANCE_REQUIRED em toda rota do convidado fora da lista liberada
    category: authorization
    applies_to: ReacceptanceGate
    trigger: cada requisição autenticada com token de convidado
    logic: >
      SE o token é de BETA_USER e o caminho não começa por um prefixo liberado ENTÃO confere no banco se existe um aceite
      da pessoa para a versão vigente efetiva (não só o mais recente: se a vigente voltar a uma versão já aceita, a pessoa
      segue em dia); SE não existe, inclusive numa conta sem nenhum aceite, ENTÃO responde 403 com o código
      TERMS_REACCEPTANCE_REQUIRED e currentVersion no ProblemDetail; SENÃO segue. A consulta usa a restrição única
      (user_id, terms_version) como índice e acrescenta no máximo 5 ms (p95) à requisição. Tokens de administrador e o
      worker não passam por esta regra.
    violation: 403 TERMS_REACCEPTANCE_REQUIRED
    source: FR4.1; AC4.1.1; Q2
  - id: BR3.2
    statement: Documentos legais, aceite, Conta, sair, renovar sessão e (a partir da U4) pedidos de titular continuam liberados
    category: authorization
    applies_to: ReacceptanceGate
    trigger: requisição numa rota liberada
    logic: SE o caminho começa por um prefixo liberado configurado ENTÃO a trava não se aplica
    violation: não se aplica
    source: FR4.1; AC4.1.2; L3; Q2
  - id: BR3.3
    statement: Bots PAPER ativos continuam rodando durante a trava; toda rota de bot do convidado fica bloqueada até o aceite
    category: policy
    applies_to: ReacceptanceGate
    trigger: trava ativa
    logic: O worker não passa pela trava, então o ciclo dos bots segue; criar, alterar, iniciar, retomar e ler bots pela API recebem 403
    violation: 403 TERMS_REACCEPTANCE_REQUIRED nas rotas de bot
    source: FR4.4; AC4.1.4; Q3
  - id: BR3.4
    statement: Aceitar a versão vigente grava um único aceite novo e não altera os anteriores
    category: policy
    applies_to: TermsAcceptance
    trigger: POST de aceite com a versão vigente
    logic: >
      SE a versão enviada é a vigente efetiva e a pessoa ainda não a aceitou ENTÃO grava um aceite REACCEPTANCE (pessoa,
      tenant da autenticação, versão, instante do relógio) e publica TERMS_ACCEPTED; a resposta é 200 com TermsStatus
      em dia
    violation: não se aplica
    source: FR4.2; AC4.2.1; Q4
  - id: BR3.5
    statement: A mesma chave de idempotência ou a mesma versão já aceita devolvem o mesmo resultado, sem nova gravação
    category: constraint
    applies_to: TermsAcceptance
    trigger: aceite repetido
    logic: SE a chave já foi usada ENTÃO devolve a resposta guardada; SE a versão já foi aceita pela pessoa ENTÃO devolve o TermsStatus existente sem gravar
    violation: não se aplica
    source: FR4.2; AC4.2.1; AC4.2.3; Q4
  - id: BR3.6
    statement: Aceitar uma versão que não é a vigente devolve 409 TERMS_VERSION_OUTDATED com a versão vigente
    category: validation
    applies_to: AcceptTermsCommand
    trigger: POST de aceite com versão diferente da vigente efetiva
    logic: SE a versão enviada não é a vigente efetiva ENTÃO responde 409 com TERMS_VERSION_OUTDATED e currentVersion, sem gravar
    violation: 409 TERMS_VERSION_OUTDATED
    source: FR4.2; AC4.2.2; Q4
  - id: BR3.7
    statement: O app leva à tela de novo aceite ao receber TERMS_REACCEPTANCE_REQUIRED e volta à rota anterior depois do aceite
    category: policy
    applies_to: ReacceptanceGate
    trigger: resposta 403 com TERMS_REACCEPTANCE_REQUIRED no app
    logic: >
      O cliente da API leva a /novo-aceite com a rota de volta. A tela mostra o resumo da versão vigente, os links e as duas
      caixas; o botão fica desabilitado até as duas estarem marcadas, com o motivo em texto; um 409 recarrega os
      documentos; depois do aceite, volta à rota anterior
    violation: não se aplica
    source: FR4.3; AC4.1.3; AC4.2.2; Q5
  - id: BR3.8
    statement: O convite só deixa conectar com as duas caixas marcadas e os documentos carregados
    category: validation
    applies_to: TermsAcceptance
    trigger: tela de convite
    logic: >
      SE alguma caixa está desmarcada ENTÃO o botão de conectar fica desabilitado com o motivo em texto; SE os documentos
      estão carregando ou falharam ENTÃO as caixas ficam desabilitadas e aparece "carregando" ou o erro em texto; os
      links abrem em outra aba para manter as marcações; a conta nasce com o aceite INVITE da versão vigente efetiva
    violation: o botão fica desabilitado; a API recusa com TERMS_NOT_ACCEPTED se faltar o aceite
    source: FR2.3; AC2.3.1; AC2.3.2; AC2.3.3; AC2.3.4; Q6
  - id: BR3.9
    statement: As telas de convite e de novo aceite funcionam só pelo teclado, em ordem e com foco visível
    category: validation
    applies_to: ReacceptanceGate
    trigger: navegação por teclado
    logic: As caixas, os links e o botão são alcançáveis em ordem, com foco visível e rótulos visíveis
    violation: teste de componente falha
    source: NFR4; AC2.3.5; AC4.1.3
  - id: BR3.10
    statement: A Conta mostra a situação dos termos e, quando a pessoa não está em dia, o caminho para o novo aceite
    category: policy
    applies_to: TermsStatus
    trigger: leitura da Conta
    logic: A resposta da Conta traz terms (TermsStatus); SE upToDate é falso ENTÃO a tela mostra "Aceitar a nova versão"
    violation: não se aplica
    source: L2; Q7
  - id: BR3.11
    statement: A rota de aceite responde com p95 de até 500 ms com 20 convidados simultâneos
    category: constraint
    applies_to: AcceptTermsCommand
    trigger: cenário de carga
    logic: Uma consulta indexada da vigente e do aceite, e uma inserção
    violation: o cenário de carga reprova
    source: NFR3; AC4.2.4
```

## Resumo

| ID | Regra | Categoria |
|---|---|---|
| BR3.1 | Trava 403 com versão aceita diferente da vigente | authorization |
| BR3.2 | Rotas liberadas durante a trava | authorization |
| BR3.3 | Bots ativos seguem no worker; rotas de bot bloqueadas | policy |
| BR3.4 | Aceite novo único, sem alterar os anteriores | policy |
| BR3.5 | Aceite idempotente por chave e por versão | constraint |
| BR3.6 | `409 TERMS_VERSION_OUTDATED` para versão superada | validation |
| BR3.7 | Tela de novo aceite no app | policy |
| BR3.8 | Convite com as duas caixas e documentos carregados | validation |
| BR3.9 | Teclado e foco visível | validation |
| BR3.10 | Situação dos termos na Conta | policy |
| BR3.11 | p95 do aceite de até 500 ms | constraint |
