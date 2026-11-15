# Requisitos — Trading REAL

Conversa: português (Brasil). Fontes: `ideation/intent-capture/intent-statement.md`, `ideation/scope-definition/scope-document.md`, `ideation/feasibility/feasibility-assessment.md`, `ideation/feasibility/constraint-register.md` (TR-C01 a TR-C24), o mapa do código (`business-overview.md`, `architecture.md`, `code-structure.md` em `aidlc/spaces/default/codekb/eureka-trader-ai/`) e `requirements-analysis-questions.md` (Q1 a Q13).

## Análise da intenção

O fundador quer que o bot opere com dinheiro real na Solana sem que o usuário precise estar online, mantendo a perda máxima limitada por um teto por usuário e por limites por ordem e por dia, e uma liberação controlada: devnet, a carteira do fundador, depois até 20 convidados. A mudança é ampla (brownfield): hoje o REAL está bloqueado em quatro camadas (banco, HTTP, interface e termos), o ciclo do bot é uma transação curta, a auditoria recusa assinaturas e endereços, e mercado e risco são só simulados (mapa do código). Tipo: funcionalidade nova com mudança de regras; escopo: vários módulos; complexidade: alta; profundidade: Comprehensive.

## Requisitos funcionais

Prioridade: **Must** = primeira versão REAL; **Should** = logo depois (scope-document).

### FR1 Dados de mercado reais (Must)
- **FR1.1** O sistema deve coletar dados reais de mercado (preço, liquidez, volume, holders, criação) de uma fonte paga (CoinGecko) atrás da interface de fonte de mercado existente, com fonte, horário e `unknown` para campo ausente.
- **FR1.2** A tela do scanner deve exibir a atribuição exigida pelo fornecedor onde os dados aparecem.
- **FR1.3** O sistema deve recusar operar em REAL quando a fonte ativa for a simulada.
- **FR1.4** Dados do sinal com mais de 60 s não podem originar ordem REAL; a ordem é recusada como dado vencido (Q5).

### FR2 Carteira do bot (Must)
- **FR2.1** O usuário deve poder ativar uma carteira do bot por escolha própria, depois de aceitar os termos v3 (FR9) e confirmar com uma mensagem de consentimento assinada pela carteira principal (diferente da mensagem de login).
- **FR2.2** A chave da carteira do bot deve ser gerada e mantida dentro do KMS, sem exportação; nenhum código de produção gera, recebe ou lê a chave.
- **FR2.3** O usuário deve poder definir o próprio teto no perfil, até o máximo de 10 SOL; o administrador pode baixar o teto de qualquer usuário a qualquer momento; subir o teto máximo exige nova liberação (FR10).
- **FR2.4** Uma carteira do bot por usuário; o endereço é exibido para depósito.

### FR3 Depósito e saque (Must)
- **FR3.1** O depósito é uma transferência de SOL da carteira principal para o endereço da carteira do bot, assinada pelo usuário na própria carteira; o sistema detecta o depósito na rede (Q8).
- **FR3.2** O bot só opera até o teto; o saldo acima do teto fica disponível só para saque, com aviso na tela (Q8).
- **FR3.3** O saque é permitido a qualquer momento, inclusive com o kill switch acionado, e só para a carteira principal verificada do usuário; qualquer outro destino é recusado (Q9).
- **FR3.4** Com posições abertas, o saque libera só o SOL livre; o usuário pode pedir "vender tudo e sacar" (Q9).
- **FR3.5** Uma reserva mínima de SOL para taxas fica fora do saldo operável e é liberada no saque total (Q10).

### FR4 Modos de operação (Must)
- **FR4.1** Três modos: Assinada (padrão), Automática nas saídas (FR13) e Automática total (carteira do bot).
- **FR4.2** Voltar para um modo mais restrito é imediato; subir de modo exige consentimento assinado pela carteira principal.
- **FR4.3** No modo Assinada, o servidor monta a transação e o navegador só a assina; o envio e o acompanhamento ficam no servidor (Q4).

### FR5 Execução das ordens (Must)
- **FR5.1** Cada ordem REAL deve ser um registro com estados gravados: proposta, cotada, assinada, enviada, confirmada, falhou e ambígua, processado fora da transação do ciclo do bot (Q1).
- **FR5.2** A cotação e a transação de swap vêm da Jupiter; a cotação usada para assinar tem no máximo 10 s (Q5).
- **FR5.3** A derrapagem máxima é 2% por padrão, configurável pelo usuário até 5% (Q6).
- **FR5.4** A assinatura é feita pelo KMS (Automática total) ou pela carteira do usuário (Assinada).
- **FR5.5** Cada ordem tem uma chave de idempotência; uma repetição nunca gera uma segunda transação.
- **FR5.6** Uma ordem sem confirmação em 60 s fica ambígua e é conferida na rede pela assinatura antes de qualquer nova tentativa (Q13).

### FR6 Barreira de risco REAL (Must)
- **FR6.1** Antes de cada ordem REAL, a barreira confere: teto, limite por ordem (até 10% do teto), perda no dia (até 20% do teto), número de ordens no dia (até 20 por bot), tokens permitidos e frescor dos dados.
- **FR6.2** Ao atingir a perda máxima do dia, o bot do usuário pausa até o dia seguinte (UTC).
- **FR6.3** Só tokens que passaram pela avaliação do scanner com cobertura suficiente podem ser operados; dado `unknown` nunca conta como seguro; o administrador mantém uma lista de bloqueio.
- **FR6.4** A barreira REAL usa o saldo e o ledger REAL, nunca o PAPER.

### FR7 Ledger REAL e reconciliação (Must)
- **FR7.1** Os lançamentos REAL ficam separados dos PAPER, só por inclusão, e cada um tem a assinatura on-chain da transação que o originou.
- **FR7.2** A reconciliação compara o ledger REAL com os saldos e as transações na rede depois de cada ordem confirmada e a cada 15 minutos (Q7).
- **FR7.3** Uma divergência gera alerta e pausa o REAL daquele usuário até ser resolvida por lançamento compensatório (Q7).
- **FR7.4** As taxas da rede entram no P&L REAL (Q10).

### FR8 Kill switch REAL (Must)
- **FR8.1** O kill switch bloqueia novas ordens REAL de todos os usuários.
- **FR8.2** Ele cancela as ordens condicionais abertas criadas pelas carteiras do bot.
- **FR8.3** O saque continua liberado; as posições abertas ficam como estão.

### FR9 Termos de uso v3 (Must)
- **FR9.1** Uma versão nova dos termos (v3) descreve o REAL, o risco de perda total do valor depositado, a carteira do bot com responsabilidade compartilhada e a ausência de promessa de lucro (Q12).
- **FR9.2** O aceite da v3 é exigido antes de ativar a carteira do bot, pelo mecanismo de novo aceite existente.

### FR10 Liberação controlada (Must)
- **FR10.1** O REAL é desligado por padrão e ligado por usuário pelo administrador.
- **FR10.2** Uma liberação só passa a valer 24 horas depois de registrada, e só se os portões automáticos estiverem verdes.
- **FR10.3** A ordem de liberação é: devnet, a carteira do fundador na mainnet, depois até 20 convidados.
- **FR10.4** A rede é escolhida por ambiente: devnet e validador local nos testes; mainnet só com configuração explícita do ambiente de produção (Q11).

### FR11 Auditoria REAL (Must)
- **FR11.1** Eventos REAL têm campos próprios e tipados para a assinatura da transação, o mint e a carteira do bot (Q2).
- **FR11.2** A carteira principal do usuário continua registrada só como HMAC; os campos livres de `details` mantêm a regra atual; os logs continuam mascarando endereços e assinaturas (Q2).
- **FR11.3** Ativação, depósito detectado, ordem (cada mudança de estado), saque, mudança de modo, mudança de teto, liberação e kill switch geram evento.

### FR12 Anonimização com carteira do bot (Must)
- **FR12.1** A anonimização é recusada com explicação enquanto a carteira do bot tiver saldo ou posição (Q3).
- **FR12.2** Sem saldo e sem posição, a anonimização encerra a carteira do bot.

### FR13 Automática nas saídas (Should)
- **FR13.1** Stop e alvo de uma posição viram ordens condicionais da Jupiter, assinadas uma vez, que disparam sem o usuário online.

### FR14 Exportação fiscal (Should)
- **FR14.1** O usuário exporta o histórico REAL com data, token, quantidade, preço, taxa e assinatura.

### FR15 Administração (Must)
- **FR15.1** O administrador vê, por usuário: modo, teto, saldo REAL, estado da liberação, ordens recentes e divergências.
- **FR15.2** O administrador liga e desliga o REAL por usuário, baixa tetos e mantém a lista de bloqueio de tokens.

## Requisitos não funcionais

- **NFR1 Segurança das chaves:** chaves só no KMS, não exportáveis; apenas o serviço executor tem permissão de assinar; toda assinatura gera evento de auditoria; nenhum segredo em log, resposta ou `NEXT_PUBLIC_*`.
- **NFR2 Separação REAL/PAPER:** fundos, ordens, ledger, posições e P&L estritamente separados; testes provam que nenhuma operação cruza os modos.
- **NFR3 Desempenho:** da decisão do bot ao envio da transação, p95 de até 3 s; confirmação acompanhada por até 60 s (Q13).
- **NFR4 Confiabilidade:** nenhuma ordem duplicada (idempotência); estados gravados sobrevivem a reinício; ordem ambígua nunca é repetida às cegas; falha do RPC ou da Jupiter pausa novas ordens sem afetar o saque.
- **NFR5 Observabilidade:** métricas de ordens por estado, tempo até a confirmação, divergências de reconciliação, saldo total em carteiras do bot e assinaturas do KMS; alertas para divergência, ordem ambígua e falha de assinatura.
- **NFR6 Isolamento:** o tenant e o usuário vêm da autenticação; uma carteira, ordem ou saldo de outro usuário nunca é visível nem operável.
- **NFR7 Acessibilidade:** as telas novas seguem a acessibilidade básica do beta (teclado, foco visível, rótulos, contraste, cor com texto).
- **NFR8 Qualidade:** testes antes do código no núcleo financeiro (limites, ledger, P&L, idempotência) e na lógica de carteira e saque; piso de 80% de linhas; testes obrigatórios na CI para REAL desligado por padrão, limites, separação REAL/PAPER, idempotência e saque só para a carteira principal.
- **NFR9 Custo:** até US$ 100 por mês no piloto (KMS, RPC, dados).
- **NFR10 Dinheiro e tempo:** valores em decimal exato ou lamports, nunca ponto flutuante; instantes em UTC.

## Restrições

- Sem programa on-chain próprio; sem SDK Solana na produção; a Jupiter monta a transação e a produção só assina (TR-C03, TR-C04).
- A API de swap da Jupiter só opera na mainnet; os swaps são testados num validador local com contas clonadas (TR-C05).
- Conta AWS nova para o KMS (TR-C20).
- Fundador solo, único revisor; liberação com quarentena de 24 horas (TR-C15, TR-C16).
- Regras de `project.md` e `team.md` sobre o REAL, a geração de chave e a execução no navegador mudam pelo registro do AI-DLC antes da Construction (feasibility-assessment).

## Premissas

- O KMS escolhido assina Ed25519 — a confirmar antes do Domain Design (prova técnica).
- A Jupiter devolve transações que a produção consegue assinar e serializar sem SDK Solana — a confirmar na prova técnica.
- O plano pago da CoinGecko permite exibir os dados com atribuição — a confirmar com o fornecedor.

## Fora do escopo

Vault ou programa on-chain próprio, auditoria externa (G3), público aberto e cobrança, outras redes e roteadores, alavancagem, derivativos e empréstimo (scope-document).

## Perguntas em aberto

- Valor exato da reserva mínima para taxas (FR3.5) — Functional Design.
- Critério objetivo de "portões verdes" para a liberação (FR10.2) — Functional Design e CI Pipeline.
- Parecer de custódia — recomendado antes de liberar convidados na mainnet (decisão do fundador).
