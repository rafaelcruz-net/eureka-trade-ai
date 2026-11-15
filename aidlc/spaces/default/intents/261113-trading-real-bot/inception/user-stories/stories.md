# Histórias de usuário — Trading REAL

Conversa: português (Brasil). Base: `inception/requirements-analysis/requirements.md` (FR1 a FR15, NFR1 a NFR10), `personas.md`, o mapa do código (`business-overview.md`, `component-inventory.md`) e `user-stories-questions.md` (Q1 a Q11). Prioridade em MoSCoW; as histórias Must formam a primeira versão REAL.

## Grupo 1 — Termos e ativação

### US1.1 Aceitar os termos do REAL (Must)
Como convidado (P2), quero ler e aceitar os termos do REAL antes de ativar a carteira do bot, para saber o risco que assumo.
- **AC1.1.1** Dado que não aceitei a v3 dos termos, quando abro a ativação da carteira do bot, então vejo os termos v3 e não consigo ativar sem aceitá-los.
- **AC1.1.2** Dado que aceitei a v3, quando volto à ativação, então ela segue para o consentimento.
- **AC1.1.3** Os termos dizem que posso perder todo o valor depositado, que a carteira do bot tem responsabilidade compartilhada e que não há promessa de lucro.
- **AC1.1.4** Dado um erro ao registrar o aceite, quando confirmo, então vejo a falha em texto com "tentar de novo" e a ativação continua bloqueada.

### US1.2 Ativar a carteira do bot (Must)
Como convidado (P2), quero ativar uma carteira do bot assinando um consentimento, para que o bot possa operar sem mim online.
- **AC1.2.1** Dado que aceitei os termos e o REAL foi liberado para mim, quando assino o consentimento na carteira principal, então uma carteira do bot é criada e vejo o endereço dela.
- **AC1.2.2** A chave da carteira do bot é criada dentro do KMS e não aparece em nenhuma resposta, log ou tela.
- **AC1.2.3** Dado que assino com a mensagem de login em vez da de consentimento, quando envio, então a ativação é recusada.
- **AC1.2.4** Dado que já tenho uma carteira do bot, quando tento ativar outra, então a segunda é recusada.
- **AC1.2.5** Dado que o REAL não foi liberado para mim, quando tento ativar, então vejo que o REAL está indisponível para minha conta.

## Grupo 2 — Teto e depósito

### US2.1 Definir o teto no perfil (Must)
Como convidado (P2), quero escolher o meu teto até o máximo, para limitar quanto posso perder.
- **AC2.1.1** Dado o máximo de 10 SOL, quando defino 3 SOL, então o teto passa a ser 3 SOL.
- **AC2.1.2** Dado o máximo de 10 SOL, quando tento 12 SOL, então o valor é recusado com a explicação do máximo.
- **AC2.1.3** O teto vale sobre o saldo livre mais o valor das posições abertas a preço de cotação; uma posição com preço `unknown` vale pelo custo de compra.
- **AC2.1.4** Dado um teto abaixo da exposição atual, quando ele é salvo, então novas compras ficam bloqueadas até a exposição voltar abaixo do teto, as vendas continuam permitidas e nada é vendido à força.

### US2.2 Depositar SOL (Must)
Como convidado (P2), quero depositar SOL da minha carteira principal na carteira do bot, para que o bot tenha saldo.
- **AC2.2.1** Dado o endereço da carteira do bot, quando envio SOL assinando na minha carteira, então o depósito aparece no saldo depois de finalizado na rede.
- **AC2.2.2** Dado um depósito que deixa o saldo acima do teto, quando ele é detectado, então o bot opera só até o teto e vejo o excedente como disponível só para saque.
- **AC2.2.3** Uma reserva mínima de SOL para taxas fica fora do saldo operável e aparece separada na tela.

## Grupo 3 — Operar por modo

### US3.1 Escolher e trocar o modo (Must)
Como convidado (P2), quero escolher entre Assinada, Automática nas saídas e Automática total, para decidir quanto o bot faz sozinho.
- **AC3.1.1** O modo inicial é Assinada.
- **AC3.1.2** Dado o modo Automática total, quando volto para Assinada, então a troca vale na hora.
- **AC3.1.3** Dado o modo Assinada, quando subo para Automática total, então preciso assinar um consentimento antes da troca valer.
- **AC3.1.4** Dado um rebaixamento de modo com ordem em andamento, quando ele vale, então as ordens ainda não enviadas são canceladas e as já enviadas são acompanhadas até o fim.

### US3.2 Assinar uma ordem no modo Assinada (Must)
Como convidado (P2), quero assinar cada ordem real na minha carteira, para aprovar o que sai.
- **AC3.2.1** Dado uma proposta do bot, quando a abro, então vejo token, valor, preço estimado, derrapagem máxima, taxa e a razão do bot.
- **AC3.2.2** Dado que assino em até 10 s, então o servidor envia a transação e acompanha a confirmação.
- **AC3.2.3** Dado que não assino em 10 s, então a proposta expira, nada é enviado e o próximo ciclo pode gerar outra.
- **AC3.2.4** Dado que recuso na carteira, então nada é enviado e a recusa fica registrada.

### US3.3 Execução automática pela carteira do bot (Must)
Como fundador operador (P1), quero que o bot execute ordens sozinho pela carteira do bot, para operar sem estar online.
- **AC3.3.1** Dado o modo Automática total e uma decisão aprovada pela barreira, quando o bot decide, então a ordem passa pelos estados proposta, cotada, assinada, enviada e confirmada, e a transação é enviada com p95 de até 3 s desde a decisão.
- **AC3.3.2** A cotação usada para assinar tem no máximo 10 s; a derrapagem máxima é a do usuário (2% padrão, até 5%).
- **AC3.3.3** No máximo uma ordem REAL por bot fica em andamento; uma decisão nova espera ou é descartada.
- **AC3.3.4** Dado uma repetição da mesma ordem (mesma chave de idempotência), então nenhuma segunda transação é criada, inclusive depois de um reinício.
- **AC3.3.5** Dado que a rede rejeita a transação por derrapagem, então a ordem fica falhou, o saldo não muda além da taxa e a falha é registrada.

### US3.4 Saídas automáticas (Should)
Como convidado (P2), quero que o stop e o alvo de uma posição disparem sem eu estar online, para limitar perdas.
- **AC3.4.1** Dado uma posição aberta no modo Automática nas saídas, quando assino o stop e o alvo, então eles viram ordens condicionais da Jupiter.
- **AC3.4.2** Dado o preço atingindo o stop, então a venda acontece sem eu online e aparece no ledger REAL.

## Grupo 4 — Acompanhar

### US4.1 Ver as ordens e seus estados (Must)
Como convidado (P2), quero ver cada ordem real com seu estado e o link da transação, para acompanhar o que o bot fez.
- **AC4.1.1** Cada ordem mostra estado, token, quantidade, preço, taxa, horário (UTC convertido para meu fuso) e o link da transação na rede.
- **AC4.1.2** Uma ordem ambígua aparece como "em conferência", com o valor reservado.
- **AC4.1.3** Estados de carregando, vazio e erro são distintos e em texto.

### US4.2 Ver o P&L e o ledger REAL (Must)
Como convidado (P2), quero ver o P&L e o ledger REAL separados do PAPER, para saber meu resultado real.
- **AC4.2.1** O P&L REAL inclui as taxas da rede e não mistura nenhum valor PAPER.
- **AC4.2.2** Cada lançamento REAL mostra a assinatura on-chain que o originou.

### US4.3 Exportar o histórico fiscal (Should)
Como convidado (P2), quero exportar meu histórico REAL, para declarar impostos.
- **AC4.3.1** A exportação tem data, token, quantidade, preço, taxa e assinatura de cada operação.

## Grupo 5 — Sacar

### US5.1 Sacar para a carteira principal (Must)
Como convidado (P2), quero sacar SOL da carteira do bot para minha carteira principal a qualquer momento, para ter meu dinheiro de volta.
- **AC5.1.1** O saque vai só para a carteira principal verificada; qualquer outro destino é recusado.
- **AC5.1.2** Dado o kill switch acionado, quando peço o saque, então ele é processado.
- **AC5.1.3** Dado uma ordem em andamento (cotada, assinada, enviada ou ambígua), quando peço o saque, então o valor dela fica reservado e só o resto do saldo livre é sacado.
- **AC5.1.4** Dado um saque total sem posições nem ordens, então a reserva para taxas também é devolvida, menos a taxa do próprio saque.

### US5.2 Vender tudo e sacar (Must)
Como convidado (P2), quero pedir "vender tudo e sacar", para sair de uma vez.
- **AC5.2.1** Dado posições abertas, quando peço, então o sistema vende cada posição e, ao final, saca o SOL para a carteira principal.
- **AC5.2.2** Dado uma venda que falha ou fica ambígua, então o saque libera o que já está livre e eu vejo quais posições ficaram, com o motivo.

## Grupo 6 — Barreira de risco

### US6.1 Respeitar os limites (Must)
Como fundador operador (P1), quero que nenhuma ordem passe dos limites, para que a perda máxima seja a que aceitei.
- **AC6.1.1** Uma ordem acima de 10% do teto é recusada.
- **AC6.1.2** A perda do dia é a perda realizada mais a não realizada, com taxas, desde 00:00 UTC; ao chegar a 20% do teto vigente no início do dia, o bot pausa até o dia seguinte.
- **AC6.1.3** A 21ª ordem do dia de um bot é recusada.
- **AC6.1.4** Dado duas decisões simultâneas do mesmo bot, então os limites são conferidos sob trava e as duas juntas nunca passam dos limites.
- **AC6.1.5** Uma ordem ambígua conta nos limites e no saldo reservado até terminar.
- **AC6.1.6** A barreira REAL usa só o saldo e o ledger REAL.

### US6.2 Operar só tokens permitidos (Must)
Como fundador operador (P1), quero que o bot só opere tokens avaliados, para não comprar um token sem dados.
- **AC6.2.1** Um token sem avaliação do scanner com cobertura suficiente é recusado.
- **AC6.2.2** Dado uma posição com preço `unknown`, então o bot não abre nova compra até o preço voltar.
- **AC6.2.3** Um token na lista de bloqueio do administrador é recusado.

### US6.3 Dados reais e frescos (Must)
Como convidado (P2), quero que o bot decida com dados reais e recentes, para não operar com informação errada.
- **AC6.3.1** O scanner mostra dados reais da fonte paga, com fonte, horário e a atribuição exigida.
- **AC6.3.2** Com a fonte simulada ativa, nenhuma ordem REAL sai.
- **AC6.3.3** Dados do sinal com mais de 60 s ou cotação com mais de 10 s fazem a ordem ser recusada como dado vencido.

## Grupo 7 — Administração

### US7.1 Liberar o REAL por usuário (Must)
Como administrador (P3), quero liberar o REAL para um usuário com quarentena, para abrir aos poucos.
- **AC7.1.1** O REAL nasce desligado para todos.
- **AC7.1.2** Dado que libero um usuário, então a liberação só vale 24 horas depois e só se os portões automáticos (testes obrigatórios e reconciliação sem divergência) estiverem verdes.
- **AC7.1.3** A primeira liberação na mainnet é a do fundador; as dos convidados vêm depois.
- **AC7.1.4** Na mainnet, o REAL só funciona com a configuração explícita do ambiente de produção.

### US7.2 Ajustar tetos e bloquear tokens (Must)
Como administrador (P3), quero baixar o teto de um usuário e bloquear tokens, para reduzir risco rápido.
- **AC7.2.1** Baixar o teto de um usuário vale na hora, com o mesmo efeito de AC2.1.4.
- **AC7.2.2** Um token bloqueado não pode ser comprado por nenhum bot a partir do bloqueio.

### US7.3 Ver o estado do REAL (Must)
Como administrador (P3), quero ver, por usuário, modo, teto, saldo REAL, liberação, ordens recentes e divergências, para acompanhar o piloto.
- **AC7.3.1** A tela mostra esses dados sem a chave nem a carteira principal completa do usuário.

## Grupo 8 — Operação

### US8.1 Kill switch no REAL (Must)
Como administrador (P3), quero parar o REAL de todos com um comando, para conter um incidente.
- **AC8.1.1** Dado o kill switch acionado, então nenhuma ordem REAL nova sai.
- **AC8.1.2** Ordens ainda não enviadas são canceladas; as enviadas são acompanhadas até o fim.
- **AC8.1.3** As ordens condicionais abertas das carteiras do bot são canceladas.
- **AC8.1.4** O saque continua liberado e as posições ficam como estão.

### US8.2 Reconciliar com a blockchain (Must)
Como fundador operador (P1), quero que o ledger REAL seja conferido com a rede, para confiar no saldo.
- **AC8.2.1** A reconciliação roda depois de cada ordem confirmada e a cada 15 minutos.
- **AC8.2.2** Dado uma divergência, então um alerta é emitido e o REAL do usuário pausa até a correção por lançamento compensatório.

### US8.3 Resolver ordens ambíguas (Must)
Como fundador operador (P1), quero que uma ordem sem confirmação tenha um fim definido, para nunca duplicar nem perder rastro.
- **AC8.3.1** Uma ordem sem confirmação em 60 s fica ambígua.
- **AC8.3.2** Ela vira confirmada quando a rede finaliza a transação e falhou quando o prazo do blockhash expira sem a transação na rede.
- **AC8.3.3** Com o RPC fora, ela continua ambígua, o valor fica reservado, o REAL do usuário pausa e um alerta é emitido.
- **AC8.3.4** Uma ordem ambígua nunca é repetida antes de ser conferida na rede.

### US8.4 Anonimizar uma conta com carteira do bot (Must)
Como administrador (P3), quero que a anonimização respeite a carteira do bot, para não perder fundos do usuário.
- **AC8.4.1** Dado saldo ou posição na carteira do bot, então a anonimização é recusada com a explicação de sacar antes.
- **AC8.4.2** Sem saldo e sem posição, a anonimização encerra a carteira do bot.

### US8.5 Auditar o REAL (Must)
Como administrador (P3), quero que cada passo do REAL fique na trilha de auditoria, para reconstruir qualquer operação.
- **AC8.5.1** Ativação, depósito, cada mudança de estado da ordem, saque, troca de modo, mudança de teto, liberação e kill switch geram evento.
- **AC8.5.2** A assinatura da transação, o mint e a carteira do bot ficam em campos próprios; a carteira principal fica só como HMAC; nada disso aparece em log sem máscara.

## Dependências entre histórias

- US1.1 → US1.2 → US2.1, US2.2 → US3.x.
- US6.1, US6.2, US6.3 vêm antes de qualquer ordem real (US3.2, US3.3).
- US8.2 e US8.3 dependem de US3.3.
- US7.1 controla a disponibilidade de US1.2.

## Notas INVEST

As histórias são pequenas (1 a 3 dias), testáveis por critério Given/When/Then e cada uma entrega valor a uma persona; as dependências acima são de ordem de entrega, não de teste: cada história pode ser testada isoladamente com dados de teste.
