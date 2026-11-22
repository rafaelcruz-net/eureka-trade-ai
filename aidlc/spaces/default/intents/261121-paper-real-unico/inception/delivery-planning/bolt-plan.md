# Plano de Bolts — paper-real-unico

Conversa: português (Brasil). Data: 2026-11-21.

Um **Bolt** é uma passada de construção sobre uma parte do trabalho que termina em algo que roda. Este plano tem quatro Bolts, um por Unit, construídos um de cada vez. O primeiro é o **esqueleto andante** (walking skeleton), a menor fatia de ponta a ponta que funciona de verdade e prova que as peças se encaixam antes de o resto ser construído em cima dela.

## Sources

- `delivery-planning-questions.md`: Q1 a Q7 e o resumo confirmado com "Looks correct". É o plano que vincula este documento.
- `inception/units-generation/unit-of-work.md`: U1 a U4, componentes, complexidade, a decisão da U2 com ponto de controle interno e os riscos RK-1 a RK-8.
- `inception/units-generation/unit-of-work-dependency.md`: o grafo U1 → U2 → U3 e U1 → U2 → U4, o bloco `yaml` e a seção do esqueleto.
- `inception/units-generation/unit-of-work-story-map.md`: as histórias de cada Unit e a ordem dentro de cada Unit.
- `inception/contract-design/contract-summary.md`: os contratos C1 a C17, as decisões PQ-1 a PQ-5 e as perguntas abertas PQ-6 a PQ-10.
- `inception/requirements-analysis/requirements.md`: FR1 a FR8, NFR1 a NFR8 e a restrição de ordem de entrega (FR6 depois da Performance Validation do intent 261113).
- `inception/user-stories/stories.md`: US1.1 a US8.1.
- `inception/domain-design/components.md` e `decisions.md`: ADR-001 a ADR-011.
- `inception/refined-mockups/mockups.md`: M1 a M5 (criação, confirmação, limites, lista `/bots` e `/pnl`).
- `inception/practices-discovery/team-practices.md` e `aidlc/spaces/default/memory/team.md`: Way of Working, Walking Skeleton ("Fatia do intent `261121-paper-real-unico`"), Testing Posture e Deployment.
- `aidlc/spaces/default/memory/project.md`: NEVER somar os modos PAPER e REAL; ALWAYS manter REAL e PAPER separados; ALWAYS usar só commit com CI verde nos pontos de controle.
- `aidlc-state.md` deste intent: Construction Checkpoints `enabled`, Iteration `unit-major`, Execution `serial`, Test Strategy `Standard`.

## Forma de trabalho

- **Um Bolt por Unit, em série** [Q3, Q4]: B1 = U1, B2 = U2, B3 = U3, B4 = U4. Cada Bolt passa pelos estágios de Construction da sua Unit (Functional Design, NFR Requirements, NFR Design, Infrastructure Design quando couber, Code Generation) e termina num ponto de controle verificado.
- **Comando de verificação dos pontos de controle** [Q7]: `bash scripts/verify-checkpoint.sh`, que roda primeiro o `scripts/verify.sh` local com todos os specs de ponta a ponta; se a máquina não aguentar (falta de memória), a prova é o run verde do `ci` do mesmo commit, com a árvore limpa fora dos registros do AI-DLC e o commit no remoto (`team.md`, Walking Skeleton). A escolha do comando passa por uma pergunta própria ao responsável, no fim deste estágio.
- **Branch e merge** (`team.md`, Way of Working): desenvolvimento baseado em tronco. As mudanças entram direto em `main`, num commit que o responsável pede. Não há worktree nem branch por Bolt; squash-merge só acontece se o responsável pedir uma branch. Um ponto de controle só usa um commit com a CI verde. Se `main` ficar vermelha, a correção vem no commit seguinte, antes de qualquer outra mudança. Mensagens de commit em inglês, com prefixo do tipo e citando a Unit (por exemplo, `feat: U1 mode skeleton ...`).
- **Depois do esqueleto aprovado**, o framework oferece seguir automaticamente ou revisar cada ponto de controle. Plan Approval, confirmação de resumo, escolha do comando de verificação e falhas continuam exigindo o responsável.

## Sequência

| # | Bolt | Unit | Complexidade | Esqueleto | Bloqueio externo |
|---|---|---|---|---|---|
| B1 | Esqueleto: modo, lista única e dois blocos | U1 `u1-mode-skeleton` | L | sim | spec novo no `verify.sh` (ponto de controle) |
| B2 | Núcleo financeiro do P&L REAL por bot | U2 `u2-real-bot-pnl` | XL | — | possível opção de carga do NFR1 no `load-test.yml` (ver o mapa de dependências) |
| B3 | Telas do P&L | U3 `u3-pnl-views` | M | — | — |
| B4 | Ciclo REAL com a quantidade própria | U4 `u4-own-quantity-cycle` | M | — | Performance Validation do intent 261113 |

A ordem B1 → B2 → B3 → B4 é uma ordem topológica válida do grafo (U2 depende de U1; U3 e U4 dependem de U2; U3 e U4 não dependem uma da outra). Não há desvio do grafo. A justificativa da ordem está em `risk-and-sequencing-rationale.md`.

## B1 — Esqueleto: modo, lista única e dois blocos (esqueleto andante)

- **Unit:** U1 `u1-mode-skeleton`. Histórias: US1.1 a US1.5, US2.1, US2.2, US2.4, US3.1, a parte da U1 em US3.2, US4.1, US4.2, US4.4, US4.5 e US5.3.
- **Esqueleto:** **sim**. Prova o caminho navegador (telas de Bots e de P&L) → API (criação no modo, lista única e P&L por bot nos dois modos) → ledger PAPER e ledger REAL → blocos separados Simulado (US$) e Real (SOL), sem total geral e com o aviso fixo no simulado, tudo no validador Solana local. Partes da arquitetura que ele prova: a elegibilidade do REAL no servidor (ADR-005), a porta `BotDirectory` sem ciclo `bots ↔ pnl` (ADR-007), o `pnl.modeview` fora de `pnl.real` (ADR-003), a leitura de limites em `riskcontrol.api` (ADR-008) e `mode == market` no contrato (ADR-009).
- **Pré-requisitos:** nenhuma Unit. Usa o que já existe: ledgers PAPER e REAL, ordens REAL com `bot_id`, carteira do bot, liberação, kill switch e o cálculo por ordens do `RealPnlCalculator`. O Functional Design da U1 responde a PQ-7 (estados do bot no P&L) e alinha o ADR-005 ao 409 `REAL_NOT_ELIGIBLE` decidido em PQ-5.
- **Definição de pronto:**
  - criação no modo escolhido, com Simulado como padrão, elegibilidade decidida no servidor com o motivo mais forte e falha fechada, confirmação "Este bot opera com dinheiro real", etapa de limites só para leitura e modo fixo depois da criação;
  - lista única com o modo em texto e o P&L na moeda do modo; `mode == market` com teste de contrato;
  - `/pnl` com os dois blocos no período "desde o início", regra ArchUnit `NEVER_SUM_MODES`, teste de contrato do OpenAPI sem campo de total geral e teste de propriedade "nenhum terceiro número";
  - conferência exata do total PAPER com o ledger PAPER (AC5.3.2);
  - semente de liberação só no perfil de teste, com teste de guarda que falha se ela puder ser ativada na configuração de produção (RK-2), e nenhum caminho de produção que encurte a quarentena de 24 horas (RK-3);
  - testes obrigatórios por risco verdes: isolamento entre contas (404 para o bot de outra pessoa), separação entre REAL e PAPER e REAL desligado por padrão;
  - piso de 80% de linhas no backend e no `apps/web`; testes do intent 261113 verdes (RK-5);
  - `traceability.json` da Code Generation no formato do verificador (correção de `project.md`, 2026-11-20);
  - spec de ponta a ponta novo escrito e acrescentado pelo responsável ao `scripts/verify.sh`;
  - ponto de controle aprovado pelo responsável, sobre um commit com a CI verde.
- **Hipótese de confiança:** um bot PAPER e um bot REAL convivem na mesma conta e na mesma tela, e cada bloco mostra só o próprio modo, sem nenhum caminho (tela, API ou contrato) que some os dois. Se o spec novo passar no validador local e na CI, a separação dos modos está provada de ponta a ponta antes de o cálculo financeiro mudar.
- **Demonstração esperada:** no validador local, criar um bot Simulado e um bot Real (com a confirmação), fazer uma compra em cada um, ver os dois na lista `/bots` com o modo em texto e abrir `/pnl` com o bloco Simulado (US$, com o aviso) e o bloco Real (SOL), sem total geral.
- **Verificação integrada real:** `bash scripts/verify-checkpoint.sh` com o spec novo da fatia incluído no `scripts/verify.sh`. O spec cria os dois bots, faz uma compra simples em cada modo (sem liquidação, condicional ou excedente) e confere que cada bloco mostra só o próprio modo e que os modos nunca são somados. Ele não confere valores que dependam do arredondamento do custo (RK-4). O responsável acrescenta o spec ao `verify.sh`; o texto pronto vem na Code Generation da U1.
- **Implantação:** **a U1 não é implantada sem a U2** (decisão do fundador, 2026-11-21; RK-1). Ela roda e é verificada no validador local e na CI, mas não vai para a mainnet, nem para a devnet com convidados, antes da U2 ou junto com ela. Até a U2, a soma dos bots REAL pode não fechar com o total REAL quando houver liquidação, condicional ou excedente.

## B2 — Núcleo financeiro do P&L REAL por bot

- **Unit:** U2 `u2-real-bot-pnl`. Histórias: a parte da U2 em US3.2, US3.3 a US3.9, US3.11, US5.1, US5.2, a parte da U2 em US5.3, US5.4 e a parte da U2 em US7.2.
- **Esqueleto:** não.
- **Pré-requisitos:** B1 com o ponto de controle aprovado. O Functional Design da U2 alinha o ADR-011 às decisões PQ-2 (slot na tabela lateral `real_entry_slot`, só de inserção, com gatilho que recusa UPDATE e DELETE) e PQ-4 (linhas antigas sem preenchimento, marcadas `SLOT_MISSING` com o motivo). O NFR Design da U2 responde a PQ-10 (índice final e resultado da carga do NFR1).
- **Definição de pronto:**
  - testes escritos antes do código em toda a Unit, a partir dos exemplos de US3.2 a US3.9 e US5.1 a US5.4 e dos cenários do FR5.4, com resultados em unidades base (`team.md`, Testing Posture);
  - slot de confirmação gravado e chave de ordem de rede comum (slot, depois assinatura);
  - porta `RealCostMethod` em `ledger.api` implementada pelo RealCostBasis, com um único custo médio REAL e o teste de paridade verde (carteira = soma dos bots + parcela sem dono);
  - RealProration pelo maior resto em lamports, com origem de cada parcela e linha "Sem bot";
  - RealBotPnl completo: custo médio por bot, realizado, não realizado com `unknown`, taxas, bot pausado ou removido e total REAL por período no servidor;
  - venda condicional no P&L REAL, com o teste de regressão escrito antes do código (FR5.1);
  - conferência exata do P&L REAL com o ledger REAL, desde o início e por período (FR5.3);
  - `GET /api/v1/real/pnl` passa a ser o total do RealBotPnl e o cálculo por ordens do `RealPnlCalculator` é aposentado;
  - cenário de carga do NFR1 executado (p95 de até 1 s, 10 bots, 2 anos) com o resultado registrado;
  - migração só de expansão: nenhuma coluna removida e nenhum lançamento muda de valor; ArchUnit `PURE_DOMAIN`, `NO_PAPER` e `theLedgerNeverDependsOnThePnl` verdes;
  - spec da U1, testes do intent 261113 e piso de 80% verdes;
  - ponto de controle aprovado pelo responsável, sobre um commit com a CI verde.
- **Ponto de controle interno** (decisão do fundador, 2026-11-21; RK-8): no meio da Code Generation da U2, um commit com a CI verde em que estão prontos o slot (tabela lateral `real_entry_slot`, linhas antigas `SLOT_MISSING`), a porta `RealCostMethod` e o RealCostBasis, com o teste de paridade verde. Esse ponto não é um gate do engine; ele fica registrado no plano da Code Generation da U2 e é mostrado ao responsável antes de o rateio começar.
- **Gatilho para dividir a U2:** se o ponto de controle interno atrasar, a U2 é dividida no corte da revisão de arquitetura:
  - parte 1: o slot, o custo médio único e a paridade (US5.2 e a parte da U2 em US3.2);
  - parte 2: o rateio, a linha "Sem bot", a conferência, o período e a carga (US3.3 a US3.9, US5.1, a parte da U2 em US5.3, US5.4, a parte da U2 em US7.2 e US3.11).

  O atraso conta quando o ponto de controle interno não está verde ao fim da primeira passada da Code Generation da U2, ou quando o responsável o declara atrasado (ver as suposições). Dividir muda o plano aprovado, o bloco `yaml` e o mapa de histórias, então volta ao Units Generation e ao responsável antes de seguir; nesse caso, B2 vira B2a e B2b, e B3 e B4 passam a depender de B2b.
- **Hipótese de confiança:** o P&L REAL por bot, com o rateio das vendas sem bot e do excedente, fecha exatamente com o ledger REAL em lamports em todos os cenários do FR5.4, e a leitura continua dentro de p95 de 1 s sem travar a execução de ordens.
- **Demonstração esperada:** no validador local, um bot REAL que compra, uma venda "vender tudo e sacar" e uma condicional executada em parte; em `/pnl`, cada bot REAL mostra o próprio resultado, a linha "Sem bot" aparece quando cabe e a soma dos bots mais "Sem bot" é igual ao total REAL e ao ledger REAL, lamport por lamport.
- **Verificação integrada real:** `bash scripts/verify-checkpoint.sh` (todos os specs de ponta a ponta, inclusive o da U1), mais os testes de integração sobre PostgreSQL em contêiner com os cenários do FR5.4 e o teste de paridade, e o relatório do cenário de carga do NFR1.
- **Implantação:** a U2 é a condição para a U1 sair para um ambiente com usuários. As duas podem sair juntas depois do ponto de controle do B2 (nenhum ambiente na nuvem está provisionado hoje; `team.md`, Deployment).

## B3 — Telas do P&L

- **Unit:** U3 `u3-pnl-views`. Histórias: US2.3, US3.10, US4.3, US7.1, a parte da U3 em US7.2, US8.1 e as partes da U3 em US4.1, US4.2, US4.4 e US4.5.
- **Esqueleto:** não.
- **Pré-requisitos:** B2 com o ponto de controle aprovado. O Functional Design da U3 responde a PQ-6 ("Tentar de novo" de um bloco) e PQ-8 (`TEMPLATE_MISMATCH` na comparação), fixa os limites dos períodos e decide se a proibição de lint do dinheiro passa a valer para `features/pnl`.
- **Definição de pronto:**
  - filtro da lista por Todos, Simulado ou Real;
  - detalhe do bot REAL com as linhas e a origem em texto e as parcelas rateadas em cada bot;
  - escolha do período (desde o início como padrão, 7 dias, 30 dias ou intervalo), em UTC na API e no fuso da pessoa na tela, com o mesmo período nos dois blocos;
  - painel com os dois totais lado a lado (variante `compact` do `PnlModeBlock`) e o aviso no lado simulado;
  - comparação de um bot PAPER com um bot REAL do mesmo `templateId`, cada um na sua moeda, sem diferença calculada;
  - nenhum total geral em nenhuma tela nova; teste de contrato e `NEVER_SUM_MODES` verdes;
  - acessibilidade básica e celular em 360 px nas telas novas; estados de carregando, erro, `unknown` e vazio distintos;
  - testes da U1 sobre o bloco e testes da lista `/bots` verdes (RK-6); piso de 80% no `apps/web` e no backend;
  - ponto de controle aprovado pelo responsável, sobre um commit com a CI verde.
- **Hipótese de confiança:** a pessoa entende de onde vem cada número do P&L REAL (origem e parcelas) e consegue olhar o mesmo período nos dois modos e comparar um bot simulado com um real sem nunca ver um número que misture os dois.
- **Demonstração esperada:** em `/pnl`, trocar o período e ver os dois blocos mudarem juntos; abrir o detalhe de um bot REAL com as parcelas "fechado por liquidação" e "fechado por condicional"; ver o painel com os dois totais lado a lado; abrir a comparação de um bot PAPER e um bot REAL; repetir em 360 px.
- **Verificação integrada real:** `bash scripts/verify-checkpoint.sh` (todos os specs de ponta a ponta), mais os testes Jest das telas novas e os testes de contrato das leituras novas.

## B4 — Ciclo REAL com a quantidade própria

- **Unit:** U4 `u4-own-quantity-cycle`. Histórias: US6.1 e US6.2.
- **Esqueleto:** não.
- **Pré-requisitos:** B2 com o ponto de controle aprovado **e** a Performance Validation do intent `261113-trading-real-bot` concluída (restrição de ordem de entrega do `requirements.md`; ADR-006; RK-7). O NFR Design da U4 responde a PQ-9 (orçamento de tempo do RealBotHoldings dentro da transação do ciclo).
- **Definição de pronto:**
  - testes escritos antes do código para a venda da quantidade própria, para "uma única transação por ordem REAL" (AC6.1.4) e para a falha fechada;
  - RealBotHoldings em `pnl.api` com a quantidade e o custo próprios por mint, menos as vendas do bot ainda não assentadas, com orçamento de tempo e falha fechada;
  - RealCycle vende só a quantidade disponível do bot; sem quantidade disponível, não há venda;
  - **configuração desligada por padrão**: com a configuração padrão, o ciclo vende como hoje e o aviso não aparece (AC6.2.2);
  - aviso na lista `/bots` só quando a mudança está ligada;
  - testes obrigatórios por risco do REAL verdes (separação, idempotência, REAL desligado por padrão, política do TxSigner); piso de 80%;
  - ponto de controle aprovado pelo responsável, sobre um commit com a CI verde.
- **Hipótese de confiança:** com a mudança ligada num ambiente de teste, um bot REAL nunca vende a parte de outro bot nem gera uma segunda transação para a mesma ordem, e com a configuração padrão nada muda no comportamento que a Performance Validation do 261113 mediu.
- **Demonstração esperada:** no validador local, com a configuração ligada só no perfil de teste, dois bots REAL donos do mesmo token: o ciclo de um deles vende só a quantidade dele, a do outro continua na carteira, e o aviso aparece em `/bots`; com a configuração padrão, o ciclo vende como hoje e o aviso não aparece.
- **Verificação integrada real:** `bash scripts/verify-checkpoint.sh` (todos os specs de ponta a ponta, inclusive os specs REAL do 261113, que provam que o comportamento padrão não mudou), mais os testes de integração do RealCycle no validador local com a configuração ligada.
- **Implantação:** sai no mesmo pipeline com a configuração desligada. Ligar a configuração é uma implantação auditada separada, nunca junto com uma mudança de código (`team.md`, Flags), e só depois da Performance Validation do 261113.

## Assumptions & Open Questions

- [assumption] O gatilho de "atraso" do ponto de controle interno da U2 é: não estar verde ao fim da primeira passada da Code Generation da U2, ou o responsável declarar o atraso. As respostas confirmadas dizem só "se esse ponto atrasar"; o responsável pode trocar esse critério no gate.
- [assumption] O nome do spec novo da fatia é decidido na Code Generation da U1 (sugestão: `e2e/paper-real-unico`), junto com o texto pronto para o `scripts/verify.sh`.
- [assumption] Se a U4 terá um spec de ponta a ponta próprio com a configuração ligada é decisão do Functional Design da U4; este plano pede só testes de integração no validador local e a suíte completa verde.
- [assumption] O cenário de carga do NFR1 pode precisar de uma opção nova no `load-test.yml`, que é um arquivo do responsável; o NFR Design da U2 decide (ver `external-dependency-map.md`).
- O NFR7 do `requirements.md` cita o volume `Comprehensive`, e o estado deste intent registra a Test Strategy `Standard` (`team.md`: cada intent segue a Test Strategy do próprio estado). O NFR Requirements de cada Unit confirma o volume com o responsável; o piso de 80% vale nos dois casos.
- As perguntas abertas dos contratos ficam com os estágios de cada Bolt: PQ-7 (B1), PQ-10 (B2), PQ-6 e PQ-8 (B3), PQ-9 (B4).
