# Units de trabalho — paper-real-unico

Conversa: português (Brasil). Data: 2026-11-21.

Este documento descreve as quatro Units do plano aprovado: o que cada uma entrega, os componentes do catálogo que ela possui, a implantação, a complexidade, o tipo (`kind`) e as notas de implementação. A ordem em que as Units serão construídas não é decidida aqui; ela fica para o Delivery Planning, a partir do grafo de `unit-of-work-dependency.md`.

## Sources

- `units-generation-questions.md`: Q1 a Q6 e o resumo confirmado com "Looks correct". É o plano que vincula este documento.
- `inception/domain-design/components.md`: o catálogo de componentes, a posse das entidades e a justificativa de cada componente.
- `inception/domain-design/decisions.md`: ADR-001 a ADR-011.
- `inception/requirements-analysis/requirements.md`: FR1 a FR8, NFR1 a NFR8 e a restrição de ordem de entrega.
- `inception/user-stories/stories.md`: US1.1 a US8.1, 34 histórias.
- `aidlc/spaces/default/memory/team.md`: Walking Skeleton, "Fatia do intent `261121-paper-real-unico`", Testing Posture e Fronteiras de módulos.
- `aidlc/spaces/default/memory/project.md`: NEVER somar os modos PAPER e REAL; ALWAYS manter REAL e PAPER separados.
- `reviews/review-01.md`: revisão de arquitetura (R-01 a R-06) e as decisões do fundador de 2026-11-21 sobre ela: a U2 continua uma Unit só, com ponto de controle interno, e a U1 não é implantada antes da U2.

## Identificação das Units

| Unit ID | Directory | Nome | Kind | Complexidade |
|---|---|---|---|---|
| U1 | `u1-mode-skeleton` | Esqueleto: criar no modo, lista única e visão com dois blocos | `service` | L |
| U2 | `u2-real-bot-pnl` | Núcleo financeiro: P&L REAL por bot, rateio e conferência | `service` | XL |
| U3 | `u3-pnl-views` | Telas do P&L: filtro, detalhe, rateio, períodos, painel e comparação | `ui` | M |
| U4 | `u4-own-quantity-cycle` | Ciclo REAL que vende só a quantidade do próprio bot | `service` | M |

O diretório é o nome da Unit no bloco `yaml` de `unit-of-work-dependency.md`. Os artefatos de Construction de cada Unit ficam em `construction/<directory>/`.

## Componentes por Unit

Cada componente do catálogo tem uma Unit dona. A Unit dona é a que cria o componente ou faz a maior parte da mudança nele. Quando outra Unit também muda o componente, a mudança aparece na seção "Componentes mudados por mais de uma Unit".

| Componente | Situação no catálogo | Unit dona |
|---|---|---|
| RealCreationEligibility | novo (bots) | U1 |
| RealAccountLimitsRead | novo (riskcontrol.real + riskcontrol.api) | U1 |
| ModePnlView | novo (pnl.modeview) | U1 |
| Bots | muda | U1 |
| PaperPnl | muda pouco | U1 |
| PaperLedger | sem mudança | U1 |
| BotWallet | sem mudança (só lido) | U1 |
| ModeStep | novo (web) | U1 |
| RealConfirmDialog | novo (web) | U1 |
| RealBotLimitsStep | novo (web) | U1 |
| SimulatedBadge | novo (web comum) | U1 |
| PnlModeBlock | novo (web) | U1 |
| RealProration | novo (pnl.real) | U2 |
| RealCostBasis | novo (pnl.real) | U2 |
| RealBotPnl | novo (pnl.application + pnl.real) | U2 |
| RealLedger | muda | U2 |
| RealExecution | muda | U2 |
| RealExits | muda pouco | U2 |
| MarketData | sem mudança (só lido) | U2 |
| BotModeFilter | novo (web) | U3 |
| ProratedLines | novo (web) | U3 |
| BotPnlLines | novo (web) | U3 |
| PeriodSelector | novo (web) | U3 |
| ComparisonView | novo (web) | U3 |
| RealBotHoldings | novo (pnl.api + pnl.application) | U4 |
| RealCycle | muda (bots.worker) | U4 |

São 26 componentes: 12 na U1, 7 na U2, 5 na U3 e 2 na U4. Nenhum componente ficou sem dono.

### Componentes mudados por mais de uma Unit

- **RealBotPnl (dono U2).** O bloco REAL do esqueleto precisa de um P&L por bot antes do núcleo financeiro. Na U1 nasce o serviço de aplicação do RealBotPnl, com a forma de resposta definitiva. Ele calcula cada bot só pelas ordens do próprio bot (`real_order.bot_id`), com o cálculo por ordens que já existe no `RealPnlCalculator`. Não tem rateio, nem "Sem bot", nem ordem por slot. O total REAL da U1 é o total da pessoa que já existe (`GET /api/v1/real/pnl`). A U2 troca o cálculo pelo RealCostBasis e pelo RealProration, acrescenta a linha "Sem bot" e passa o total REAL a ser o do próprio componente. Os testes do cálculo da U2 são escritos antes do código.
- **ModePnlView (dono U1).** A U1 entrega a visão com os dois blocos no período "desde o início", a regra ArchUnit NEVER_SUM_MODES e o teste de contrato sem total geral. A U3 acrescenta a escolha do período (FR7), as linhas de um bot com a origem (FR3.6) e a comparação (FR8). Cada acréscimo mantém a regra e o teste de contrato.
- **RealExecution (dono U2).** A U2 grava o slot de confirmação na ordem (ADR-011) e passa a trazer o slot e a assinatura na leitura das ordens. A U4 acrescenta a venda de uma quantidade dada e a leitura das vendas do bot ainda não assentadas (ADR-006).
- **Bots (dono U1).** A U4 não muda o módulo, mas o RealCycle fica em `bots.worker` e é dela. O aviso da tela de bots REAL (FR6.2) entra na lista `/bots` que a U1 entrega.
- **PnlModeBlock (dono U1) → ProratedLines (dono U3).** Na U1, a variante `full` do PnlModeBlock mostra o total e as linhas dos bots, sem as parcelas rateadas, que ainda não existem. A U3 muda o PnlModeBlock para incluir o ProratedLines em cada bot REAL e acrescenta a variante `compact` do painel. Os testes da U1 sobre o bloco continuam verdes.

## U1 — `u1-mode-skeleton`

**Descrição.** A menor fatia integrada deste intent: o bot é criado no modo escolhido, aparece na lista única e o P&L aparece em dois blocos separados, Simulado (US$) e Real (SOL), sem total geral. Atravessa navegador, API, ledger PAPER, ledger REAL e o validador Solana local.

**Responsabilidades**
- Passo "Modo e nome" no assistente de criação, com Simulado como padrão (FR1.1).
- Elegibilidade do REAL decidida no servidor, com só o motivo mais forte e o `effectiveAt` na quarentena (FR1.2, ADR-005). O POST de criação com `market = REAL` é recusado com 422 por qualquer um dos cinco motivos, com falha fechada.
- Confirmação explícita "Este bot opera com dinheiro real" (FR1.3).
- Etapa de limites do bot REAL, só para leitura e em SOL, a partir da leitura nova em `riskcontrol.api` (ADR-008).
- Modo fixo depois da criação (FR1.4).
- `mode == market` no resumo do bot, com `mode` obsoleto e um teste de contrato (FR2.4, ADR-009).
- Lista única com o modo em texto e o P&L na moeda do modo (FR2.1, FR2.2).
- Porta `BotDirectory` declarada em `pnl.api` e implementada pelo `bots` (ADR-007).
- ModePnlView com o bloco PAPER e o bloco REAL lado a lado, no período "desde o início", com o aviso fixo no simulado (FR4.1, FR4.2, ADR-003).
- Regra ArchUnit NEVER_SUM_MODES, teste de contrato do OpenAPI sem campo de total geral e teste de propriedade "nenhum terceiro número" (NFR3).
- Total PAPER do modo no formato de bloco e a conferência exata com o ledger PAPER (FR5.3, parte PAPER).
- Componentes web `ModeStep`, `RealConfirmDialog`, `RealBotLimitsStep`, `SimulatedBadge` e `PnlModeBlock` (variante `full`).
- Spec de ponta a ponta novo da fatia (NFR7).

**Componentes:** RealCreationEligibility, RealAccountLimitsRead, ModePnlView, Bots, PaperPnl, PaperLedger, BotWallet, ModeStep, RealConfirmDialog, RealBotLimitsStep, SimulatedBadge, PnlModeBlock. Também cria a primeira fatia do serviço do RealBotPnl (ver acima).

**Implantação:** embutida no monólito modular que já existe. A API e o worker são o mesmo artefato, e o `apps/web` sai no mesmo pipeline [Q5]. Nenhum serviço AWS novo (NFR8).

**Complexidade:** L. Muitas peças pequenas em dois módulos de backend, cinco componentes web, um contrato novo e o spec de ponta a ponta no validador local.

**Kind:** `service`. Muda a API e o contrato `openapi-guest.yaml` [Q6].

**Notas de implementação e restrições**
- **Bloco REAL sem rateio.** O bloco REAL da U1 mostra o P&L de cada bot só pelas ordens do próprio bot, sem rateio, sem a linha "Sem bot" e sem a ordem por slot. O total REAL é o total da pessoa que já existe. Por isso, até a U2, a soma dos bots REAL pode ser diferente do total REAL quando houver liquidação, condicional ou excedente, e o defeito do FR5.1 (a condicional fora do P&L REAL) continua. A invariante do FR5.3 só vale a partir da U2.
- **A U1 não é implantada antes da U2 (decisão do fundador, 2026-11-21).** A U1 não vai para a mainnet, nem para a devnet com convidados, antes da U2. Ela é verificada no validador local e na CI, no ponto de controle do esqueleto, e só sai para um ambiente com usuários junto com a U2 ou depois dela. Assim, nenhum usuário lê um bloco REAL cuja soma por bot ainda não fecha com o total.
- **Spec de ponta a ponta.** O spec novo cria um bot PAPER e um bot REAL no validador local, faz uma compra em cada modo e confere que cada bloco mostra só o próprio modo e que os modos nunca são somados (`team.md`, "Fatia do intent `261121-paper-real-unico`"). O responsável acrescenta o spec ao `scripts/verify.sh`. A verificação continua sendo `bash scripts/verify.sh`.
- **Só cenários simples no spec (decisão do fundador, 2026-11-21).** O spec da U1 usa só compras simples, uma por bot, sem liquidação, condicional ou excedente. Nesses cenários, o P&L por bot já fecha com o ledger. O spec confere a separação dos modos e a ausência de total geral, e não confere valores que dependam do arredondamento do custo.
- **Liberação no spec.** Para criar o bot REAL, o spec precisa de carteira ativa, REAL ligado, liberação vigente e kill switch desligado. A liberação vigente sem esperar as 24 horas da quarentena vem de uma semente que só existe no perfil de teste. Duas garantias, a detalhar no Functional Design da U1:
  - a semente nunca é carregada em produção: ela fica só no perfil de teste, e um teste de guarda (de configuração ou ArchUnit) falha se o perfil de teste ou a semente puder ser ativado na configuração de produção;
  - o relógio injetável não muda a quarentena de 24 horas do código de produção (ADR-005): o spec monta uma liberação já vigente, e nenhum caminho de produção encurta ou ignora o prazo.

  A carteira principal de teste é gerada só no código de teste.
- **Elegibilidade e limites.** A elegibilidade só libera a criação, nunca autoriza operar. A leitura de limites usa as mesmas entradas da `RealRiskPolicy`, para a tela mostrar o mesmo limite que a barreira aplica.
- **Fronteiras.** O ModePnlView fica em `pnl.modeview`, fora de `pnl.real`, porque a regra `NO_PAPER` vale para `pnl.real`. O `pnl` não passa a depender do `bots`.
- **Testes.** A regra de precedência dos motivos e o `mode == market` têm teste de unidade e de contrato. O isolamento entre contas (404 para o bot de outra pessoa) e a separação entre REAL e PAPER são testes obrigatórios por risco (`team.md`).

## U2 — `u2-real-bot-pnl`

**Descrição.** O núcleo financeiro do intent: o P&L REAL por bot com o custo médio do próprio bot, o rateio das vendas sem bot e do excedente, a linha "Sem bot", as taxas, a correção do P&L REAL e a conferência exata com o ledger.

**Responsabilidades**
- Coluna `slot` em `real_ledger_entry` e `real_order`, por migração expand, com o índice que inclui o slot e o preenchimento das linhas antigas; a linha sem slot fica sinalizada com o motivo (ADR-011).
- Chave de ordem de rede comum: slot, depois assinatura (FR3.8).
- Porta `RealCostMethod` em `ledger.api`, implementada pelo RealCostBasis; um único custo médio REAL, com o teste de paridade entre a carteira e a soma dos bots mais a parcela sem dono (FR5.2, ADR-002).
- RealProration: rateio pelo maior resto em lamports, desempate pela criação e pelo id do bot, origem de cada parcela e parcela sem dono com o motivo (FR3.3, FR3.4, FR3.7).
- RealBotPnl completo: custo médio por bot, realizado, não realizado com `unknown`, taxas (FR3.9), bot pausado ou removido (FR3.11), linha "Sem bot" e total REAL do modo por período (FR7.2, lado do servidor).
- Venda condicional no P&L REAL a partir do ledger REAL, com o teste de regressão escrito antes do código (FR5.1).
- Conferência exata com o ledger REAL, desde o início e por período (FR5.3).
- Leitura em fluxo dos lançamentos de swap e taxa por carteira, na ordem de rede, e o cenário de carga do NFR1.
- `GET /api/v1/real/pnl` passa a ser o total do RealBotPnl, e o cálculo por ordens do `RealPnlCalculator` é aposentado.

**Componentes:** RealProration, RealCostBasis, RealBotPnl, RealLedger, RealExecution, RealExits, MarketData.

**Implantação:** embutida no monólito modular, no mesmo pipeline [Q5]. A migração é só expand: nenhuma coluna é removida e nenhum lançamento muda de valor (append-only mantido).

**Complexidade:** XL. Migração com preenchimento das linhas antigas, uma porta entre módulos, dois cálculos puros novos, a troca do cálculo de posições do RealLedger, a conferência exata e a meta de desempenho.

**Decisão: a U2 continua uma Unit só, com um ponto de controle interno.** Fonte: decisão do fundador, 2026-11-21, depois da revisão de arquitetura (R-01).
- **Contexto.** A U2 tem 14 linhas do mapa e é dependência da U3 e da U4, então é o gargalo do intent.
- **Decisão.** A U2 fica como uma Unit XL. Dentro dela há um ponto de controle intermediário: a migração do slot com o preenchimento das linhas antigas, a porta `RealCostMethod` e o RealCostBasis prontos, com o teste de paridade verde.
- **Gatilho para dividir.** Se esse ponto de controle atrasar, a U2 é dividida no corte da revisão:
  - parte 1: o slot, o custo médio único e a paridade (US5.2 e a parte da U2 em US3.2);
  - parte 2: o rateio, a linha "Sem bot", a conferência, o período e a carga (US3.3 a US3.9, US5.1, a parte da U2 em US5.3, US5.4, a parte da U2 em US7.2 e US3.11).

  Dividir muda o plano aprovado (Q2), o bloco `yaml` e o mapa de histórias, e volta ao fundador.
- **Alternativas consideradas.** Dividir já em duas Units: rejeitado, porque as duas partes mudam o mesmo cálculo e os mesmos testes escritos antes do código, e a divisão só compensa se a primeira parte atrasar. Juntar a U3 na U2 (Q2-B): rejeitado no plano, porque aumenta a Unit que já é a maior.
- **Consequências.** Um só conjunto de artefatos de Construction para a U2. O ponto de controle interno dá um sinal cedo de atraso antes que a U3 e a U4 fiquem esperando.

**Kind:** `service`. Muda a API, o worker e o esquema [Q6].

**Notas de implementação e restrições**
- **Testes antes do código.** Toda a U2 é núcleo financeiro (P&L, ledger, custo médio, rateio). Os testes são escritos antes do código, a partir dos exemplos das histórias US3.2 a US3.9 e US5.1 a US5.4 e dos cenários do FR5.4 (`team.md`, Testing Posture; CT-15). Os exemplos já trazem os resultados em unidades base.
- **Ledger sem dependência do pnl.** O RealLedger chama a porta `RealCostMethod` do próprio `ledger.api`; a implementação é injetada em tempo de execução (`LedgerArchitectureTest.theLedgerNeverDependsOnThePnl`).
- **Pureza.** RealProration e RealCostBasis ficam em `pnl.real`: sem I/O, sem Spring, sem relógio, sem ponto flutuante e sem PAPER (`PURE_DOMAIN`, `NO_PAPER`). O I/O fica em `pnl.application`.
- **Valores exatos.** Lamports e unidades base em `long` com operações exatas; frações em `BigInteger`.
- **Perguntas para o Functional Design.** O arredondamento da quantidade de token rateada e do custo de uma venda parcial; a origem exata do preenchimento do slot nas linhas antigas.
- **Perguntas para o NFR Design.** O índice final com slot e o resultado do cenário de carga do NFR1 (p95 de até 1 s), antes da Code Generation.
- **Arquivos compartilhados com o intent 261113.** Os arquivos de `pnl` e a troca do cálculo de posições do RealLedger tocam o que a barreira REAL e o ciclo de hoje usam. O teste de paridade e os testes do 261113 precisam continuar verdes.

## U3 — `u3-pnl-views`

**Descrição.** As telas que mostram o P&L em detalhe: o filtro da lista por modo, o detalhe do bot com a origem de cada linha, as parcelas rateadas, a escolha do período, os totais do painel e a comparação entre um bot PAPER e um bot REAL.

**Responsabilidades**
- Filtro da lista por Todos, Simulado ou Real (FR2.3).
- Detalhe do bot REAL com as linhas e a origem em texto (FR3.6).
- Parcelas rateadas por origem em cada bot REAL do bloco.
- Escolha do período: desde o início (padrão), últimos 7 dias, últimos 30 dias ou intervalo, com conversão para UTC e o mesmo período nos dois blocos (FR7.1, FR7.2).
- Painel com os dois totais lado a lado, com o aviso no lado simulado (FR4.3), usando a variante `compact` do `PnlModeBlock`.
- Comparação de um bot PAPER com um bot REAL do mesmo `templateId`, cada um na sua moeda, sem diferença calculada (FR8.1).
- Na API, as leituras pequenas que essas telas pedem ao ModePnlView: o parâmetro de período, as linhas de um bot e a comparação.
- Aviso do simulado, acessibilidade e celular em 360 px nas telas novas (FR4.2, NFR5, NFR6).

**Componentes:** BotModeFilter, ProratedLines, BotPnlLines, PeriodSelector, ComparisonView.

**Implantação:** o `apps/web` e as leituras da API saem com o monólito, no mesmo pipeline [Q5].

**Complexidade:** M. Cinco componentes web e três leituras pequenas na API, sobre dados que a U2 já calcula.

**Kind:** `ui`. É principalmente a tela, com leituras pequenas da API [Q6].

**Notas de implementação e restrições**
- **Test-after.** As telas seguem a ordem test-after do `team.md`. O cálculo do período no servidor (realizado do período, não realizado de agora) é da U2 e já tem os testes escritos antes do código.
- **A tela não soma.** Os totais chegam calculados da API. A proibição de lint do dinheiro (`Number(`, `toFixed`, `parseFloat`) passa a valer para `features/pnl` (pergunta para o Functional Design). O valor em SOL é formatado só por `features/real/money.ts`.
- **Período.** O PeriodSelector reaproveita o `features/pnl/period.ts`; o padrão muda de 7 dias para "Desde o início". Os limites dos períodos ficam para o Functional Design (pergunta em aberto 7 das histórias).
- **Contrato.** Os tipos do app web são gerados do `openapi-guest.yaml`. Cada leitura nova mantém o teste de contrato sem campo de total geral.

## U4 — `u4-own-quantity-cycle`

**Descrição.** O ciclo do bot REAL passa a vender só a quantidade que o próprio bot comprou, e não a posição inteira do token na carteira do bot (FR6).

**Responsabilidades**
- RealBotHoldings em `pnl.api`: a quantidade e o custo próprios de cada bot por mint, menos as vendas do bot ainda não assentadas, com orçamento de tempo e falha fechada (ADR-006).
- RealCycle: as entradas de decisão levam a posição e o custo próprios do bot, e a venda pedida é só da quantidade disponível do bot. Sem quantidade disponível, não há venda.
- No RealExecution: a decisão de venda aceita a quantidade pedida pelo ciclo, e a leitura das vendas do bot em `PROPOSED`, `QUOTED`, `SIGNED`, `SENT` ou `AMBIGUOUS`.
- Configuração que liga e desliga a mudança, e o estado exposto para o aviso da tela de bots REAL (FR6.2).
- Aviso na lista `/bots` quando a mudança está ligada.

**Componentes:** RealBotHoldings, RealCycle.

**Implantação:** embutida no monólito; o RealCycle roda no processo worker, que é o mesmo artefato da API. Sai no mesmo pipeline das outras Units, com a configuração desligada. Ligar a configuração é uma implantação auditada separada, nunca junto com uma mudança de código (`team.md`, Flags) [Q5].

**Complexidade:** M. Um componente novo de leitura, uma mudança no ciclo e duas mudanças pequenas no RealExecution, mas com risco alto porque mexe em venda real.

**Kind:** `service`. Muda o worker e a API [Q6].

**Notas de implementação e restrições**
- **Configuração desligada por padrão.** Com a configuração padrão, o ciclo vende como hoje e o aviso não aparece (AC6.2.2).
- **Depois da Performance Validation do 261113.** A U4 só entra depois que o intent `261113-trading-real-bot` terminar a Performance Validation (restrição de ordem de entrega do `requirements.md`; ADR-006).
- **Testes antes do código.** A lógica de venda e de idempotência da ordem REAL está na lista de testes antes do código do `team.md`. O teste de "uma única transação por ordem REAL" (AC6.1.4) e o de falha fechada são escritos antes do código.
- **Falha fechada.** Se a leitura do RealBotHoldings passa do orçamento ou falha, o bot não vende naquele ciclo; o motivo vai para o log e para uma métrica, e nenhuma venda da carteira inteira é usada no lugar.
- **Perguntas para o NFR Design.** O número do orçamento de tempo do RealBotHoldings dentro da transação do ciclo, e se o RealBotHoldings precisa existir no papel de processo worker (`ProcessRole`).
- **Efeito no rateio.** Com a mudança ligada, deixa de surgir excedente novo vendido por um bot (FR3.4); o rateio do excedente da U2 continua valendo para o histórico.

## Riscos

| # | Risco | Probabilidade | Impacto | Mitigação |
|---|---|---|---|---|
| RK-1 | Na U1, a soma dos bots REAL não fecha com o total REAL quando há liquidação, condicional ou excedente, e o defeito do FR5.1 (condicional fora do P&L REAL) continua até a U2. | Alta | Alto | A U1 não é implantada na mainnet, nem na devnet com convidados, antes da U2 (decisão do fundador, 2026-11-21). O spec da U1 usa só compras simples, que fecham com o ledger. A invariante do FR5.3 é conferida a partir da U2. |
| RK-2 | A semente de liberação do spec da U1 é carregada em produção e libera o REAL sem quarentena. | Baixa | Alto | A semente fica só no perfil de teste, e um teste de guarda (configuração ou ArchUnit) falha se o perfil de teste ou a semente puder ser ativado na configuração de produção. É pergunta para o Functional Design da U1. |
| RK-3 | O relógio injetável usado no teste muda a quarentena de 24 horas do código de produção. | Baixa | Alto | O spec monta uma liberação já vigente; nenhum caminho de produção encurta ou ignora o prazo do ADR-005. Os testes da quarentena do intent 261113 continuam verdes. |
| RK-4 | Os valores do spec da U1 mudam quando a U2 troca o arredondamento do custo: o `ceilShare` do `RealPnlCalculator` arredonda o custo tirado para cima e o `RealPositions` para baixo. | Média | Médio | O spec da U1 não confere valores que dependam do arredondamento (só compras, sem venda). A regra única de arredondamento é fixada no Functional Design da U2, e o teste de paridade confere o resultado. |
| RK-5 | Conflito de arquivos com o intent 261113: os arquivos de `pnl`, o painel e o cálculo de posições do RealLedger, que a barreira REAL e o ciclo de hoje usam. | Média | Alto | As mudanças são coordenadas com o que o 261113 está validando (restrição do `requirements.md`). Os testes do 261113 e o teste de paridade precisam ficar verdes em cada commit. |
| RK-6 | A U3 (filtro) e a U4 (aviso do FR6.2) mudam o mesmo arquivo da lista `/bots`. | Média | Baixo | É o único arquivo comum entre as duas. O segundo a entrar integra a mudança do primeiro, e os testes da lista das duas Units continuam verdes. |
| RK-7 | A U4 depende da Performance Validation do intent 261113, que está fora deste intent e pode atrasar. | Média | Médio | A U4 sai com a configuração desligada e só é ligada numa implantação auditada separada, depois da Performance Validation. A U3 não depende dela. |
| RK-8 | A U2 (XL) atrasa e segura a U3 e a U4. | Média | Alto | Ponto de controle interno depois do teste de paridade, e divisão no corte da revisão se ele atrasar (decisão do fundador, 2026-11-21; ver a U2). |

## Assumptions & Open Questions

- [assumption] A primeira fatia do RealBotPnl na U1 usa o cálculo por ordens que já existe (`RealPnlCalculator`) filtrado por `bot_id`, para não escrever um segundo cálculo financeiro que a U2 jogaria fora. A U2 troca esse cálculo com os testes escritos antes do código.
- [assumption] A etapa de limites do bot REAL (RealAccountLimitsRead e RealBotLimitsStep) fica na U1, porque o assistente de criação de um bot REAL não termina sem ela; não há história própria para ela, e ela serve ao US1.1 e ao US1.3.
- [assumption] A conferência PAPER do FR5.3 (AC5.3.2) fica na U1, junto com o total PAPER do bloco; a conferência REAL fica na U2.
- As perguntas para o Functional Design e o NFR Design listadas em cada Unit vêm do `components.md` e não mudam a divisão das Units.
