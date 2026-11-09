# Perguntas da Captura da Intenção

## Sources

- [desc] Initial description: "Termos de uso e LGPD como requisitos do beta PAPER: termos gerais de uso definitivos (v2) e política de privacidade com retenção de dados, como em qualquer site. Páginas públicas /termos e /privacidade, links no rodapé dos apps e no convite, novo aceite quando a versão muda, canal para pedidos do titular (acesso, correção, exclusão) compatível com o ledger append-only, e tabela de retenção alinhada aos expurgos existentes. Parecer jurídico (C-09) aprovado em 2026-11-09."
- [scope] Workflow-selected scope: `feature`.
- [memory:M1] `aidlc/spaces/default/memory/project.md#Mandated`: "ALWAYS registrar o ledger como append-only, corrigindo erros apenas com lançamentos compensatórios. (origem: PRD §13) (affirmed 2026-10-26)"
- [memory:M2] `aidlc/spaces/default/memory/project.md#Forbidden`: "NEVER prometer lucro ou garantia de detecção de fraude em telas ou comunicações. (origem: C-16) (affirmed 2026-10-26)"

## Q1. Qual problema de negócio estamos resolvendo?

A) O beta PAPER não pode receber convidados sem termos de uso definitivos e sem uma política de privacidade que cumpra a LGPD. Hoje os termos são uma versão provisória (v1) e não existe política de privacidade publicada (recomendado)
B) Só trocar o texto provisório dos termos pelo definitivo
C) Not yet defined
X) Other (please specify)

[Answer]: A

## Q2. Quem é o cliente e qual dor ele sente?

A) Os convidados do beta (titulares dos dados): precisam saber, antes de entrar, o que podem fazer, quais dados guardamos, por quanto tempo e como pedir acesso, correção ou exclusão. O responsável do projeto (controlador dos dados) precisa de regras claras para cumprir a LGPD e responder pedidos (recomendado)
B) Só os convidados
C) Not identified
X) Other (please specify)

[Answer]: A

## Q3. Como medimos o sucesso?

A) Quatro critérios:
- 100% das contas ativas aceitaram a versão vigente dos termos e da política antes de usar o produto;
- 100% dos pedidos de titular respondidos em até 15 dias;
- cada dado listado na tabela de retenção tem um expurgo ou uma regra de guarda testada;
- as duas páginas são acessíveis sem login, por teclado e com contraste legível (recomendado)

B) Só a publicação das duas páginas
C) Not yet defined
X) Other (please specify)

[Answer]: A

## Q4. O que dispara esta iniciativa?

A) Regulação: o parecer jurídico (C-09) foi aprovado em 2026-11-09 e exige termos e política de privacidade antes de convidar pessoas (recomendado)
B) Pedido de convidados
C) Not identified
X) Other (please specify)

[Answer]: A

## Q5. Quem são os interessados e o que cada um quer?

A) Três:
- o responsável do projeto: controlador dos dados e, no beta, também encarregado; quer cumprir a LGPD com pouco trabalho manual;
- os convidados: querem clareza e controle sobre os próprios dados;
- a assessoria jurídica que emitiu o parecer: quer textos fiéis ao parecer.

(recomendado)

B) Só o responsável do projeto e os convidados
C) Not identified
X) Other (please specify)

[Answer]: A

## Q6. Quem decide escopo e prioridade, e quem influencia?

A) O responsável do projeto decide. A assessoria jurídica influencia o conteúdo dos textos (recomendado)
B) A assessoria jurídica decide o conteúdo dos textos
C) Not yet defined
X) Other (please specify)

[Answer]: A

## Q7. Há requisitos de comunicação ou de relatório?

A) Sim. Quando os termos ou a política mudam, o convidado vê a nova versão e aceita de novo antes de continuar; o mecanismo de aceite por versão já existe. Os pedidos de titular ficam registrados, com data de recebimento e de resposta, para prestar contas (recomendado)
B) None
X) Other (please specify)

[Answer]: A

## Q8. O que esta primeira versão deixa de fora?

O escopo do trabalho já foi escolhido (`feature`). Pergunto só o que fica fora desta primeira versão:

A) Fica de fora:
- banner de cookies de terceiros: o produto não usa rastreamento, só armazenamento local necessário;
- exportação automática dos dados pelo próprio titular: o pedido é atendido pelo responsável;
- textos em outros idiomas;
- termos do modo REAL, que continua bloqueado;
- encarregado externo contratado.

(recomendado)

B) None (tudo entra nesta versão)
C) Not yet defined
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Problema: o beta PAPER não recebe convidados sem termos de uso definitivos e sem política de privacidade conforme a LGPD; hoje os termos são a v1 provisória e não há política publicada [Q1].
- Cliente: os convidados (titulares dos dados) e o responsável do projeto (controlador) [Q2].
- Sucesso: 100% das contas ativas com aceite da versão vigente; pedidos de titular respondidos em até 15 dias; cada dado da tabela de retenção com expurgo ou regra de guarda testada; páginas acessíveis sem login, por teclado e com contraste legível [Q3].
- Gatilho: o parecer jurídico (C-09), aprovado em 2026-11-09 [Q4].
- Interessados: responsável do projeto (controlador e encarregado no beta), convidados e assessoria jurídica [Q5]; o responsável decide e a assessoria influencia os textos [Q6].
- Comunicação: novo aceite a cada mudança de versão; pedidos de titular registrados com datas [Q7].
- Fora desta versão: banner de cookies de terceiros, exportação automática pelo titular, outros idiomas, termos do modo REAL e encarregado externo [Q8].

- Looks correct
- Request changes

[Answer]: Looks correct
