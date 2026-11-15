# Perguntas de Functional Design — U4 `u4-real-execution`

Conversa: português (Brasil). Base:
- `inception/units-generation/unit-of-work.md` (U4) e `inception/units-generation/unit-of-work-story-map.md`: US3.2, US3.3, US4.1 e US4.2 (dados), US5.2 e US8.3, mais o servidor da US3.1;
- `inception/user-stories/stories.md`;
- `inception/requirements-analysis/requirements.md`: FR4, FR5, FR3.4, FR7.1, FR7.4, NFR3, NFR4;
- `inception/domain-design/components.md` (RealExecution, ChainWatcher) e `decisions.md`;
- `inception/contract-design/contract-summary.md`: R1, R3, R6, M1 a M6, X1 e X3, mais os adendos da U1 e da U3;
- o código atual: os bots são todos PAPER; o TxSigner da U1 assina só saque (`OPERATION_NOT_SUPPORTED` para o resto); `ChainPort` da U1; barreira M5 da U3.

## Q1. [Bot REAL] Como um bot passa a operar no REAL?

A. O bot ganha o campo `market` (`PAPER` ou `REAL`), escolhido na criação e que nunca muda depois. O padrão é `PAPER`. Um bot `REAL` só pode ser criado e iniciado por quem tem carteira do bot ativa. No ciclo, o bot `REAL` não usa o ledger PAPER: ele chama a barreira (M5) e, se for aprovado, grava a decisão pela M6 `recordDecision` na mesma transação. Os bots PAPER não mudam.
B. Trocar um bot PAPER existente para REAL
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Modo Assinada] De onde sai o dinheiro e quem assina?

A. O dinheiro fica sempre na carteira do bot, nos três modos. No modo Assinada, a transação do swap tem duas assinaturas: a carteira principal do usuário é a pagadora da taxa e assina primeiro, no navegador, como aprovação; depois o TxSigner confere essa assinatura e assina como dono dos fundos. Sem a assinatura válida da carteira principal sobre a mesma mensagem, o TxSigner recusa. Assim o ledger, a barreira e o teto são os mesmos nos três modos, e o navegador só assina (ADR-008).
B. No modo Assinada, operar com o dinheiro da carteira principal, com ledger próprio
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Fila] Como a decisão vira ordem?

A. A M6 `recordDecision` grava a `RealDecisionRequest` na transação do ciclo. Um consumidor no worker busca as decisões pendentes a cada 200 ms com `FOR UPDATE SKIP LOCKED` e, numa transação curta, faz:
- a validação de posse do bot pelo tenant e pelo usuário;
- o `handOver` da barreira (M5);
- o `RESERVE` no ledger;
- a criação da ordem `PROPOSED`.

A cotação, a assinatura e o envio ficam fora dessa transação. Se a reserva da barreira já tiver vencido, a decisão é descartada com o motivo registrado.
B. Processar a ordem dentro do ciclo do bot
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Uma por bot] Como garantir uma ordem por bot em andamento (AC3.3.3)?

A. O ciclo do bot REAL pergunta à M6 se o bot tem ordem em andamento (estados `PROPOSED`, `QUOTED`, `SIGNED`, `SENT` e `AMBIGUOUS`). Se tiver, a decisão é descartada com o motivo `ORDER_IN_PROGRESS`, antes de chamar a barreira. Um índice único parcial no banco garante a regra mesmo com corrida.
B. Enfileirar as decisões novas e processar depois
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Idempotência] Como nunca gerar uma segunda transação (AC3.3.4, FR5.5)?

A. A chave de idempotência da ordem é o `decisionRequestId`, única na tabela de ordens. A assinatura on-chain é calculada a partir da transação assinada e gravada **antes** do envio. Um reenvio, mesmo depois de reinício, manda só a mesma transação assinada. Uma ordem nunca é assinada de novo. Se o blockhash vencer sem a transação na rede, a ordem vira `FAILED`, e só uma decisão nova do ciclo pode gerar outra ordem.
B. Reassinar com blockhash novo quando o envio falhar
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Cotação e montagem] Como cotar e montar o swap?

A. A cotação e a transação vêm da Jupiter (X1), com a derrapagem do usuário (padrão 2%, até 5%) e o valor da reserva como entrada da compra. Na venda, a entrada é a quantidade da posição. A cotação usada para assinar tem no máximo 10 s; vencida, a ordem cota de novo uma vez e, se vencer de novo, vira `FAILED` (`QUOTE_EXPIRED`). Antes de assinar e antes de enviar, a M1 `recheckBeforeSend` confere se o REAL está ligado, se o modo permite e se não há pausa ou kill switch. Se a conferência falhar, a ordem ainda não enviada vira `CANCELLED`.
B. Montar o swap sem a Jupiter
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Modo Assinada] Prazo e sessão de assinatura (US3.2)?

A. No modo Assinada, a ordem fica `QUOTED` com a sessão de assinatura: a transação montada, o resumo da mensagem e o que mostrar (token, valor, saída esperada e mínima, taxa e a razão do bot). O prazo é de 8 s depois da cotação, para a cotação ainda ter validade no envio. Depois do prazo, a ordem vira `CANCELLED` (`SIGNING_DEADLINE_EXPIRED`) e nada é enviado. A recusa na carteira é registrada como `CANCELLED` (`USER_REJECTED`). O servidor confere que a transação assinada recebida tem exatamente a mesma mensagem (`SIGNED_MESSAGE_MISMATCH`).
B. Prazo de 10 s
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Confirmação e ambígua] Como acompanhar o envio (US8.3)?

A. Depois do envio, a ordem fica `SENT` e o estado é consultado a cada 2 s. Ela vira `CONFIRMED` quando a rede finaliza. Sem confirmação em 60 s, vira `AMBIGUOUS` e o valor continua reservado. Um trabalho a cada 15 s confere as ambíguas pela assinatura:
- finalizada vira `CONFIRMED`;
- com o prazo do blockhash vencido e sem a transação na rede, vira `FAILED`.

Com o RPC fora, a ordem continua ambígua, o REAL do usuário pausa (`UPSTREAM`) e um alerta é emitido. Uma ordem ambígua nunca é reenviada nem reassinada antes da conferência.
B. Repetir o envio depois de 60 s
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q9. [Ledger] O que entra no ledger REAL?

A. Os lançamentos:
- **Na criação da ordem:** `RESERVE` da compra.
- **Na confirmação:** os valores reais lidos da transação na rede (saldos antes e depois), nunca da cotação:
  - `SWAP_FILL_IN` com os lamports gastos, negativo;
  - `SWAP_FILL_OUT` com os lamports recebidos, positivo;
  - o mint e a quantidade de tokens;
  - `NETWORK_FEE` e `RENT` quando houver;
  - o `RESERVE_RELEASE`.
- **Na falha com a transação na rede:** só a taxa e o `RESERVE_RELEASE`.
- **No cancelamento antes do envio:** só o `RESERVE_RELEASE`.

Todo lançamento tem a assinatura on-chain e uma chave de idempotência derivada da ordem. Isso segue a convenção do Adendo da U3.
B. Lançar pelos valores da cotação
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q10. [P&L REAL] Como calcular (US4.2)?

A. O P&L REAL é calculado pelo custo médio por token, em lamports: o realizado vem das vendas confirmadas, e o não realizado é a posição a preço de cotação, com `unknown` quando a cotação faltar, nunca zero. As taxas da rede entram no realizado. A rota `GET /api/v1/real/pnl` usa a mesma forma do relatório PAPER, mas com dados só do ledger REAL. Os cenários são determinísticos, em aritmética inteira.
B. Primeiro a entrar, primeiro a sair
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q11. [Troca de modo] Como o servidor troca o modo (US3.1)?

A. Pela rota `PUT /api/v1/real/mode`:
- **Rebaixar** vale na hora (M1 `lowerMode`). Na mesma transação, as ordens do usuário ainda não enviadas (`PROPOSED` e `QUOTED`) são canceladas, e as já enviadas são acompanhadas até o fim.
- **Subir** exige o desafio e a assinatura do consentimento (M1 `applyConsent`), e é recusado com `409 ORDER_AMBIGUOUS` se houver ordem ambígua não conferida.

A BotWallet repassa a mudança ao TxSigner, como já faz.
B. Exigir consentimento também para rebaixar
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q12. [Vender tudo e sacar] Como funciona (US5.2)?

A. `POST /api/v1/real/liquidations` cria uma liquidação e uma ordem de venda por posição, com origem `USER_LIQUIDATION`, avaliada pela barreira. Ela é recusada com `409` se já houver liquidação ou ordem ambígua em andamento.
- **Modo Automática total:** as vendas são assinadas pelo TxSigner.
- **Modo Assinada:** cada venda abre uma sessão de assinatura.

Quando a última venda termina, a U4 pede o saque total pela M1 `requestWithdrawal`, com chave de idempotência derivada da liquidação. Se uma venda falhar ou ficar ambígua, o saque leva só o que está livre, e a liquidação mostra as posições que ficaram, com o motivo.
B. Sacar só depois de todas as vendas confirmarem
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q13. [Política de swap no TxSigner] O que o TxSigner confere antes de assinar um swap?

A. A política de conteúdo do ADR-004, com os testes de ataque escritos antes do código. Ela recusa, com o motivo registrado:
- programa fora da lista (Jupiter v6, Token, Token-2022, Associated Token, Compute Budget e System só para embrulhar SOL);
- assinante ou dono dos fundos que não seja a carteira do bot;
- no modo Assinada, pagador que não seja a carteira principal registrada ou assinatura dela inválida;
- conta de destino que não seja da carteira do bot;
- saída mínima abaixo da cotação menos a derrapagem;
- troca de autoridade;
- fechamento de conta para terceiros;
- valor acima do teto autorizado;
- modo que não permite o swap.

O TxSigner também recusa assinar duas vezes o mesmo `signingRequestId` com conteúdo diferente.
B. Conferir só o valor
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q14. [Falha de fornecedor] O que acontece quando a Jupiter ou o RPC falham?

A. Uma falha da Jupiter ou do RPC antes da assinatura deixa a ordem `FAILED` (`UPSTREAM_UNAVAILABLE`) e libera a reserva. Três falhas seguidas do mesmo fornecedor pausam o REAL do usuário com o motivo `UPSTREAM`. A pausa é retomada sozinha depois de 5 min de fornecedor saudável. O saque nunca é afetado (NFR4).
B. Repetir até dar certo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q15. [Eventos] O que é auditado e avisado?

A. Cada mudança de estado da ordem gera um evento de auditoria REAL, com a assinatura e o mint nos campos tipados, e um evento `RealOrderStateChanged` no canal ao vivo, só para o dono. A sessão de assinatura aberta também gera aviso no canal. Os logs mascaram assinatura e endereço.
B. Só auditar os estados finais
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- O bot ganha o mercado `PAPER` ou `REAL`, fixo desde a criação; o bot REAL passa pela barreira e grava a decisão no ciclo [Q1].
- No modo Assinada o dinheiro continua na carteira do bot: a carteira principal assina primeiro como pagadora da taxa e o TxSigner assina depois [Q2].
- Uma fila no worker transforma a decisão em ordem, com reserva e posse do bot conferidas; a rede fica fora da transação [Q3].
- Uma ordem por bot em andamento, garantida no ciclo e por índice único [Q4].
- Uma única transação por ordem, sem nunca reassinar; a assinatura é gravada antes do envio [Q5].
- A cotação e o swap vêm da Jupiter, com cotação de no máximo 10 s e a reconferência de modo e pausa antes de assinar e de enviar [Q6].
- Assinada com prazo de 8 s, recusa registrada e conferência da mensagem [Q7].
- Confirmação acompanhada por 60 s; depois disso, ambígua conferida pela assinatura, sem reenvio [Q8].
- O ledger usa os valores reais da rede, com taxas e assinatura [Q9].
- P&L REAL por custo médio, com as taxas e `unknown` sem preço [Q10].
- A troca de modo rebaixa na hora e cancela as ordens não enviadas; subir exige consentimento [Q11].
- "Vender tudo e sacar" vende cada posição e saca o que está livre, mostrando o que ficou [Q12].
- O TxSigner ganha a política de swap do ADR-004, com testes de ataque antes do código [Q13].
- A falha da Jupiter ou do RPC falha a ordem; três seguidas pausam o REAL do usuário, e o saque continua livre [Q14].
- Auditoria e aviso no canal a cada mudança de estado [Q15].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
