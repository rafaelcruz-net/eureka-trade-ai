# Avaliação de viabilidade — paper-real-unico

## Veredito

**Viável com condições.**

O produto já tem quase tudo o que a intenção pede [Q2]:
- o bot nasce simulado (PAPER) ou real (REAL);
- o P&L simulado já sai por bot;
- o P&L real já sai por pessoa;
- cada ordem real decidida por um bot já guarda o bot que a decidiu.

O que falta é dividir o resultado real entre os bots de uma mesma pessoa. Isso é possível sem serviço novo e sem custo novo [Q2] [Q5] [Q7].

As decisões do fundador fecharam três pontos [Q8] [Q9] [Q10]:

1. **Método de custo.** O P&L real por bot usa custo médio por bot, o mesmo método que o PAPER, o REAL e o ledger já usam. O total de cada modo não muda [Q8].
2. **Vendas sem bot.** Só existem duas: "vender tudo e sacar" e as saídas condicionais. As ordens do modo Assinada têm bot, e a ordem manual não existe. O resultado dessas duas vendas é rateado entre os bots donos das quantidades vendidas, na proporção do que cada um tinha. O relatório marca "fechado por liquidação" ou "fechado por condicional" [Q9].
3. **O bot real vende só a própria quantidade.** Hoje, quando dois bots reais compram o mesmo token, a venda de um leva também os tokens do outro. O ciclo do bot real vai mudar, mas só depois que o intent `261113-trading-real-bot` terminar a Performance Validation. Até lá, a separação por bot é calculada só na leitura [Q10] [Q6].

Restam duas condições:

- **A mudança no ciclo do bot real depende do fim da Performance Validation do intent do REAL** [Q10] [Q6]. Até lá, o P&L por bot precisa de uma regra de leitura para a venda de um bot que passa da quantidade dele (ver a seção `## Assumptions & Open Questions`).
- **Há indício de que o P&L real de hoje não inclui as saídas condicionais executadas.** O ledger registra essas vendas, mas o cálculo do P&L real lê só a tabela de ordens. Se isso se confirmar, o total real já não fecha com o ledger depois de uma condicional executada, e a separação por bot herdaria o erro. Esse ponto pertence ao intent `261113-trading-real-bot` e deve ser confirmado por um teste lá (ver o problema I4 e a dependência D1 em `raid-log.md`).

## Viabilidade técnica, ponto a ponto

| Tema | O que o código mostra | Avaliação | Fontes |
|---|---|---|---|
| Bot de cada ordem real | Toda ordem com origem `BOT` tem o bot, e o banco obriga isso. A ordem de liquidação nunca tem bot. Só existem essas duas origens. | Viável. A atribuição sai do que já está gravado. | [Q1]; `apps/api/src/main/resources/db/changelog/changes/0019-real-execution.sql` (`real_order_origin_shape_ck`); `realexecution/api/RealOrderView.java` |
| Modo Assinada | As ordens assinadas pela pessoa também têm origem `BOT` e o bot que as propôs. | Viável. Essas ordens ficam no bot que as propôs. | `0019-real-execution.sql`; `realexecution/infra/DecisionConsumer.java` |
| Método de custo | O REAL, o PAPER e o ledger REAL usam custo médio. | Viável. Custo médio por bot mantém o mesmo método nos dois modos, e o total de cada modo não muda [Q8]. | `pnl/real/RealPnlCalculator.java`; `ledger/domain/PositionAccounting.java`; `ledger/domain/RealPositions.java` |
| Venda parcial | A venda de um bot pode ser parcial. O custo médio da parte vendida sai do bot, e o resto continua aberto. | Viável. | [Q8]; `pnl/real/RealPnlCalculator.java` |
| Venda do bot maior que a quantidade dele | Hoje o ciclo do bot real vê as posições, o saldo e o tempo de posse da carteira inteira, e vende a posição inteira do token. | **Condição.** O ciclo passa a vender só a quantidade do próprio bot, depois do fim da Performance Validation do intent do REAL [Q10]. Até lá, a separação é feita na leitura. | `bots/worker/RealCycle.java`; `realexecution/infra/DecisionConsumer.java` |
| Venda que fecha quantidades de vários bots | "Vender tudo e sacar" e a condicional vendem a posição da carteira daquele token, com as quantidades de todos os bots. Há no máximo uma condicional aberta por carteira e token. | Viável. O resultado é rateado na proporção do que cada bot tinha, com a marca de origem [Q9]. | `realexecution/infra/LiquidationJob.java`; `0022-real-exits.sql` (`uq_real_conditional_order_open`) |
| Saídas condicionais | A condicional pertence à posição (carteira e token), não a um bot. A pessoa a cria no modo "Automática nas saídas". A venda entra no ledger com a referência `conditional:<id>`, mas não vira linha na tabela de ordens. | **Condição.** O cálculo por bot precisa ler essas vendas. Há indício de que o P&L real atual não as lê (dependência do intent do REAL). | `realexits/application/FillPosting.java`; `realexits/application/ConditionalService.java`; `pnl/application/RealPnlService.java`; `realexecution/infra/JdbcRealExecutionStore.java` (`settled`) |
| Taxas | A taxa de compra entra no custo. A taxa de venda sai do resultado da venda. Uma ordem que falha na rede só gasta a taxa. A taxa paga pela carteira principal no modo Assinada também conta. | Viável. A taxa de uma ordem de bot fica com o bot. A taxa de uma venda sem bot é rateada junto com o resultado [Q9]. | `pnl/real/RealPnlCalculator.java` |
| Aluguel de conta (rent) | O aluguel de conta que a compra pagou entra no custo. | Viável: ele segue a compra do bot. | `RealPnlCalculator.java` |
| Preço desconhecido | Preço ausente deixa a posição e o total como `unknown`, nunca zero. | Viável. Um token sem preço deixa o não realizado do bot dono como `unknown`. | [memory] `project.md`: "ALWAYS representar dado de mercado ausente como `unknown`"; `RealPnlService.java` |
| Aritmética | Os valores reais estão em lamports e unidades base, em inteiros exatos. O arredondamento do custo é para cima. | Viável. O rateio entre bots também usa inteiros, com uma regra fixa para o resto do arredondamento. | [memory] `project.md`: "NEVER usar ponto flutuante" |
| Ordens antigas (backfill) | O P&L real é recalculado a cada leitura, a partir das ordens. Todas as ordens com origem `BOT` desde que o REAL existe já têm bot. | Viável sem migração de dados. O passado é refeito na leitura, e a mudança é reversível. | `0019-real-execution.sql`; `RealPnlService.java` |
| Unidades dos dois modos | O simulado está em dólar, com decimais. O real está em lamports de SOL. | Viável. Isso reforça a regra de nunca somar os dois num número só [Q3]. | `pnl/domain/PnlCalculator.java`; `pnl/web/RealPnlController.java` |
| Telas | O painel já tem um cartão de P&L simulado e um de P&L real. A tela de P&L simulado já filtra por bot. | Viável: são mudanças nas telas que já existem. | `apps/web/src/features/dashboard/PnlCard.tsx`; `apps/web/src/features/real/components/DashboardRealPnl.tsx`; `apps/web/src/features/pnl/PnlScreen.tsx` |

## O que já existe e o que é novo

| Já existe | É novo |
|---|---|
| Criar o bot como PAPER ou REAL (`BotMarket`) | Visão única dos bots dos dois modos, lado a lado |
| P&L PAPER por bot e total (`GET /api/v1/pnl?botId=`) | P&L REAL por bot, por custo médio por bot [Q8] |
| P&L REAL por pessoa (`GET /api/v1/real/pnl`) | Rateio das vendas sem bot, com as marcas "fechado por liquidação" e "fechado por condicional" [Q9] |
| O bot de cada ordem real gravado na ordem | Total REAL igual à soma das linhas por bot, conferido com o ledger REAL |
| Ledger PAPER e ledger REAL separados | Ciclo do bot real vendendo só a própria quantidade, depois da validação do intent do REAL [Q10] |
| Tratamento de `unknown` nos dois modos | — |

## Principais riscos

A escala vai de Baixa a Alta. O detalhe e as mitigações estão em `raid-log.md`.

| ID | Risco | Probabilidade | Impacto |
|---|---|---|---|
| R1 | Até o ciclo mudar, um bot real vende tokens de outro bot. Nesse período, o P&L por bot depende da regra de leitura. | Alta (sempre que dois bots reais operam o mesmo token) | Médio |
| R2 | A mudança no ciclo do bot real atrasa, porque a Performance Validation do intent do REAL ainda não terminou. | Média | Médio |
| R5 | O P&L real atual pode não incluir as condicionais executadas. Nesse caso, o total não fecha com o ledger. Ver o problema I4 e a dependência D1. | Média (indício no código, ainda não confirmado por teste) | Alto |
| R6 | A tela põe o resultado simulado ao lado do real, e o simulado é lido como promessa de lucro. | Média | Alto (regra C-16) |
| R7 | O rateio com arredondamento faz a soma das linhas diferir do total em alguns lamports. | Média | Médio |

## AWS

Nenhum serviço novo e nenhuma conta nova [Q7]. A feature roda no ambiente único da mainnet, já desenhado no Terraform e ainda não aplicado. Não há custo novo de infraestrutura, e o orçamento de US$ 350 por conta continua [Q5] [Q7]. O cálculo por bot é feito na leitura, sobre as mesmas tabelas, sem fila, armazenamento ou processamento novos [Q10].

Hipótese: com até 20 convidados e o recálculo a cada leitura, a carga extra é pequena. O intent do REAL ainda mede a carga na Performance Validation [Q6].

## Conformidade

- **Nada novo de regulação** [Q3]. Os termos v3 já descrevem o PAPER e o REAL, e a LGPD já cobre os dados. A atribuição por bot usa só dados que já existem: ordens, bot e ledger. Não há dado pessoal novo nem finalidade nova.
- **Nunca misturar** [memory] `project.md`: "ALWAYS manter REAL e PAPER estritamente separados". Na prática:
  - o P&L de cada bot mostra o modo dele;
  - cada modo tem o próprio total;
  - não existe um "total geral" que some simulado com real.

  As unidades já são diferentes: dólar no simulado e SOL no real. Somar os dois seria errado também na conta.
- **Nunca prometer lucro** [memory] `project.md`: "NEVER prometer lucro" (C-16). O simulado ao lado do real pode sugerir que "o real vai render como o simulado". Para evitar isso:
  - o simulado sempre traz o rótulo de simulado, em texto e não só em cor;
  - a tela traz um aviso curto: resultado simulado não garante resultado real, porque o real tem taxas de rede, derrapagem e execução que o simulado não tem;
  - não existe ranking nem destaque do "melhor bot" que misture os dois modos.
- **Isolamento entre contas.** O cálculo por bot filtra pela conta e pela pessoa, como o cálculo de hoje [memory] `project.md`: "ALWAYS validar o tenant no servidor".

## Sources

- [Q1] a [Q10]: `ideation/feasibility/feasibility-questions.md`.
- `ideation/intent-capture/intent-statement.md` e `stakeholder-map.md`.
- [memory] `aidlc/spaces/default/memory/project.md` (Mandated e Forbidden) e `team.md` (Testing Posture e Code Style).
- Código (caminhos relativos a `apps/api/src/main/java/com/memetrade/`, salvo indicação):
  - `bots/domain/BotMarket.java`, `bots/application/BotCommandService.java`, `bots/worker/RealCycle.java`;
  - `pnl/real/RealPnlCalculator.java`, `pnl/application/RealPnlService.java`, `pnl/web/RealPnlController.java`, `pnl/web/PnlController.java`, `pnl/domain/PnlCalculator.java`;
  - `realexecution/api/RealOrderView.java`, `realexecution/infra/DecisionConsumer.java`, `realexecution/infra/JdbcRealExecutionStore.java`, `realexecution/infra/LiquidationJob.java`, `realexecution/infra/RealExecutionPurgeJob.java`;
  - `realexits/application/FillPosting.java`, `realexits/application/ConditionalService.java`;
  - `ledger/domain/RealPositions.java`, `ledger/domain/PositionAccounting.java`;
  - `apps/api/src/main/resources/db/changelog/changes/0016-real-bot-wallet.sql`, `0019-real-execution.sql`, `0022-real-exits.sql`;
  - `apps/web/src/features/dashboard/PnlCard.tsx`, `apps/web/src/features/real/components/DashboardRealPnl.tsx`, `apps/web/src/features/pnl/PnlScreen.tsx`.

## Assumptions & Open Questions

- [assumption] O cálculo por bot continua sendo feito na leitura, a partir das ordens e do ledger, sem tabela nova [Q10]. Uma tabela de resumo fica como opção só se a carga exigir.
- [assumption] A leitura do código sobre as condicionais (R5) está certa: o P&L real de hoje não lê as vendas das condicionais. Isso precisa ser confirmado por um teste no intent `261113-trading-real-bot` antes dos requisitos.
- [assumption] Até o ciclo do bot real mudar [Q10], a venda de um bot que passa da quantidade dele é separada na leitura assim:
  - a parte que cabe na quantidade do bot fica com ele;
  - o excedente é rateado entre os outros bots donos do token, do mesmo jeito que as vendas sem bot [Q9];
  - o relatório marca esse excedente como vendido por outro bot.

  A regra exata é definida nos requisitos.
