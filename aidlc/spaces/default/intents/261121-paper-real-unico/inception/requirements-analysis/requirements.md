# Requisitos — paper-real-unico

Fontes:
- `requirements-analysis-questions.md` (Q1 a Q14);
- `ideation/intent-capture/intent-statement.md`;
- `ideation/feasibility/` (avaliação, restrições, RAID; decisões Q1 a Q10);
- `ideation/scope-definition/scope-document.md` e `intent-backlog.md`;
- `ideation/rough-mockups/`;
- o mapa do código em `aidlc/spaces/default/codekb/eureka-trader-ai/`;
- `inception/practices-discovery/team-practices.md`.

Profundidade: Standard. É uma melhoria em vários componentes de um sistema que já existe.

## Análise da intenção

O usuário quer, num único ambiente na mainnet:
- criar bots simulados (PAPER) e reais (REAL) lado a lado;
- acompanhar o P&L de cada bot;
- ver o total de cada modo, sempre separado e nunca somado.

O simulado é o PAPER que já existe. O valor novo para o usuário está em três pontos:
- poder criar um bot REAL pelo app;
- ver o P&L REAL por bot, que hoje só existe por pessoa;
- ter uma visão única, com os dois modos separados.

O fundador também quer que os usuários usem um ambiente só [intent Q1 a Q4] [scope Q1].

## Requisitos funcionais

### FR1 — Criar bot em qualquer modo

- **FR1.1** A tela de criação de bot tem a escolha "Simulado" ou "Real", com "Simulado" como padrão [Q1].
- **FR1.2** A opção "Real" só fica disponível quando a carteira do bot está ativa, a liberação está vigente e o REAL está ligado. Fora disso, ela aparece desabilitada, com o link "Ativar a carteira do bot" quando couber e com um texto próprio para cada motivo [Q1] [Q14]:
  - carteira do bot inativa;
  - liberação ausente;
  - liberação em quarentena;
  - REAL desligado;
  - kill switch acionado.
- **FR1.3** Antes de criar um bot REAL, o usuário confirma explicitamente a frase "Este bot opera com dinheiro real" [Q1].
- **FR1.4** O modo de um bot não muda depois da criação, e nenhum bot é convertido de um modo para o outro [intent Q8].

### FR2 — Lista única de bots

- **FR2.1** A lista de bots mostra os bots PAPER e REAL juntos, com o modo em texto (`SIMULADO` ou `REAL`) e não só em cor [scope Must] [mockups Q1].
- **FR2.2** Cada bot da lista mostra o P&L dele na moeda do próprio modo: dólar no PAPER e SOL no REAL [mockups Q3].
- **FR2.3** A lista tem um filtro por modo: Todos, Simulado ou Real (Should) [scope Should].
- **FR2.4** O contrato usa `market` (`PAPER` ou `REAL`) como o modo do bot em todas as respostas. O campo `mode` do resumo passa a valer exatamente o mesmo que `market` e fica marcado como obsoleto, sem quebrar os clientes atuais. Um teste de contrato confere `mode == market` para os dois mercados [Q6] [Q14].

### FR3 — P&L por bot

- **FR3.1** O P&L PAPER por bot continua como já é hoje [feasibility].
- **FR3.2** O P&L REAL por bot é calculado na leitura, com custo médio por bot. Cada compra de um bot entra no custo médio daquele bot, e cada venda de um bot reduz a quantidade e realiza o resultado daquele bot [feasibility Q1, Q8].
- **FR3.3** O resultado e a taxa das vendas sem bot ("vender tudo e sacar" e as saídas condicionais) são rateados entre os bots donos das quantidades vendidas. A proporção usada é a das quantidades que cada bot tinha logo antes da venda. Cada parcela fica marcada como "fechado por liquidação" ou "fechado por condicional". Uma condicional executada em parte é rateada só sobre a quantidade executada [feasibility Q9] [Q11] [Q12].
- **FR3.4** Enquanto o ciclo do bot REAL não mudar (FR6), quando um bot vende mais do que comprou, o excedente é rateado entre os outros bots donos do token, como no FR3.3. Essa parte fica marcada como "vendido por outro bot". Se não houver nenhum outro bot dono, por exemplo numa posição herdada de antes deste intent, o excedente vai para a linha "Sem bot" com o motivo "excedente sem dono" [feasibility A8] [Q11].
- **FR3.7** **Arredondamento do rateio.** O rateio em lamports usa o método do maior resto:
  - cada bot recebe a sua parte inteira;
  - os lamports que sobram vão, um a um, para os bots com a maior fração;
  - o empate é decidido pela data de criação do bot e, depois, pelo id do bot.

  A soma das parcelas é sempre exatamente igual ao valor rateado [Q10].
- **FR3.8** **Ordem das operações.** As operações REAL são aplicadas na ordem da confirmação na rede: primeiro o slot, depois a assinatura. Operações no mesmo instante seguem essa mesma ordem [Q11].
- **FR3.9** **Taxas.** A regra para cada caso [Q12]:
  - a taxa de uma ordem que falhou na rede fica no bot que enviou a ordem;
  - no modo Assinada, a taxa é paga pela carteira principal e entra no P&L do bot que decidiu a ordem;
  - a taxa de uma venda sem bot é rateada junto com o resultado (FR3.3).
- **FR3.10** **Custo e preço desconhecido.** O custo usa sempre os lamports efetivamente pagos, nunca um preço. Quando o preço é `unknown`, só o não realizado fica desconhecido; o realizado e o custo continuam exatos [Q12].
- **FR3.11** **Bot pausado ou apagado.** Um bot pausado ou apagado continua dono dos seus lotes e do seu P&L, e aparece como "pausado" ou "removido" [Q12].
- **FR3.5** O P&L de cada bot mostra o realizado, o não realizado e as taxas. Se faltar o preço, o não realizado aparece como desconhecido (`unknown`), nunca como zero [mockups] [project.md].
- **FR3.6** O detalhe de um bot mostra as ordens e as linhas que compõem o P&L, com a origem de cada uma (Should) [scope Should] [mockups].

### FR4 — Totais por modo, nunca somados

- **FR4.1** O total do PAPER e o total do REAL aparecem separados, cada um na sua moeda. Nenhuma tela, API ou relatório soma os dois num total geral [scope Must] [Q9] [project.md].
- **FR4.2** O bloco do simulado mostra sempre o aviso "o resultado simulado não garante o resultado real", em todas as telas onde o total ou o P&L simulado aparece [mockups] [C-16].
- **FR4.3** O painel mostra os dois totais lado a lado, com o aviso no lado simulado [mockups].

### FR5 — Correção do P&L REAL

- **FR5.1** O P&L REAL passa a incluir as vendas das saídas condicionais a partir do ledger REAL. Isso corrige um defeito que existe hoje, e o teste de regressão é escrito antes do código [Q3].
- **FR5.2** Um único cálculo de custo médio REAL, a partir do ledger REAL, passa a ser a fonte. O outro cálculo (`RealPositions`) passa a usá-lo [Q2].
- **FR5.3** O P&L REAL de cada pessoa fecha com o ledger REAL, e o P&L PAPER fecha com o ledger PAPER, por igualdade exata nas unidades base [Q4] [Q11] [Q13]:
  - a soma do P&L de todos os bots mais a linha "Sem bot" é igual ao P&L do modo calculado a partir do ledger;
  - a linha "Sem bot" só pode ser diferente de zero com um motivo registrado;
  - em qualquer período, a soma do realizado dos bots no período mais a linha "Sem bot" do período é igual ao realizado do modo, calculado a partir dos lançamentos do ledger com data no período;
  - o não realizado é conferido só no período "desde o início".
- **FR5.4** **Cenários de aceite do cálculo.** Testes escritos antes do código cobrem cada um com o resultado esperado em unidades base [Q3] [Q10] [Q11] [Q12]:
  - condicional executada por inteiro e em parte;
  - "vender tudo e sacar" com dois e com três bots donos;
  - resto de arredondamento com empate;
  - excedente com e sem outro dono;
  - taxa de ordem que falhou;
  - taxa no modo Assinada;
  - preço `unknown`;
  - bot apagado;
  - venda no mesmo slot.

### FR6 — O bot REAL vende só a própria quantidade

- **FR6.1** O ciclo do bot REAL passa a vender só a quantidade que o próprio bot comprou, e não a posição inteira do token na carteira do bot [feasibility Q10].
- **FR6.2** Quando o comportamento mudar, a tela de bots REAL mostra um aviso dizendo que cada bot passa a vender só o que ele comprou [Q14].

### FR7 — Períodos

- **FR7.1** O P&L pode ser visto desde o início (padrão), nos últimos 7 dias, nos últimos 30 dias ou num intervalo escolhido [Q5].
- **FR7.2** O realizado conta as vendas dentro do período, e o não realizado é sempre o de agora. Os instantes ficam em UTC e são mostrados no fuso da pessoa [Q5] [project.md].

### FR8 — Comparação lado a lado (Could)

- **FR8.1** Uma vista compara um bot PAPER com um bot REAL da mesma estratégia, cada um na sua moeda, sem ranking misturado e com o aviso no simulado [scope Could].

## Requisitos não funcionais

- **NFR1 — Desempenho.**
  - O P&L por bot e por modo responde com p95 de até 1 s, para uma pessoa com até 10 bots e 2 anos de histórico [Q8].
  - O p95 é medido no cenário de carga da CI e repetido na devnet [Q14].
  - O cálculo na leitura nunca trava a execução de ordens.
- **NFR2 — Exatidão.**
  - Valores em decimal exato ou em unidades base, nunca em ponto flutuante.
  - O P&L fecha com o ledger por igualdade exata [Q4] [project.md].
  - Os testes do cálculo são escritos antes do código, porque o P&L é parte do núcleo financeiro [team.md].
- **NFR3 — Isolamento e segurança.**
  - O tenant vem sempre do token, e o bot de outra pessoa responde 404.
  - Nenhum endereço de carteira aparece no P&L.
  - Um teste obrigatório confere que nenhuma resposta traz um total que some os modos [Q9] [project.md].
- **NFR4 — Separação dos modos.** REAL e PAPER continuam estritamente separados em fundos, ordens, ledger, posições e P&L [project.md].
- **NFR5 — Acessibilidade.**
  - Navegação por teclado, foco visível e rótulos visíveis.
  - Contraste mínimo de 4,5:1 no tema escuro, e cor sempre acompanhada de texto [Q14].
  - Cada total tem um rótulo para leitor de tela com o modo e a moeda [mockups Q6].
- **NFR6 — Telas.**
  - Computador e celular a partir de 360 px. Em 360 px não há rolagem lateral e todos os valores ficam legíveis [Q14].
  - Os estados de carregando, erro, `unknown` e vazio são distintos [mockups].
- **NFR7 — Testes.**
  - Volume `Comprehensive`, com piso de 80% de linhas em cada parte [team.md].
  - Um spec de ponta a ponta novo, a fatia fina deste intent, mostra um bot PAPER e um bot REAL no validador local e confere que nenhum total soma os modos [practices Q8].
- **NFR8 — Custo.**
  - Os usuários têm um único ambiente, a mainnet.
  - Não entra nenhum serviço AWS novo, e o orçamento continua em US$ 350 por conta [Q7] [feasibility Q7].

## Restrições

- **Ordem de entrega**, com o risco primeiro: o cálculo, a API, a tela e por último o ciclo (FR6). O FR6 só entra depois que o intent 261113-trading-real-bot terminar a Performance Validation [scope Q4] [feasibility Q10].
- **Mesma pilha:** Spring Boot com Java 21, Next.js com TypeScript e PostgreSQL. Só módulos que já existem [feasibility Q2, Q4].
- **Termos:** os termos v3 já descrevem o PAPER e o REAL e não mudam neste intent. Nada promete lucro (C-16) [feasibility Q3] [Q14].
- **Arquivos compartilhados:** os arquivos de `pnl` e do painel também pertencem ao intent 261113. As mudanças são coordenadas para não quebrar o que ele está validando [feasibility Q6].

## Suposições

- [assumption] As ordens REAL antigas já guardam o bot e não precisam de migração, porque o rateio é calculado na leitura [feasibility].

## Fora do escopo

- Misturar saldos, ordens ou P&L entre os modos.
- Converter um bot de um modo para o outro.
- Criar uma rede nova.
- Fazer a exportação fiscal do simulado.
- Abrir a devnet aos usuários [intent Q8] [practices].

## Perguntas em aberto

Nenhuma no nível dos requisitos. O rateio, o arredondamento, a ordem, as taxas e os bots pausados ou apagados foram fechados nas perguntas Q10 a Q12. O Functional Design detalha a forma das tabelas e das consultas.
