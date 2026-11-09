# Histórias de usuário — Termos de uso e LGPD

Conversa: português (Brasil). Base: `inception/requirements-analysis/requirements.md` (FR, NFR), `aidlc/spaces/default/codekb/eureka-trader-ai/business-overview.md`, `component-inventory.md`, `inception/practices-discovery/team-practices.md` e as respostas de `user-stories-questions.md`. Personas em `personas.md`.

INVEST: cada história cabe em 1 a 3 dias e tem critérios verificáveis. Quando uma história depende de outra, a seção "Dados de teste" diz qual estado semear para testá-la sozinha.

## US1 — Documentos e publicação

### US1.1 — Publicar a versão v2 dos documentos (Must)
Como **Administrador**, quero publicar a versão v2 com os termos de uso, a política de privacidade e o aviso de risco, cada um com resumo, para que os convidados leiam documentos fiéis ao sistema. (FR1.1, FR1.2)

- **AC1.1.1** Dado o catálogo com a v2 completa, quando a API é consultada, então devolve a versão, a data de vigência, os três textos e os resumos de 5 a 7 tópicos.
- **AC1.1.2** Dada a política v2, quando um teste procura as seções exigidas, então encontra cada uma:
  - dados guardados e finalidade;
  - base legal;
  - retenção;
  - compartilhamento;
  - direitos e canal;
  - encarregado;
  - local de armazenamento e transferência internacional;
  - "não usamos rastreamento".
- **AC1.1.3** Dado qualquer texto da v2, quando um teste procura os termos da lista versionada de promessas proibidas (`apps/web/src/content/prohibited-terms.json`, a mesma usada nas telas), então não encontra nenhum, e o aviso de simulação (PAPER) está presente. A revisão jurídica registrada (US1.3) cobre o que a lista não pega.

### US1.2 — Configurar os campos de implantação (Must)
Como **Administrador**, quero configurar o local de armazenamento, a transferência internacional e o e-mail do encarregado fora do código, para publicar a política certa em cada ambiente. (FR1.3)

- **AC1.2.1** Dada a configuração com os três campos, quando a política é exibida, então mostra os valores configurados.
- **AC1.2.2** Dada a configuração sem algum campo, quando a política é exibida, então mostra "a definir" e a versão fica marcada como não publicável.

### US1.3 — Ativar a versão só com revisão jurídica e campos preenchidos (Must)
Como **Administrador**, quero que uma versão só fique vigente com a revisão jurídica registrada e os campos de implantação preenchidos, para nunca publicar um texto não revisado. (FR1.4)

- **AC1.3.1** Dada uma versão com data e responsável da revisão e os campos preenchidos, quando a API sobe, então ela é a vigente.
- **AC1.3.2** Dada uma versão sem os dados de revisão, quando a API sobe, então a versão anterior continua vigente e um aviso vai para o log.
- **AC1.3.3** Dada uma versão sem os campos de implantação, quando a API sobe, então ela não é ativada, a versão anterior continua vigente e um aviso vai para o log.

### US1.4 — Confiar que a política descreve o sistema (Must)
Como **Administrador**, quero que a tabela de retenção publicada seja conferida contra os expurgos reais a cada mudança, para que a política nunca diga algo que o sistema não faz. (FR3.4)

- **AC1.4.1** Dada a tabela de retenção da política, quando a suíte de testes roda, então cada linha tem um expurgo ou uma regra de guarda com o prazo declarado.
- **AC1.4.2** Dado um prazo alterado no código sem mudar a política, quando a suíte roda, então falha e aponta a linha divergente.

## US2 — Páginas e acessos

### US2.1 — Ler os documentos sem login (Must)
Como **Visitante**, quero abrir `/termos` e `/privacidade` sem login, para entender as regras antes de entrar. (FR2.1, NFR3, NFR4)

- **AC2.1.1** Dado um visitante sem sessão, quando abre `/termos` ou `/privacidade` em qualquer um dos dois apps, então vê a versão, a vigência, o resumo, o índice e o aviso de simulação.
- **AC2.1.2** Dado um usuário só de teclado, quando abre a página, então o primeiro foco é "Pular para o conteúdo", o foco é visível e os títulos seguem h1 a h3.
- **AC2.1.3** Dados 20 acessos simultâneos, quando as páginas carregam, então o p95 fica em até 2 s.
- **AC2.1.4** Dada a API fora do ar, quando a página é aberta, então mostra um estado de erro em texto, com "tentar de novo", e nunca uma página vazia.

### US2.2 — Ver os links e a versão aceita no rodapé (Must)
Como **Convidado**, quero os links dos documentos e a versão que aceitei no rodapé de todas as telas, para consultar quando quiser. (FR2.2)

- **AC2.2.1** Dada qualquer tela dos dois apps, quando ela é exibida, então o rodapé tem os links "Termos de uso" e "Privacidade".
- **AC2.2.2** Dado um convidado com login, quando vê o rodapé, então aparece a versão aceita; sem login, não aparece.

### US2.3 — Aceitar os termos no convite (Must)
Como **Convidado**, quero ler o resumo e aceitar os termos, a política e o aviso de risco no convite, para entrar sabendo das regras. (FR2.3, NFR4)

- **AC2.3.1** Dado o convite aberto, quando nenhuma ou só uma das caixas está marcada, então o botão de conectar fica desabilitado, com o motivo em texto.
- **AC2.3.2** Dadas as duas caixas marcadas, quando o convidado conecta a carteira, então a conta nasce com o aceite da versão vigente.
- **AC2.3.3** Dado o convite, quando o convidado abre os links, então vê as páginas completas e volta ao convite sem perder as marcações.
- **AC2.3.4** Dados os documentos ainda carregando ou com falha ao carregar, quando o convite é exibido, então aparece "carregando" ou um erro em texto, e as caixas ficam desabilitadas.
- **AC2.3.5** Dado um usuário só de teclado, quando percorre o convite, então alcança as caixas, os links e o botão em ordem, com foco visível.

### US2.4 — Ir da política para a Conta para fazer um pedido (Must)
Como **Convidado**, quero que o botão "Fazer um pedido" da política me leve à Conta, para exercer meus direitos. (FR2.4)

- **AC2.4.1** Dado um convidado com login, quando clica em "Fazer um pedido", então vai para a Conta.

### US2.5 — Saber como pedir sem conseguir entrar (Must)
Como **Visitante**, quero que a política me diga como fazer um pedido sem login, para não ficar sem canal. (FR2.4)

- **AC2.5.1** Dado um visitante sem login, quando clica em "Fazer um pedido", então vai para a entrada, e a página informa o e-mail do encarregado para quem não consegue entrar (conta anonimizada, carteira perdida).

### US2.6 — Consultar versões anteriores (Could)
Como **Convidado**, quero ver as versões anteriores com as datas, para saber o que mudou. (FR8.1)

- **AC2.6.1** Dadas duas versões publicadas, quando abro a página, então vejo a lista com as datas de vigência e o link para o texto de cada uma.

## US3 — Retenção e expurgos

### US3.1 — Apagar desafios e sessões vencidos (Must)
Como **Administrador**, quero que desafios de login e sessões vencidos saiam sozinhos, para não guardar dado pessoal além do prazo. (FR3.1, FR3.2, NFR7)

- **AC3.1.1** Dado um desafio expirado há 24 h ou mais, quando o expurgo diário roda, então o desafio é apagado, e um mais recente fica.
- **AC3.1.2** Dada uma sessão encerrada ou revogada há 30 dias ou mais, quando o expurgo roda, então a sessão e seus digests são apagados; uma sessão ativa nunca é apagada.
- **AC3.1.3** Dado um expurgo que falha ou atrasa mais de 48 h, quando a saúde é consultada, então aparece `DEGRADED`.

### US3.2 — Registrar a auditoria sem a carteira completa (Must)
Como **Convidado**, quero que a trilha de auditoria não guarde minha carteira completa, para reduzir o que fica exposto. (FR3.3, NFR6)

- **AC3.2.1** Dado um login ou uma criação de conta, quando o evento é gravado, então `audit_event` e `audit_outbox` têm só o endereço mascarado e o código HMAC, nunca o endereço completo.
- **AC3.2.2** Dados dois eventos da mesma carteira, quando comparados, então o código HMAC é o mesmo.
- **AC3.2.3** Dado o checklist de abertura do beta, quando revisado, então inclui o descarte dos dados de teste.

## US4 — Novo aceite

### US4.1 — Ser levado ao novo aceite quando a versão muda (Must)
Como **Convidado**, quero ser avisado e levado ao novo aceite quando os termos mudam, para continuar usando sabendo das regras novas. (FR4.1, FR4.3, FR4.4, NFR4, NFR9)

- **AC4.1.1** Dada uma versão vigente diferente da aceita, quando o convidado chama uma rota bloqueada, então recebe `403` com `TERMS_REACCEPTANCE_REQUIRED` (teste obrigatório do time).
- **AC4.1.2** Dada essa situação, quando o convidado abre os documentos, a Conta, os pedidos ou sai, então essas rotas funcionam.
- **AC4.1.3** Dado o `403`, quando o app recebe a resposta, então mostra a tela de novo aceite com o resumo do que mudou e os links. A tela é navegável só por teclado e tem foco visível.
- **AC4.1.4** Dado um bot PAPER ativo, quando o convidado ainda não aceitou, então o bot continua rodando, mas criar, alterar, iniciar ou retomar bot é bloqueado.

### US4.2 — Aceitar a versão nova (Must)
Como **Convidado**, quero aceitar a versão nova uma vez, para voltar a usar tudo. (FR4.2, NFR3)

- **AC4.2.1** Dada a versão vigente, quando o convidado aceita com uma chave de idempotência, então fica um único registro novo de aceite, e os anteriores não mudam.
- **AC4.2.2** Dada uma versão enviada que não é a vigente, quando o convidado aceita, então recebe `409` com `TERMS_VERSION_OUTDATED` e a versão vigente, e o app recarrega os documentos.
- **AC4.2.3** Dada uma versão já aceita, quando o aceite se repete, então o resultado é o mesmo, sem nova gravação.
- **AC4.2.4** Dados 20 convidados aceitando ao mesmo tempo, quando medido, então o p95 da rota de aceite fica em até 500 ms.

**Dados de teste:** semear a v1 aceita e a v2 vigente (não depende de US4.1).

## US5 — Pedidos de titular

### US5.1 — Fazer um pedido pela Conta (Must)
Como **Convidado**, quero pedir acesso, correção, exclusão ou outra coisa sobre meus dados na Conta, para exercer meus direitos sem e-mail. (FR5.1, NFR1, NFR3, NFR4)

- **AC5.1.1** Dado um convidado na Conta, quando envia um pedido de um dos quatro tipos com até 1000 caracteres, então o pedido é criado como Recebido, com prazo de 15 dias corridos.
- **AC5.1.2** Dado um pedido aberto do mesmo tipo, quando tenta criar outro, então recebe `409`.
- **AC5.1.3** Dado um texto acima de 1000 caracteres ou um tipo fora dos quatro, quando envia, então recebe `400` com `VALIDATION_FAILED`, e nada é gravado.
- **AC5.1.4** Dado qualquer pedido, quando os logs e os eventos são verificados, então o texto do pedido não aparece.
- **AC5.1.5** Dados 20 convidados criando pedidos ao mesmo tempo, quando medido, então o p95 fica em até 500 ms.
- **AC5.1.6** Dado um usuário só de teclado, quando preenche o formulário, então alcança tipo, texto e envio em ordem, com rótulos visíveis e foco visível.

### US5.2 — Acompanhar meus pedidos e ver a resposta (Must)
Como **Convidado**, quero ver meus pedidos com situação, prazo, resposta e motivo da recusa, para saber se fui atendido. (FR5.2, NFR2)

- **AC5.2.1** Dados pedidos meus, quando abro a Conta, então vejo tipo, data, situação e prazo de cada um.
- **AC5.2.2** Dado um pedido concluído ou recusado, quando abro o pedido, então vejo a resposta escrita e, se houver, o motivo da recusa.
- **AC5.2.3** Dado um pedido de outra pessoa ou de outro tenant, quando tento abri-lo, então recebo `404` e nunca o dado.

**Dados de teste:** semear pedidos nos quatro estados (não depende de US5.1 nem de US5.3).

### US5.3 — Atender um pedido no app do administrador (Must)
Como **Administrador**, quero ver os pedidos com prazo, responder e mudar o estado, para cumprir os 15 dias. (FR5.3, FR5.4, FR5.5, NFR4)

- **AC5.3.1** Dado o app do administrador, quando abro os pedidos, então vejo a lista com tipo, origem (Conta ou e-mail), conta, recebimento, prazo e situação, e o detalhe de cada um.
- **AC5.3.2** Dado um pedido, quando mudo o estado, então só estas transições são aceitas:
  - Recebido → Em andamento;
  - Recebido → Aguardando verificação, só para pedido por e-mail;
  - Aguardando verificação → Em andamento;
  - Aguardando verificação → Recusado;
  - Em andamento → Concluído;
  - Em andamento → Recusado, com motivo obrigatório.
- **AC5.3.3** Dado um pedido Concluído ou Recusado, quando tento mudar o estado, então recebo `409` e o estado não muda.
- **AC5.3.4** Dada cada criação e cada mudança de estado, quando a auditoria é consultada, então há um evento sem o texto do pedido.
- **AC5.3.5** Dado um usuário sem o papel `ADMIN`, quando chama as rotas administrativas, então recebe `403`.
- **AC5.3.6** Dado um usuário só de teclado, quando percorre a lista e o detalhe, então alcança filtros, pedidos, resposta e ações em ordem, com foco visível.

**Dados de teste:** semear pedidos com origem Conta e e-mail.

### US5.4 — Liberar o resumo de Acesso (Must)
Como **Administrador**, quero que o sistema monte o resumo dos dados da pessoa num pedido de Acesso, para responder sem montar à mão. (FR5.2a)

- **AC5.4.1** Dada uma conta com dados conhecidos, quando o resumo é montado, então traz a carteira mascarada, o provedor, as datas da conta e do último login, as versões aceitas, a quantidade de bots, de ordens e de feedbacks, e os pedidos.
- **AC5.4.2** Dado o resumo, quando verificado, então não tem o endereço completo nem dado de outra pessoa.
- **AC5.4.3** Dado o resumo conferido, quando o administrador conclui o pedido, então a pessoa vê o resumo junto da resposta.
- **AC5.4.4** Dado um pedido de Acesso com origem e-mail sem identidade confirmada (US5.6), quando o administrador tenta liberar o resumo, então a API recusa com `409` e `IDENTITY_NOT_VERIFIED`, e nada é liberado.
- **AC5.4.5** Dado um pedido de Acesso com origem Conta (pessoa autenticada), quando o administrador libera o resumo, então não é exigida a verificação por assinatura.

**Dados de teste:** semear uma conta com bots, ordens, feedback e aceites conhecidos.

### US5.5 — Responder um pedido de Correção (Must)
Como **Administrador**, quero uma resposta modelo para a Correção, para orientar a pessoa de forma consistente. (FR5.2b)

- **AC5.5.1** Dado um pedido de Correção, quando abro a resposta, então ela indica que o nome dos bots a pessoa muda sozinha e que a carteira, por ser a identidade da conta, não se corrige, com a exclusão como alternativa.

### US5.6 — Registrar e verificar um pedido recebido por e-mail (Must)
Como **Administrador**, quero registrar um pedido recebido pelo e-mail do encarregado e confirmar a identidade antes de agir, para atender quem não consegue entrar sem expor dados a terceiros. (FR5.2c; user-stories-questions.md Q4)

- **AC5.6.1** Dado um pedido por e-mail, quando registro no app do administrador, então ele ganha origem "e-mail", o mesmo prazo, a mesma auditoria e o mesmo expurgo, ligado à conta quando ela existir, e o contato de resposta (o e-mail informado) fica guardado só no pedido.
- **AC5.6.2** Dado um pedido de Acesso ou de Exclusão por e-mail ligado a uma conta, quando peço a verificação, então o pedido vai para "Aguardando verificação", e o sistema gera uma mensagem de verificação única para aquele pedido, com o domínio, o identificador do pedido, a finalidade "verificação de pedido de titular" e a validade de 24 h.
- **AC5.6.3** Dada uma assinatura válida, feita com a carteira da conta sobre essa mensagem dentro das 24 h, quando confiro, então a identidade fica confirmada e o pedido vai para "Em andamento".
- **AC5.6.4** Dada uma assinatura inválida ou de outra carteira, quando confiro, então a identidade não é confirmada e o pedido continua aguardando até vencer.
- **AC5.6.5** Dadas 24 h sem assinatura válida, quando o prazo da mensagem passa, então o pedido é recusado com o motivo "identidade não confirmada".
- **AC5.6.6** Dada uma mensagem de verificação já usada para confirmar, quando a mesma assinatura é enviada de novo, então é rejeitada (uso único).
- **AC5.6.7** Dada a assinatura de verificação, quando enviada à rota de login, então o login é rejeitado. Dada uma assinatura de login, quando enviada como verificação de pedido, então é rejeitada. Nenhuma das duas autoriza operar: as rotas de trading continuam exigindo sessão e autorização próprias.
- **AC5.6.8** Dada qualquer verificação, quando os logs e os eventos são verificados, então nem a mensagem nem a assinatura aparecem.
- **AC5.6.9** Dada uma carteira perdida ou uma conta já anonimizada, quando o pedido é recusado, então a resposta ao contato informado orienta a pessoa sem expor dado pessoal.
- **AC5.6.10** Dado um pedido por e-mail concluído ou recusado, quando o administrador registra a resposta, então o app mostra o texto pronto para ele enviar ao contato informado, e o pedido registra a data do envio.

### US5.7 — Saber quando um prazo vence (Must)
Como **Administrador**, quero que um pedido vencido gere alerta de sistema, para não perder o prazo legal em silêncio. (FR5.7, NFR8)

- **AC5.7.1** Dado um pedido aberto que passa do prazo, quando o tempo avança, então há um único evento `DATA_REQUEST_OVERDUE`, a métrica de vencidos sobe e a saúde dos pedidos fica `DEGRADED`.
- **AC5.7.2** Dado o pedido vencido concluído, quando a saúde é consultada, então volta ao normal.

**Dados de teste:** semear um pedido Recebido com 15 dias e relógio injetável.

### US5.8 — Apagar pedidos antigos (Must)
Como **Administrador**, quero que pedidos concluídos ou recusados há 12 meses saiam sozinhos, para cumprir a retenção declarada. (FR5.6)

- **AC5.8.1** Dado um pedido concluído há 12 meses ou mais, quando o expurgo roda, então é apagado (com o contato de resposta); um mais recente fica.

### US5.9 — Filtrar e ver alertas de prazo na lista (Should)
Como **Administrador**, quero filtrar os pedidos por situação e ver os que vencem em breve, para priorizar. (FR7.1)

- **AC5.9.1** Dada a lista, quando filtro por situação, então só aparecem os pedidos dessa situação.
- **AC5.9.2** Dado um pedido a 3 dias ou menos do prazo, ou vencido, quando vejo a lista, então ele tem ícone e texto de alerta.

## US6 — Anonimização

### US6.1 — Acionar a anonimização a partir de um pedido de Exclusão (Must)
Como **Administrador**, quero acionar a anonimização só a partir de um pedido de Exclusão válido e com dupla confirmação, para nunca anonimizar a conta errada. (FR6.1, FR6.4)

- **AC6.1.1** Dado um pedido de Exclusão em andamento, quando aciono "Anonimizar conta" sem digitar `ANONIMIZAR`, então nada é enviado.
- **AC6.1.2** Dado um pedido de Exclusão com origem e-mail sem identidade confirmada (US5.6), quando aciono a anonimização, então a API recusa com `409` e `IDENTITY_NOT_VERIFIED`, e a conta não muda.
- **AC6.1.3** Dado um pedido de Exclusão com origem Conta, quando aciono a anonimização, então não é exigida a verificação por assinatura.
- **AC6.1.4** Dado um pedido que não é de Exclusão, ou que já está Concluído ou Recusado, quando aciono a anonimização, então a API recusa com `409`, e a conta não muda.
- **AC6.1.5** Dada a anonimização concluída, quando a auditoria é consultada, então há o evento `ACCOUNT_ANONYMIZED` sem dado pessoal, e o pedido de Exclusão está Concluído.

**Dados de teste:** semear pedidos de Exclusão com origem Conta e e-mail, com e sem identidade confirmada.

### US6.2 — Apagar o que identifica a pessoa sem tocar no ledger (Must)
Como **Convidado** que pediu exclusão, quero que tudo o que me identifica saia do sistema e que o histórico simulado fique sem vínculo comigo, para que a exclusão seja real. (FR6.2, NFR5, NFR9)

- **AC6.2.1** Dada a confirmação, quando a anonimização roda, então, numa só transação:
  - a conta fica `ANONYMIZED`;
  - a carteira e o provedor são apagados;
  - as sessões são revogadas;
  - o feedback é apagado;
  - os bots são renomeados para "Bot anonimizado" e parados.
- **AC6.2.2** Dada a conta anonimizada, quando todas as tabelas, inclusive a trilha, e os logs capturados são verificados, então o endereço completo não aparece em nenhum lugar (teste obrigatório do time).
- **AC6.2.3** Dado o ledger da conta, quando a reconciliação roda depois da anonimização, então dá 0 divergência, e nenhuma linha do ledger ou dos fills foi alterada ou apagada.
- **AC6.2.4** Dada uma falha no meio da anonimização (erro simulado em qualquer passo), quando a transação termina, então nada mudou: a conta continua ativa, a carteira continua lá e o pedido não é concluído.
- **AC6.2.5** Dada uma conta já `ANONYMIZED`, quando a anonimização é pedida de novo, então a resposta é a mesma da primeira vez, sem nova mudança nem novo evento.
- **AC6.2.6** Dado um login ou um ciclo de bot da mesma conta acontecendo ao mesmo tempo, quando a anonimização roda, então ou eles terminam antes e a anonimização vem depois, ou falham com a conta já anonimizada. Nenhuma sessão nova nem ordem nova fica ligada à conta depois da anonimização.

**Dados de teste:** semear uma conta com carteira, sessões, feedback, bots ativos, ordens e ledger conhecidos (não depende de US6.1).

### US6.3 — Não entrar com uma conta anonimizada (Must)
Como **Convidado** cuja conta foi anonimizada, quero que a conta antiga não reabra, e que eu só volte com convite novo, para que a exclusão seja definitiva. (FR6.3)

- **AC6.3.1** Dada uma conta `ANONYMIZED`, quando a mesma carteira tenta entrar, então a conta antiga não é reaberta.
- **AC6.3.2** Dado um convite novo, quando a mesma carteira entra, então nasce uma conta nova, sem histórico da antiga.

## Dependências entre histórias

- US2.1, US2.2 e US2.3 dependem de US1.1. US1.3 depende de US1.2. US1.4 depende de US3.1.
- US4.1 e US4.2 dependem de US1.1 e US1.3.
- US5.2 a US5.9 usam o pedido de US5.1, mas cada uma tem dados de teste próprios.
- US6.1 usa US5.3 e US5.6; US6.2 é testável sozinha com dados semeados; US6.2 depende de US3.2 para a trilha sem resíduo.

## Prioridade

Must: todas, menos US5.9 (Should, FR7) e US2.6 (Could, FR8).
