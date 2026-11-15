# Perguntas de Histórias de Usuário — Trading REAL

Conversa: português (Brasil). Base: `inception/requirements-analysis/requirements.md` e o mapa do código (`business-overview.md`, `component-inventory.md`). As perguntas Q4 a Q11 fecham, nos critérios de aceite das histórias, as lacunas apontadas na revisão dos requisitos (teto, limites, concorrência e ordem ambígua).

## Q1. [Produto] Quais personas as histórias usam?

A. Três: o fundador operando REAL na própria carteira; o convidado operando REAL; o administrador (o mesmo fundador, no papel de administração)
B. Só o usuário e o administrador
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Produto] Como dividir as histórias?

A. Pela jornada do usuário (aceitar termos e ativar, definir teto e depositar, operar por modo, acompanhar e conciliar, sacar), mais um grupo de administração e um de operação (kill switch, liberação, reconciliação)
B. Por módulo técnico
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Produto] Qual granularidade?

A. Histórias pequenas (1 a 3 dias), cada uma com 3 a 6 critérios de aceite em Given/When/Then, incluindo pelo menos um caminho de erro
B. Histórias grandes por épico
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Produto] Sobre o que o teto vale, e como avaliar uma posição sem preço?

A. O teto vale sobre o saldo livre mais o valor das posições abertas a preço de cotação; uma posição com preço `unknown` é avaliada pelo custo de compra, e o bot não abre nova compra enquanto houver posição com preço `unknown`
B. Só sobre o saldo livre em SOL
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Produto] Como se calcula a perda do dia?

A. Perda realizada mais não realizada, com taxas, desde 00:00 UTC, comparada com 20% do teto vigente no início do dia
B. Só perda realizada
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Produto] O que acontece quando o teto é baixado abaixo da exposição atual?

A. Novas compras ficam bloqueadas até a exposição voltar abaixo do teto; vendas continuam permitidas; nada é vendido à força
B. O sistema vende o excedente na hora
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Produto] Como fica o saque com uma ordem em andamento?

A. O valor de ordens cotadas, assinadas, enviadas ou ambíguas fica reservado e não pode ser sacado até a ordem terminar; o resto do saldo livre pode ser sacado
B. O saque cancela as ordens em andamento
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Produto] O que acontece com ordens em andamento quando o kill switch é acionado, o REAL é desligado ou o modo é rebaixado?

A. Ordens ainda não enviadas são canceladas; ordens já enviadas são acompanhadas até o fim e registradas; nenhuma ordem nova sai
B. Tudo em andamento é ignorado
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q9. [Produto] Quando uma ordem ambígua termina, e o que vale enquanto isso?

A. Vira confirmada quando a rede finaliza a transação; vira falhou quando o prazo do blockhash expira sem a transação na rede; se o RPC também cair, continua ambígua com o valor reservado, conta nos limites, o REAL do usuário fica pausado e um alerta é emitido; o estado vem do banco depois de um reinício
B. Vira falhou depois de 60 s
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q10. [Produto] E se, no modo Assinada, o usuário não assinar a tempo?

A. A proposta expira junto com a cotação (10 s); nada é enviado; o próximo ciclo do bot pode gerar uma proposta nova
B. A proposta fica esperando sem prazo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q11. [Produto] Duas ordens do mesmo bot podem correr ao mesmo tempo?

A. Não: no máximo uma ordem REAL em andamento por bot; teto, limite por ordem, perda do dia e contagem de ordens são conferidos sob trava, de modo que ordens simultâneas nunca ultrapassam os limites
B. Sim, sem trava
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
