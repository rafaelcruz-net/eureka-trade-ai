# Requisitos — Termos de uso e LGPD

Conversa: português (Brasil). Fontes:
- `ideation/intent-capture/intent-statement.md` (IC);
- `ideation/scope-definition/scope-document.md` (SD, capacidades C1 a C8);
- a base de conhecimento do código, `aidlc/spaces/default/codekb/eureka-trader-ai/` (`business-overview.md`, `architecture.md`, `code-structure.md`) (CK);
- `inception/practices-discovery/team-practices.md` (TP);
- as respostas de `requirements-analysis-questions.md` (Q1 a Q8).

## Intent analysis

O beta PAPER só pode receber convidados com termos de uso definitivos e uma política de privacidade fiel ao que o sistema faz, cumprindo a LGPD (IC). Isso pede:
- documentos versionados e públicos, com novo aceite a cada versão;
- uma tabela de retenção que corresponda aos expurgos reais;
- um canal para os pedidos do titular, com prazo de 15 dias;
- exclusão por anonimização, sem quebrar o ledger, que não aceita edição (SD).

A varredura do código mostrou dados sem expurgo, a carteira completa na trilha de auditoria, a conta impossível de apagar e a falta de novo aceite (CK). Os requisitos abaixo tratam cada um desses pontos.

## Functional requirements

### FR1 — Documentos versionados (C1; IC, SD)

- **FR1.1** O catálogo de termos passa a ter a versão `v2`, com três textos: termos de uso, política de privacidade e aviso de risco. Cada versão tem identificador, data de vigência e um resumo de 5 a 7 tópicos de cada documento (C1; Q8).
  - **Critério:** a API devolve a versão vigente com os três textos, a data e os resumos.
- **FR1.2** O texto dos documentos descreve o que o sistema faz de verdade:
  - dados guardados, finalidade e base legal indicada no parecer;
  - retenção, conforme FR3;
  - compartilhamento;
  - direitos e canal;
  - encarregado;
  - local de armazenamento e transferência internacional;
  - aviso de que tudo é simulado (PAPER);
  - nenhuma promessa de lucro;
  - "não usamos rastreamento" (Q7, Q8).
  - **Critério:** um teste confere que cada dado da tabela de retenção da política existe no código com o prazo declarado (ver FR3.4).
- **FR1.3** Os campos de implantação (local de armazenamento, transferência internacional, e-mail do encarregado) vêm da configuração, nunca do código (C6; Q8).
  - **Critério:** sem a configuração, a API e as páginas mostram "a definir" e a versão fica marcada como não publicável. Um teste cobre o valor configurado e o ausente.
- **FR1.4** A versão do beta só é publicada com a revisão jurídica registrada e os três campos de FR1.3 preenchidos (Q8). A revisão fica registrada no arquivo da própria versão no catálogo, com a data da revisão e quem revisou. Sem esses dois campos, ou sem os campos de FR1.3, a versão não é ativada: a API continua servindo a versão anterior e registra um aviso no log (Q13).
  - **Critério:** teste do catálogo com três versões: uma completa (ativa), uma sem os campos de revisão (não ativa, a anterior continua vigente, aviso no log) e uma sem os campos de FR1.3 (não ativa).

### FR2 — Páginas públicas e pontos de acesso (C2; SD, Q7)

- **FR2.1** Páginas `/termos` e `/privacidade` sem login nos dois apps (convidado e administrador), com versão, vigência, resumo, índice e seções (C2).
  - **Critério:** spec de ponta a ponta abre as duas páginas sem sessão e encontra versão, resumo e o aviso de simulação.
- **FR2.2** Rodapé com os links para as duas páginas em todas as telas dos dois apps e, com login, a versão aceita (Q7).
  - **Critério:** teste de componente confere os links e a versão aceita; sem login, a versão não aparece.
- **FR2.3** A tela do convite mostra o resumo, links para as páginas completas e duas caixas: "Li e aceito os Termos de uso e a Política de privacidade" e "Entendo o aviso de risco". O botão de conectar só fica habilitado com as duas marcadas (Q7).
  - **Critério:** teste de componente e o smoke U3 atualizado.
- **FR2.4** A página de privacidade tem o botão "Fazer um pedido". Com login, leva à Conta. Sem login, leva à entrada (não há formulário público). A página informa que quem não consegue entrar (conta anonimizada, carteira perdida) faz o pedido pelo e-mail do encarregado (Q10).
  - **Critério:** teste de componente para os dois casos e para o texto do e-mail do encarregado.

### FR3 — Retenção alinhada aos expurgos (C3; Q1, Q2, Q4, Q6)

- **FR3.1** Novo expurgo de desafios de login 24 horas depois de expirarem (Q1).
  - **Critério:** teste com relógio injetável; o desafio expirado há 24 h ou mais some, e o mais novo fica.
- **FR3.2** Novo expurgo de sessões e de digests de renovação 30 dias depois do fim ou da revogação (Q1).
  - **Critério:** teste com relógio injetável; uma sessão ativa nunca é apagada.
- **FR3.3** Os eventos de auditoria novos guardam só o endereço mascarado e um código de correlação HMAC-SHA-256 com chave secreta da configuração, nunca o endereço completo, inclusive no `audit_outbox`. A mudança entra antes da abertura do beta. Como ainda não existe convidado real, nenhum convidado terá evento com o endereço completo. Antes da abertura, os dados de desenvolvimento e teste são descartados (ambiente novo). A trilha continua sem nenhuma edição (Q2, Q9).
  - **Critério:** teste que cria conta e faz login e procura o endereço completo em `audit_event` e `audit_outbox`: não encontra. O HMAC da mesma carteira é igual entre eventos. O checklist de abertura do beta inclui o descarte dos dados de teste.
- **FR3.4** Tabela de retenção única, na política e no código, com cada dado e seu prazo. Prazos:
  - auditoria: 12 meses;
  - saída da auditoria: 7 dias após entrega;
  - feedback: 180 dias;
  - decisões HOLD/NO_TRADE: 30 dias;
  - avaliações aprovadas da barreira: 30 dias;
  - idempotência: 24 h;
  - desafios: 24 h após expirar;
  - sessões: 30 dias após o fim;
  - pedidos de titular: 12 meses após concluídos;
  - conta, carteira, bots e histórico PAPER: enquanto a conta existir, e sem vínculo depois da anonimização.

  (Q1, Q4, Q6; CK)
  - **Critério:** teste que lê a tabela da política e confere, para cada linha, a existência do expurgo ou da regra de guarda com o prazo declarado.

### FR4 — Novo aceite quando a versão muda (C1; Q5)

- **FR4.1** Com versão vigente diferente da última aceita pela pessoa, a API responde `403` em `ProblemDetail` com o código `TERMS_REACCEPTANCE_REQUIRED` a toda rota do convidado, menos: leitura dos documentos, aceite, Conta, pedidos de titular e sair (Q5).
  - **Critério:** teste de API para uma rota bloqueada e para cada rota liberada.
- **FR4.2** Rota de aceite da versão vigente, com chave de idempotência, que grava um novo registro de aceite (pessoa, versão, data) sem alterar os anteriores. O tenant vem da autenticação.
  - Se a versão enviada não é a vigente, a API responde `409` com o código `TERMS_VERSION_OUTDATED` e a versão vigente, e o app recarrega os documentos e pede o aceite de novo.
  - Aceitar de novo uma versão já aceita devolve o mesmo resultado, sem nova gravação (Q14).
  - **Critério:** teste de API para o aceite com a mesma chave (uma gravação), para a versão superada (`409`) e para o aceite repetido da mesma versão (sem nova gravação).
- **FR4.3** O app leva à tela de novo aceite ao receber `TERMS_REACCEPTANCE_REQUIRED`, com o resumo do que mudou e os links (Q5).
  - **Critério:** teste de componente e spec de ponta a ponta.
- **FR4.4** Os bots PAPER já ativos continuam rodando. Criar, alterar, iniciar ou retomar bot fica bloqueado até o aceite (Q5).
  - **Critério:** teste de API; o ciclo do worker de um bot ativo continua.

### FR5 — Pedidos de titular (C4; Q6)

- **FR5.1** Na Conta, a pessoa cria um pedido dos tipos Acesso, Correção, Exclusão ou Outro, com texto de até 1000 caracteres; só um aberto por tipo (Q6).
  - **Critério:** teste de API para criação, limite de texto e segundo pedido aberto do mesmo tipo (`409`).
- **FR5.2** A pessoa vê a lista dos próprios pedidos com tipo, data, situação e prazo. Quando o pedido é concluído ou recusado, vê também a resposta escrita e, se houver, o motivo da recusa. Nunca vê pedidos de outra pessoa (Q6, Q11; regra de tenant).
  - **Critério:** teste de isolamento (o pedido de outra pessoa ou de outro tenant devolve `404`) e teste de API e de componente da resposta e do motivo visíveis depois da conclusão.
- **FR5.2a** Pedido de Acesso: o sistema monta um resumo dos dados da pessoa, que o administrador confere e libera junto da resposta. O resumo traz:
  - carteira mascarada e provedor;
  - datas de criação da conta e do último login;
  - versões aceitas com as datas;
  - quantidade de bots, de ordens PAPER e de feedbacks enviados;
  - lista dos pedidos de titular.

  (Q11)
  - **Critério:** teste do montador com uma conta de dados conhecidos; o resumo nunca traz o endereço completo nem dado de outra pessoa.
- **FR5.2b** Pedido de Correção: a resposta indica o que a pessoa pode mudar sozinha (nome dos bots). A carteira não se corrige, porque é a identidade da conta; a alternativa indicada é a exclusão (Q11).
  - **Critério:** teste de componente da resposta modelo de Correção.
- **FR5.2c** Pedido recebido fora do app (e-mail do encarregado): o administrador registra o pedido no app do administrador, ligado à conta quando ela existir. Vale o mesmo prazo, a mesma auditoria e o mesmo expurgo (Q10).
  - **Critério:** teste de API da rota administrativa de registro.
- **FR5.3** Estados Recebido, Em andamento, Concluído e Recusado (este com motivo). O prazo é a data de recebimento mais 15 dias corridos (Q6).
  - **Critério:** teste das transições permitidas e proibidas, e do cálculo do prazo com relógio injetável.
- **FR5.4** No app do administrador: lista dos pedidos com prazo e situação, detalhe, campo de resposta e ações de mudar o estado (Q6).
  - **Critério:** teste de API das rotas administrativas (só `ADMIN`) e teste de componente.
- **FR5.5** Cada criação e cada mudança de estado gera evento de auditoria sem o texto do pedido (Q6).
  - **Critério:** teste de auditoria; o texto do pedido não aparece em evento nem em log.
- **FR5.6** Pedidos concluídos ou recusados são apagados 12 meses depois (Q6).
  - **Critério:** teste do expurgo com relógio injetável.
- **FR5.7** Um pedido aberto cujo prazo vence gera uma vez o evento de auditoria `DATA_REQUEST_OVERDUE`, entra na métrica de pedidos vencidos e deixa a saúde dos pedidos em `DEGRADED` enquanto houver vencido aberto (Q12).
  - **Critério:** teste com relógio injetável: um único evento por pedido, a métrica e a saúde mudam no vencimento e voltam ao concluir.

### FR6 — Exclusão por anonimização (C5; Q3)

- **FR6.1** Num pedido de Exclusão, o administrador aciona "Anonimizar conta" com dupla confirmação: digitar `ANONIMIZAR` (Q3; rascunhos).
  - **Critério:** teste de componente; sem o texto exato, a ação não é enviada.
- **FR6.2** A anonimização roda numa só transação auditada. Ela:
  - leva a conta para o estado novo `ANONYMIZED`;
  - apaga o endereço e o provedor da carteira;
  - revoga todas as sessões;
  - apaga o feedback da pessoa;
  - troca os nomes dos bots por "Bot anonimizado";
  - para os bots ativos.

  O ledger, as ordens, as recusas e os registros de aceite ficam, ligados só ao identificador interno (Q3).
  - **Critério:** teste de integração que, depois da anonimização, procura o endereço completo em todas as tabelas e nos logs capturados e não encontra. A trilha de auditoria entra na busca, porque os eventos gravados depois de FR3.3 já não têm o endereço completo (Q9). O ledger continua íntegro, e a reconciliação dá 0 divergência.
- **FR6.3** Uma conta `ANONYMIZED` não entra mais. A mesma carteira só volta com convite novo, como conta nova (Q3).
  - **Critério:** teste de login com a carteira anonimizada; a conta antiga não é reaberta.
- **FR6.4** A anonimização conclui o pedido de Exclusão e registra o evento `ACCOUNT_ANONYMIZED` sem dado pessoal.
  - **Critério:** teste de auditoria.

### FR7 — Alerta de prazo (C7, Should; Q7)

- **FR7.1** A lista de pedidos do administrador filtra por situação e marca, com ícone e texto, os pedidos a 3 dias ou menos do prazo e os vencidos.
  - **Critério:** teste de componente.

### FR8 — Histórico de versões (C8, Could; SD)

- **FR8.1** As páginas listam as versões anteriores, com data de vigência e link para o texto de cada uma.
  - **Critério:** teste de componente.

## Non-functional requirements

- **NFR1 — Privacidade por padrão.** Nenhum log, evento ou resposta de erro contém endereço completo de carteira, texto de pedido de titular, texto de feedback, token, nonce ou assinatura. A varredura do `verify.sh` e o `LogScanIT` ganham o texto do pedido de titular (FR5, FR6; regra do projeto).
- **NFR2 — Isolamento.** Toda leitura e escrita de pedido de titular filtra pelo tenant e pela pessoa da autenticação. A administração só acessa com o papel `ADMIN` (regra do projeto).
- **NFR3 — Desempenho.** As páginas `/termos` e `/privacidade` carregam com p95 ≤ 2 s. As rotas de pedido e de aceite respondem com p95 ≤ 500 ms, com 20 usuários simultâneos (meta das telas do beta).
- **NFR4 — Acessibilidade básica do beta.** Navegação por teclado, foco visível, ponto de entrada "Pular para o conteúdo" em todas as telas, rótulos visíveis, contraste legível no tema escuro, cor sempre com texto ou ícone, títulos h1 a h3 em ordem e regiões de página (Q7; regra do projeto).
- **NFR5 — Integridade.** O ledger e os fills continuam sem edição nem exclusão. A anonimização não executa UPDATE nem DELETE neles. A migração que permite o endereço vazio segue expand-contract e só vale no estado `ANONYMIZED` (FR6; regra do projeto).
- **NFR6 — Segredos.** A chave do HMAC da auditoria é segredo de configuração (`.env` local; gerenciador de segredos na nuvem). Fica fora do repositório e nunca vai para `NEXT_PUBLIC_*` (FR3.3).
- **NFR7 — Confiabilidade dos expurgos.** Cada expurgo novo roda uma vez por dia em lotes, com indicador de saúde que fica `DEGRADED` se falhar ou atrasar mais de 48 h, como os expurgos existentes (FR3).
- **NFR8 — Observabilidade.** Métricas de pedidos de titular abertos e vencidos, e contadores dos expurgos novos, no `/actuator/prometheus`. Indicador de saúde dos pedidos (`DEGRADED` com vencido aberto, FR5.7). Nenhuma métrica com rótulo de dado pessoal.
- **NFR9 — Testes.** Volume `Comprehensive`, piso de 80% de linhas, e testes escritos antes do código para anonimização, exclusão, novo aceite e expurgos. Dois testes obrigatórios novos: "a anonimização não deixa a carteira em nenhuma tabela nem log consultável" e "uma versão nova dos termos exige novo aceite antes de qualquer outra rota" (TP).

## Constraints

- Ledger append-only; nenhuma exclusão de conta (`app_user` referenciado sem `ON DELETE`) (CK; regra do projeto).
- Sem script de terceiro e sem rastreamento (SD).
- Tenant sempre do contexto de autenticação (regra do projeto).
- Os textos não prometem lucro e dizem que tudo é simulado (regra do projeto).
- Desenvolvedor solo, sem orçamento extra, sem data fixa; antes da abertura do beta (SD).
- O `ci.yml` e o `verify.sh` só são alterados pelo responsável do projeto: as lacunas da CI e o spec novo dependem dele (TP).
- Mudanças de banco em expand-contract (regra do time).

## Assumptions

| Premissa | Motivo |
|---|---|
| O parecer indica a base legal de cada tratamento, e o texto a cita | O parecer foi aprovado (IC); o texto é revisado pela assessoria (Q8) |
| A trilha de auditoria por 12 meses tem base legal (segurança e exercício de direitos) | Prazo existente; FR3.3 reduz o dado guardado |
| O código de correlação HMAC sem a chave não identifica a pessoa | A chave é segredo e fica fora do banco (NFR6) |
| Não há tratamento de dados de menores | O beta é por convite e destinado a adultos |
| Nenhum convidado real existe antes da minimização de FR3.3 | O beta ainda não abriu; os dados atuais são de desenvolvimento e teste (Q9) |

## Out of scope

Banner de cookies de terceiros, exportação automática dos dados pelo titular, outros idiomas, termos do modo REAL e encarregado externo (IC; SD).

## Open questions

| Pergunta | Bloqueia |
|---|---|
| Local de armazenamento e transferência internacional (depende da hospedagem) | Publicação da versão do beta (FR1.4) |
| E-mail do encarregado | Publicação da versão do beta (FR1.4) |
| Texto final aprovado pela assessoria | Publicação da versão do beta (FR1.4) |
