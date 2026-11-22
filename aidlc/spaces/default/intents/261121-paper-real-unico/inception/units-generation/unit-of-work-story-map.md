# Mapa de histórias por Unit — paper-real-unico

Conversa: português (Brasil). Data: 2026-11-21.

Cada linha liga uma história à Unit, ou às Units, que implementam parte dela. Uma Unit só aparece numa linha quando implementa parte daquela história, e cada Unit da linha rastreia todos os critérios de aceite da história no seu Functional Design. Quando uma linha tem mais de uma Unit, a seção "Histórias que atravessam Units" diz qual parte cabe a cada uma.

## Sources

- `inception/user-stories/stories.md`: US1.1 a US8.1, 34 histórias, com os critérios de aceite.
- `unit-of-work.md` e `unit-of-work-dependency.md`: as Units, os componentes e o grafo.
- `units-generation-questions.md`: Q2 (o que cada Unit entrega).
- `inception/domain-design/components.md`: o componente que atende cada história.

## Mapa

| História | Unit | Directory |
|---|---|---|
| US1.1 | U1 | u1-mode-skeleton |
| US1.2 | U1 | u1-mode-skeleton |
| US1.3 | U1 | u1-mode-skeleton |
| US1.4 | U1 | u1-mode-skeleton |
| US1.5 | U1 | u1-mode-skeleton |
| US2.1 | U1 | u1-mode-skeleton |
| US2.2 | U1 | u1-mode-skeleton |
| US2.3 | U3 | u3-pnl-views |
| US2.4 | U1 | u1-mode-skeleton |
| US3.1 | U1 | u1-mode-skeleton |
| US3.2 | U1; U2 | u1-mode-skeleton; u2-real-bot-pnl |
| US3.3 | U2 | u2-real-bot-pnl |
| US3.4 | U2 | u2-real-bot-pnl |
| US3.5 | U2 | u2-real-bot-pnl |
| US3.6 | U2 | u2-real-bot-pnl |
| US3.7 | U2 | u2-real-bot-pnl |
| US3.8 | U2 | u2-real-bot-pnl |
| US3.9 | U2 | u2-real-bot-pnl |
| US3.10 | U3 | u3-pnl-views |
| US3.11 | U2 | u2-real-bot-pnl |
| US4.1 | U1; U3 | u1-mode-skeleton; u3-pnl-views |
| US4.2 | U1; U3 | u1-mode-skeleton; u3-pnl-views |
| US4.3 | U3 | u3-pnl-views |
| US4.4 | U1; U3 | u1-mode-skeleton; u3-pnl-views |
| US4.5 | U1; U3 | u1-mode-skeleton; u3-pnl-views |
| US5.1 | U2 | u2-real-bot-pnl |
| US5.2 | U2 | u2-real-bot-pnl |
| US5.3 | U1; U2 | u1-mode-skeleton; u2-real-bot-pnl |
| US5.4 | U2 | u2-real-bot-pnl |
| US6.1 | U4 | u4-own-quantity-cycle |
| US6.2 | U4 | u4-own-quantity-cycle |
| US7.1 | U3 | u3-pnl-views |
| US7.2 | U2; U3 | u2-real-bot-pnl; u3-pnl-views |
| US8.1 | U3 | u3-pnl-views |

## Histórias que atravessam Units

- **US3.2.** **U1:** a forma da resposta do P&L REAL por bot, em lamports e sem endereço de carteira (AC3.2.5), e o 404 para o bot de outra pessoa (AC3.2.6), com o cálculo por ordens do próprio bot. **U2:** o custo médio do próprio bot pelo RealCostBasis (AC3.2.1 a AC3.2.4), com os testes escritos antes do código.
- **US4.1.** **U1:** os dois blocos em `/pnl`, o teste de contrato sem total geral, a separação entre os modos e o spec de ponta a ponta (AC4.1.1 a AC4.1.5), com o AC4.1.2 em `/pnl`, na API e na lista `/bots`. **U3:** mantém o AC4.1.2 verde no painel, que ela constrói: nenhum campo, linha ou texto do painel traz um total geral dos dois modos.
- **US4.2.** **U1:** o aviso no bloco Simulado de `/pnl` (AC4.2.1) e na lista `/bots` (AC4.2.3), a ausência do aviso numa tela só REAL (AC4.2.6) e nenhum texto que prometa lucro (AC4.2.7). **U3:** o aviso no painel (AC4.2.2), no detalhe do bot simulado (AC4.2.4) e na comparação (AC4.2.5).
- **US4.4.** **U1:** contraste, texto junto da cor, rótulos com o modo e a moeda, foco e cabeçalhos em `/bots` e `/pnl` (AC4.4.1 a AC4.4.5 nessas telas). **U3:** os mesmos critérios no painel e no detalhe do bot.
- **US4.5.** **U1:** 360 px, cartões empilhados e estados distintos em `/bots` e `/pnl` (AC4.5.1 a AC4.5.3 nessas telas). **U3:** os mesmos critérios no painel e no detalhe do bot.
- **US5.3.** **U1:** a conferência PAPER (AC5.3.2), junto com o total PAPER do bloco. **U2:** a conferência REAL com a linha "Sem bot" (AC5.3.1, AC5.3.3 a AC5.3.5).
- **US7.2.** **U2:** o realizado do período e o não realizado de agora no cálculo do servidor (AC7.2.1). **U3:** a data no fuso da pessoa (AC7.2.2) e os instantes em UTC no parâmetro de período da API (AC7.2.3).

Preocupações transversais, que valem para todas as Units e não mudam o mapa:
- **Nunca somar os modos (FR4.1, NFR3).** A regra ArchUnit NEVER_SUM_MODES e o teste de contrato nascem na U1 (US4.1); cada Unit que acrescenta um campo ou uma leitura de P&L (U2 e U3) os mantém verdes.
- **Tenant e 404 (NFR3).** Toda leitura nova, em qualquer Unit, tira o tenant do token e responde 404 para o bot de outra pessoa.
- **Separação entre REAL e PAPER (NFR4).** Teste obrigatório por risco em toda mudança que toque o P&L ou o ledger (U1, U2 e U4).
- **Um único ambiente (US1.5, NFR8).** A U1 implementa a criação dos dois modos no mesmo app; nenhuma Unit acrescenta serviço AWS.
- **Piso de 80% e volume da Test Strategy (NFR7).** Vale em cada Unit, separadamente no backend e no `apps/web`.

## Ordem dentro de cada Unit

Esta é a ordem de implementação dentro de cada Unit, que respeita as dependências entre as histórias. A ordem entre as Units fica para o Delivery Planning.

- **U1 `u1-mode-skeleton`:** `mode == market` e contrato (US2.4) → elegibilidade no servidor e recusa do POST (US1.2) → passo do modo, confirmação e limites do bot REAL (US1.1, US1.3) → modo fixo (US1.4) → porta `BotDirectory` e lista única com P&L na moeda do modo (US2.1, US2.2, US3.1) → ModePnlView com os dois blocos, a primeira fatia do RealBotPnl, a regra NEVER_SUM_MODES e o teste de contrato (US3.2 parte U1, US4.1) → total e conferência PAPER (US5.3 parte U1) → aviso, acessibilidade e celular em `/bots` e `/pnl` (US4.2, US4.4, US4.5, partes U1) → spec de ponta a ponta no validador local (US4.1, US1.5).
- **U2 `u2-real-bot-pnl`:** (testes antes do código em cada passo) migração expand do slot e chave de ordem de rede → porta `RealCostMethod`, RealCostBasis e teste de paridade (US5.2) → custo médio por bot (US3.2 parte U2) → regressão da condicional (US5.1) → RealProration: liquidação, condicional e excedente (US3.3, US3.4, US3.5) → ordem no mesmo slot (US3.6) → taxas (US3.7) → preço `unknown` (US3.8) → bot pausado ou removido (US3.9) → linha "Sem bot", total REAL e conferência (US5.3 parte U2) → realizado por período e não realizado de agora (US5.4, US7.2 parte U2) → leitura em fluxo, índice e cenário de carga (US3.11).
- **U3 `u3-pnl-views`:** filtro por modo (US2.3) → escolha do período e UTC na API (US7.1, US7.2 parte U3) → parcelas rateadas no bloco REAL e detalhe do bot com a origem (US3.10) → painel com os dois totais, sem total geral (US4.3, US4.1 parte U3) → aviso, acessibilidade e celular no painel e no detalhe (US4.2, US4.4, US4.5, partes U3) → comparação (US8.1).
- **U4 `u4-own-quantity-cycle`:** (testes antes do código para venda, idempotência e falha fechada) leitura das vendas do bot ainda não assentadas e venda de uma quantidade dada no RealExecution → RealBotHoldings com orçamento de tempo e falha fechada → RealCycle com a posição própria atrás da configuração desligada (US6.1) → aviso na lista `/bots` quando ligada (US6.2).

## Verificação de cobertura

- **Todas as histórias têm Unit.** As 34 histórias de US1.1 a US8.1 aparecem no mapa, cada uma com pelo menos uma Unit. Nenhuma está sem Unit.
- **Todas as Units têm histórias.** U1 tem 15 linhas, U2 tem 14, U3 tem 10 e U4 tem 2.
- **Histórias em mais de uma Unit:** 7 (US3.2, US4.1, US4.2, US4.4, US4.5, US5.3 e US7.2).
- **Rastreabilidade.** O `traceability.json` deste estágio lista as 34 histórias com status `OK`; o alvo de cada uma é uma Unit que aparece na linha dela.

## Assumptions & Open Questions

- [assumption] US1.5 (um único ambiente) fica só na U1, porque é ela que põe os dois modos no mesmo app; a conferência de que nenhuma Unit acrescenta serviço AWS é transversal.
- [assumption] US3.11 (P&L em até 1 s) fica só na U2, onde nascem a leitura em fluxo e o índice com slot; as leituras novas da U3 rodam sobre o mesmo cálculo e o cenário de carga da CI as cobre.
