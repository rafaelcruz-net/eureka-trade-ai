# Perguntas de Functional Design — U5 `u5-real-operations`

Conversa: português (Brasil). Base:
- `inception/units-generation/unit-of-work.md` (U5) e `inception/units-generation/unit-of-work-story-map.md` (US7.1, US7.2, US7.3, US8.1, US8.2, US8.4);
- `inception/user-stories/stories.md`;
- `inception/requirements-analysis/requirements.md` (FR7.2, FR7.3, FR8, FR10, FR12, FR15);
- `inception/contract-design/contract-summary.md` (R4, M1, M2, M5, M6, M7);
- pendências deixadas para a U5 pelas Units anteriores:
  - U1: anonimização de `main_wallet_address` e `consent_challenge.message`, saque limitado por divergência (BR4.6), reconciliação depois de restauração do banco, reenvio de `LOWER_CAP`;
  - U3: lista de bloqueio pela M5;
  - U4: `cancelUnsent` e `decision.mode` gravado como `PAPER`;
- código atual: liberação mínima da U1 (`real_release`), motivos de pausa da U1 (`DIVERGENCE`, `KILL_SWITCH`, `ADMIN`), kill switch PAPER da administração.

## Q1. [Portões verdes] Quais são os "portões automáticos" que a liberação confere (AC7.1.2, pergunta aberta de R4)?

A. Uma liberação passa a valer só quando, no momento em que vence a quarentena de 24 horas, todas estas condições estão verdes:
- a última execução da CI em `main` passou, com os testes obrigatórios (lida da API do GitHub pelo `gh` só no ambiente de produção, ou declarada por variável no local e nos testes);
- a última reconciliação do usuário não tem divergência aberta;
- o kill switch REAL está desligado;
- a rede configurada é a da liberação.

Se algum portão estiver vermelho na hora, a liberação fica `BLOCKED`, com o motivo, e é conferida de novo a cada 15 minutos até ficar verde ou ser revogada.
B. Só a quarentena de 24 horas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Ordem da liberação] Como garantir fundador primeiro e depois até 20 convidados (AC7.1.3, FR10.3)?

A. Na mainnet, a liberação de um convidado é recusada (`FOUNDER_FIRST`) enquanto o fundador não tiver liberação efetiva há pelo menos 7 dias sem divergência (regra do `team.md`). Ela também é recusada (`PILOT_FULL`) quando já há 20 convidados liberados. O fundador é o usuário com o papel `ADMIN` da conta do responsável. Na devnet e no validador local não há essa ordem.
B. Ordem só por procedimento, sem regra no sistema
C. Not yet defined
X. Other (please specify)

[Answer]: X — Fundador primeiro e no máximo 20 convidados, mas sem a espera de 7 dias: na mainnet, a liberação de um convidado é recusada (`FOUNDER_FIRST`) enquanto o fundador não tiver liberação efetiva, e recusada (`PILOT_FULL`) com 20 convidados liberados. A quarentena de 24 horas continua valendo para cada liberação. Na devnet e no validador local não há essa ordem. (Pedido do responsável do projeto na confirmação do resumo.)

## Q3. [Desligar o REAL] O que acontece ao revogar a liberação de um usuário?

A. Vale na hora. A liberação vira `REVOKED`. As ordens não enviadas do usuário são canceladas (M6 `cancelUnsent USER`), as enviadas são acompanhadas até o fim, e as condicionais abertas ficam marcadas para o usuário cancelar (U7). O saque continua disponível. Uma liberação nova recomeça a quarentena.
B. Desligar só depois que as ordens terminarem
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Reconciliação] Como reconciliar com a blockchain (US8.2)?

A. Por carteira do bot, depois de cada ordem confirmada e a cada 15 minutos. A reconciliação compara:
- o saldo de SOL na rede com o saldo do ledger REAL;
- o saldo de cada token na rede com a posição do ledger.

A comparação usa o último slot finalizado e só os lançamentos com assinatura já finalizada. Ordens `SENT` ou `AMBIGUOUS` são tratadas como em trânsito e não contam como divergência. Uma diferença acima de zero, fora das taxas de aluguel previstas, abre uma divergência, emite alerta e pausa o REAL do usuário (`DIVERGENCE`).
B. Comparar só o saldo de SOL
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Divergência e correção] Como uma divergência é resolvida?

A. O administrador vê a divergência com os dois saldos, a diferença e as assinaturas recentes. Ele resolve com um lançamento compensatório (M1 `compensate`, gravado pela M2, com motivo obrigatório e o administrador como ator). Uma reconciliação nova roda na hora; só se ela vier limpa a pausa `DIVERGENCE` sai. Cada passo é auditado.
B. Despausar sem reconciliar de novo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Saque com divergência] O saque fica limitado quando há divergência (BR4.6 da U1)?

A. Sim. Com divergência aberta, o saque continua disponível, mas limitado ao menor dos dois saldos (ledger e rede), para nunca sacar o que talvez não exista. O saque total (`ALL`) também segue esse limite. A regra do `project.md` de saque sempre disponível continua valendo.
B. Saque sem limite com divergência
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Kill switch REAL] Como funciona (US8.1)?

A. É um interruptor global, separado do kill switch PAPER. Ao ligar:
- nenhuma ordem REAL nova sai: a barreira e a reconferência já leem a pausa `KILL_SWITCH`;
- as ordens não enviadas de todos são canceladas (M6 `cancelUnsent ALL`);
- as enviadas são acompanhadas;
- as condicionais abertas ficam marcadas para o usuário cancelar (U7, pela M7);
- o saque continua e as posições ficam como estão.

Ligar e desligar exigem o papel `ADMIN` e o motivo, e são auditados. Desligar retira a pausa `KILL_SWITCH` de todos e não religa nenhum bot.
B. Usar o mesmo kill switch do PAPER
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Teto e bloqueio] Como o administrador baixa o teto e bloqueia tokens (US7.2)?

A. Baixar o teto chama a M1 `adminLowerCap`, que já repassa ao TxSigner (`LOWER_CAP`). Se o TxSigner não confirmar, o reenvio fica pendente e é tentado a cada minuto até confirmar (pendência da U1). Subir o teto nunca é pelo administrador. O bloqueio e o desbloqueio de token chamam a M5 da U3 e valem a partir do registro para todos os bots.
B. Teto e bloqueio sem repasse ao TxSigner
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q9. [Visão do administrador] O que a tela do administrador mostra (US7.3)?

A. Por usuário, a rota R4 devolve:
- o modo e o teto;
- o saldo REAL (livre, reservado, acima do teto);
- o estado da liberação e a quarentena;
- as pausas;
- as últimas 20 ordens;
- as divergências abertas.

A carteira principal aparece só com os 4 primeiros e os 4 últimos caracteres. A carteira do bot aparece inteira, porque é pública e serve para conferir na rede. Nenhuma chave aparece. As telas ficam na U6.
B. Mostrar a carteira principal inteira
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q10. [Anonimização] Como a anonimização trata a carteira do bot (US8.4)?

A. A anonimização chama a M1 `closeForAnonymization`. Com saldo, posição ou ordem em andamento, ela é recusada com a explicação de sacar antes (`BALANCE`, `POSITION`, `ORDER_IN_PROGRESS`). Sem nada disso:
- a carteira do bot é encerrada;
- a política do TxSigner da carteira é desativada;
- `main_wallet_address` e `consent_challenge.message` são limpos (pendência da U1);
- as ordens ficam sem ligação com a pessoa (U4).

O teste obrigatório confere que nenhum endereço sobra em tabela ou log.
B. Anonimizar com saldo e mandar o saldo para a carteira principal
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q11. [Restauração do banco] O que acontece depois de restaurar o banco de um backup (pendência da U1)?

A. Na subida, se o último slot reconciliado for mais antigo que a última atividade conhecida na rede, a U5 marca uma reconciliação completa obrigatória de todas as carteiras. Enquanto ela não terminar limpa, o REAL de todos fica pausado (`DIVERGENCE`) e o saque continua.
B. Nada especial
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q12. [Modo da decisão] Corrigir o `decision.mode` dos bots REAL, hoje gravado como `PAPER` (pendência da U4)?

A. Sim. Uma migração só de expansão aceita `REAL` no CHECK de `decision.mode`, e o ciclo REAL passa a gravar `REAL`. As decisões REAL antigas, só de teste, não são reescritas.
B. Deixar como está
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Portões verdes: CI de `main` verde, sem divergência, kill switch desligado e rede certa, conferidos ao vencer a quarentena; vermelho deixa a liberação `BLOCKED` e reconfere a cada 15 min [Q1].
- Na mainnet: fundador primeiro (convidado só depois da liberação efetiva do fundador, sem espera de 7 dias), no máximo 20 convidados; quarentena de 24 h para cada um [Q2].
- Revogar vale na hora: cancela as ordens não enviadas e mantém o saque [Q3].
- Reconciliação de SOL e tokens depois de cada ordem e a cada 15 min, com ordens em trânsito à parte; divergência pausa o usuário e alerta [Q4].
- Correção por lançamento compensatório e nova reconciliação limpa antes de despausar [Q5].
- Com divergência, o saque fica limitado ao menor saldo, mas continua disponível [Q6].
- Kill switch REAL próprio: cancela as ordens não enviadas, mantém o saque, e desligar não religa bots [Q7].
- Baixar teto com reenvio ao TxSigner até confirmar; bloqueio de token pela M5 [Q8].
- Visão do administrador sem chave e com a carteira principal abreviada [Q9].
- Anonimização recusada com saldo ou posição; sem eles, encerra a carteira e limpa os endereços [Q10].
- Depois de restaurar o banco, reconciliação completa obrigatória com o REAL pausado [Q11].
- `decision.mode` passa a gravar `REAL` nos bots REAL [Q12].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
