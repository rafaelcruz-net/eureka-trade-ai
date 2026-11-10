# Perguntas do Functional Design — U4 `u4-data-requests`

Conversa: português (Brasil). Todas as respostas são as recomendadas (A), pelo pedido do responsável do projeto em 2026-11-10: "Siga sempre as recomendações sem me pergunta".

A U4 cobre US5.1 a US5.9 (FR5.1 a FR5.7, FR7.1) e os contratos P1, P2 (sem a rota de anonimização, que é da U5), J1 (resumo e conferência da carteira), J2, J3 e J4 (contagens).

## Q1 — Guarda do texto do pedido e do contato

A. O texto do pedido (até 1000 caracteres), a resposta e o contato de e-mail ficam em colunas próprias da tabela de pedidos do módulo Privacy, no PostgreSQL, como os demais dados da conta. Eles nunca vão para log, evento de auditoria, métrica ou mensagem de erro, e saem com o pedido no expurgo de 12 meses. (recomendado)
B. Cifrar o texto na aplicação.
X. Other (please specify)

[Answer]: A

## Q2 — Um pedido aberto por tipo

A. Um índice único parcial em `(tenant_id, user_id, type)` enquanto o estado é aberto (`RECEIVED`, `AWAITING_VERIFICATION` ou `IN_PROGRESS`). A segunda criação recebe `409 DATA_REQUEST_ALREADY_OPEN`. Pedidos por e-mail sem conta não entram nessa regra. (recomendado)
B. Só a checagem na aplicação.
X. Other (please specify)

[Answer]: A

## Q3 — Mensagem de verificação (US5.6)

A. A mensagem é gerada pelo Privacy e guardada no pedido (não é segredo). Ela tem uma primeira linha fixa que a separa do login (`MemeTrade - verificação de pedido de titular`), o domínio, o identificador do pedido, a finalidade e a validade de 24 h. A conferência usa a J1 `verifyAccountSignature`, que confere a assinatura Ed25519 sobre essa mensagem com a carteira da conta e recusa qualquer mensagem de login. A assinatura nunca é guardada nem registrada; o pedido guarda só que a verificação foi usada (uso único). (recomendado)
B. Outro formato.
X. Other (please specify)

[Answer]: A

## Q4 — Tarefas de tempo

A. Um job do Privacy, a cada 15 minutos, com ShedLock, só no processo da API:
- recusa com "identidade não confirmada" as verificações vencidas (24 h);
- marca os pedidos vencidos e publica `DATA_REQUEST_OVERDUE` uma única vez por pedido, guardando a data do aviso.

O expurgo diário dos pedidos encerrados há 12 meses (`dataSubjectRequestPurge`, às 04:00 UTC) segue o padrão dos expurgos da U2: declaração do prazo, tabela de execuções e recuperação na subida. Ele sai da lista de pendências do catálogo de retenção. A saúde `dataRequests` fica `DEGRADED` enquanto houver pedido vencido aberto, e `dataSubjectRequestPurge` fica `DEGRADED` com 48 h sem sucesso. (recomendado)
B. Um job por tarefa.
X. Other (please specify)

[Answer]: A

## Q5 — Resumo de Acesso (US5.4)

A. O resumo é montado na hora pelas interfaces públicas:
- J1 `personSummary`: carteira mascarada, provedor, datas e versões aceitas;
- J2, J4 e J3: contagens de bots, de ordens PAPER e de feedbacks;
- os pedidos da própria pessoa.

O administrador vê a prévia. Ao concluir com `releaseAccessSummary=true`, o resumo é gravado no pedido como foto daquele momento, e a pessoa vê essa foto. Nunca entra endereço completo nem dado de outra pessoa. (recomendado)
B. Montar sempre na hora, também para a pessoa.
X. Other (please specify)

[Answer]: A

## Q6 — Respostas modelo (US5.5, US5.6)

A. O app do administrador traz textos prontos em pt-BR, que preenchem o campo de resposta e podem ser editados antes de enviar:
- **Correção:** o nome dos bots a pessoa muda sozinha; a carteira não se corrige, e a alternativa é a exclusão;
- **recusa por "identidade não confirmada" ou carteira perdida:** orienta sem expor dado pessoal;
- **resposta pronta para o e-mail do contato**, com o botão "Marcar como enviada".

(recomendado)
B. Sem modelos.
X. Other (please specify)

[Answer]: A

## Q7 — Telas

A. Telas:
- **Convidado (T5):** a seção "Meus dados (LGPD)" na Conta, com o formulário de pedido (tipo e texto, contador de caracteres) e a lista dos pedidos com tipo, data, situação e prazo; o detalhe mostra a resposta, o motivo e o resumo de Acesso.
- **Administrador:**
  - T6: a lista, com filtro por situação e alerta com ícone e texto para os pedidos a 3 dias ou menos do prazo, ou vencidos;
  - T6a: o detalhe, com resposta, ações de estado e prévia do resumo;
  - T6b: o registro de pedido por e-mail;
  - T6c: o painel de verificação, com a mensagem para copiar e o campo da assinatura.

(recomendado)
B. Outra divisão.
X. Other (please specify)

[Answer]: A

## Q8 — Trava de novo aceite

A. O prefixo `/api/v1/privacy/` entra na lista liberada da trava da U3, com o teste de rotas correspondente. Assim a pessoa desatualizada ainda faz pedidos. (recomendado)
B. Não liberar.
X. Other (please specify)

[Answer]: A
