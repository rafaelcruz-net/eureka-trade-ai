# Perguntas do Functional Design — U5 `u5-anonymization`

Conversa: português (Brasil). Todas as respostas são as recomendadas (A), pelo pedido do responsável do projeto em 2026-11-10: "Siga sempre as recomendações sem me pergunta".

A U5 cobre US6.1, US6.2 e US6.3 (FR6.1 a FR6.4), a rota de anonimização de P2 e as interfaces de escrita J1 a J3. O que o código tem hoje:
- **`wallet`:** endereço e provedor `NOT NULL`, endereço único e `CHECK` de Base58.
- **`app_user`:** `status` aceita `ACTIVE` e `DISABLED`.
- **`feedback_entry`:** o gatilho só deixa o papel `feedback_retention` apagar.
- **Parada de bot:** a regra atual leva a `CLOSING_PENDING` quando há posições.

## Q1 — Bots com posições abertas

A. A anonimização trava os bots da pessoa e leva todos os que não estão parados (`ACTIVE`, `PAUSED` e `CLOSING_PENDING`) direto para `STOPPED`, com o nome "Bot anonimizado". As posições PAPER abertas ficam como estão, sem ordem nova de fechamento: o ledger não muda, a reconciliação continua em 0 divergência e nenhuma ordem nova fica ligada à conta depois da anonimização (AC6.2.6). A regra nova `anonymizeStop` entra na máquina de estados do bot, separada da parada comum. (recomendado)
B. Usar a parada comum, que fecha as posições.
X. Other (please specify)

[Answer]: A

## Q2 — Apagar o feedback na mesma transação

A. Uma migração atualiza o gatilho de `feedback_entry`. O `DELETE` passa a valer também quando a transação tem a configuração local `memetrade.erasure = 'on'`, ligada por `SET LOCAL` só pela implementação da J3 `deleteByUser`. Um teste de arquitetura garante que só essa classe contém essa instrução, e o teste do gatilho garante que sem a configuração o `DELETE` continua recusado. Assim o apagar entra na transação única da anonimização, pela conexão principal. (recomendado)
B. Dar o papel `feedback_retention` à conexão principal.
X. Other (please specify)

[Answer]: A

## Q3 — Estado da conta e carteira

A. Uma migração só acrescenta ou relaxa:
- `app_user_status_ck` passa a aceitar `ANONYMIZED`;
- `wallet.address` e `wallet.provider` aceitam nulo só quando a conta está anonimizada, por um `CHECK` com a coluna nova `wallet.anonymized_at`;
- a unicidade de `address` continua e ignora os nulos.

A J1 `anonymizeAccount`:
- trava a linha da conta;
- grava `ANONYMIZED`;
- apaga o endereço e o provedor;
- revoga todas as sessões;
- apaga os desafios de login desse endereço.

Login e renovação recusam conta `ANONYMIZED` com a mesma resposta genérica de conta inexistente. A mesma carteira só volta por convite novo, como conta nova. (recomendado)
B. Apagar a linha da carteira.
X. Other (please specify)

[Answer]: A

## Q4 — Trilha de auditoria depois da anonimização

A. A trilha não é editada (append-only). Os eventos antigos da pessoa guardam só a carteira mascarada e o HMAC (U2), nunca o endereço completo. O HMAC sem a chave não identifica a pessoa, e os eventos saem no expurgo de 12 meses. O teste obrigatório procura o endereço completo em todas as tabelas, inclusive a trilha e a saída da trilha, e nos logs capturados. (recomendado)
B. Outra política.
X. Other (please specify)

[Answer]: A

## Q5 — Tela de anonimização (T8)

A. No detalhe administrativo de um pedido de Exclusão em andamento, o botão "Anonimizar conta" abre um diálogo. O diálogo explica o que sai e o que fica, e o envio só fica habilitado com o texto exato `ANONIMIZAR` digitado; com outro texto, nada é enviado. Depois do sucesso, o pedido aparece Concluído e a resposta modelo de exclusão aparece para enviar. O diálogo funciona só pelo teclado e prende o foco enquanto está aberto. (recomendado)
B. Uma página própria.
X. Other (please specify)

[Answer]: A
