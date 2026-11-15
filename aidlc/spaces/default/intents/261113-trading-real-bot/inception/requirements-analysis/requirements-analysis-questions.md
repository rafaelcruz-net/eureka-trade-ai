# Perguntas de Análise de Requisitos — Trading REAL

Conversa: português (Brasil). Base: `ideation/intent-capture/intent-statement.md`, `ideation/scope-definition/scope-document.md` e o mapa do código em `aidlc/spaces/default/codekb/eureka-trader-ai/` (`business-overview.md`, `architecture.md`, `code-structure.md`).

## Q1. [Arquitetura] Como a ordem REAL é executada, se o ciclo do bot é uma única transação curta de banco?

O ciclo atual roda numa transação com trava de 2 s e limite de 5 s por comando; cotar, assinar, enviar e confirmar na rede não cabe nela.

A. A ordem REAL vira um registro com estados gravados (proposta, cotada, assinada, enviada, confirmada, falhou, ambígua), processado fora da transação do ciclo; uma ordem ambígua é conferida na rede pela assinatura antes de qualquer nova tentativa, nunca repetida às cegas
B. Aumentar os limites da transação do ciclo e executar tudo dentro dela
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Segurança] Como a trilha de auditoria registra as transações reais?

Hoje o validador recusa a chave `signature` e qualquer texto Base58 de 32 bytes (trata como carteira) ou de 80 a 90 caracteres.

A. Eventos REAL ganham campos próprios e tipados para a assinatura da transação, o mint e a carteira do bot (endereços públicos do sistema); a carteira principal do usuário continua só como HMAC; os campos livres de `details` continuam com a regra atual; os logs continuam mascarando
B. Relaxar a regra do validador para todos os eventos
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Produto] O que acontece na anonimização (LGPD) de uma conta com carteira do bot?

A anonimização apaga o endereço da carteira principal, que é o único destino de saque.

A. A anonimização exige a carteira do bot sem saldo e sem posição (o usuário saca antes); com saldo ou posição, ela é recusada com a explicação; depois de anonimizar, a carteira do bot é encerrada
B. Vender tudo e sacar automaticamente antes de anonimizar
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Produto] Como fica o modo Assinada diante da regra "nunca executar ordens no navegador depois que o bot é ativado"?

A. A regra é ajustada: no modo Assinada, o navegador só assina a transação montada pelo servidor; montar, enviar e acompanhar continuam no servidor
B. Remover o modo Assinada desta intent
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Produto] Qual frescor de dados vale para o REAL?

A. Cotação da Jupiter com no máximo 10 s na hora de assinar; dados de mercado do sinal com no máximo 60 s; fora disso a ordem é recusada como dado vencido
B. Os mesmos limites do PAPER (60 s na barreira e 5 min no preenchimento)
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Produto] Qual derrapagem máxima de preço por ordem?

Memecoins variam muito; derrapagem baixa demais faz a ordem falhar, alta demais perde dinheiro.

A. Padrão de 2%, configurável pelo usuário até no máximo 5%
B. Fixa em 1%
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Produto] Como é a reconciliação com a blockchain?

A. Depois de cada ordem confirmada e a cada 15 minutos; uma divergência gera alerta e pausa o REAL daquele usuário até ser resolvida por lançamento compensatório
B. Só uma vez por dia
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Produto] Como funciona o depósito na carteira do bot?

Não dá para impedir uma transferência na rede; o teto precisa valer depois do depósito.

A. O usuário envia SOL da carteira principal para o endereço da carteira do bot (assinando na própria carteira); o sistema detecta o depósito na rede; o bot só opera até o teto, e o excedente fica disponível só para saque, com aviso na tela
B. Recusar e devolver automaticamente o excedente
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q9. [Produto] Como funciona o saque?

A. A qualquer momento, inclusive com o kill switch acionado; só para a carteira principal verificada; com posições abertas, saca o SOL livre, e o usuário pode pedir "vender tudo e sacar"
B. Só com todas as posições fechadas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q10. [Produto] Quem paga as taxas da rede?

A. A carteira do bot paga as taxas, que entram no P&L REAL; uma reserva mínima de SOL para taxas fica fora do saldo operável
B. O produto paga as taxas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q11. [Arquitetura] Como a rede (devnet ou mainnet) é escolhida?

Hoje a rede está fixa em `solana:mainnet` na configuração.

A. Por ambiente: devnet e validador local nos testes; mainnet só com uma configuração explícita do ambiente de produção; o padrão é o REAL desligado
B. Sempre mainnet
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q12. [Jurídico] Como ficam os termos de uso?

Os termos v1 e v2 dizem que o REAL está indisponível.

A. Uma versão nova dos termos (v3) descreve o REAL, o risco de perda total do valor depositado, a carteira do bot com responsabilidade compartilhada e a ausência de promessa de lucro; o novo aceite é exigido antes de ativar a carteira do bot
B. Manter os termos atuais e só avisar na tela
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q13. [Arquitetura] Qual meta de tempo vale para a execução REAL?

A. Da decisão do bot ao envio da transação: p95 de até 3 s; confirmação acompanhada por até 60 s, depois disso a ordem fica ambígua e é conferida na rede
B. Sem meta de tempo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Execução: ordem REAL com estados gravados, fora da transação do ciclo; ordem ambígua conferida na rede antes de nova tentativa
- Auditoria: campos próprios para assinatura, mint e carteira do bot; carteira principal só como HMAC; logs mascarados
- Anonimização: exige carteira do bot sem saldo e sem posição; depois a carteira do bot é encerrada
- Modo Assinada: o navegador só assina; o servidor monta, envia e acompanha
- Frescor: cotação com até 10 s, sinal com até 60 s
- Derrapagem: 2% padrão, até 5%
- Reconciliação: após cada ordem e a cada 15 min; divergência pausa o REAL do usuário
- Depósito: SOL da carteira principal; opera até o teto; excedente só para saque
- Saque: a qualquer momento, só para a carteira principal; opção vender tudo e sacar
- Taxas: pagas pela carteira do bot, no P&L, com reserva mínima
- Rede: por ambiente; REAL desligado por padrão
- Termos: v3 com novo aceite antes de ativar a carteira do bot
- Tempo: decisão ao envio p95 até 3 s; confirmação até 60 s

Does this all look correct before I generate the requirements artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
