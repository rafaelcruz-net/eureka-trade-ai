# ADR-0004 — Duas fontes simuladas e o valor conhecido mais recente

- **Status:** aceito (U4 `u4-discovery`, decisões Q6 e Q7 do design funcional e Q4 dos requisitos não funcionais)
- **Regras:** BR1.2, BR1.3, BR1.4, BR1.5, BR1.6, BR1.7 e BR1.9 de [rules.md](../../aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/functional-design/rules.md)

## Contexto

A U1 tinha uma única fonte simulada. A U4 precisa mostrar o que acontece quando mais de uma fonte informa o mesmo token: qual valor exibir, quando as fontes discordam e o que fazer quando uma delas cai. Fontes reais só entram depois do G0 e do SP-1 (C-06), então o comportamento com várias fontes tem de ser provado com fontes simuladas, de forma determinística, sem rede.

## Decisão

- Há duas fontes simuladas, `sim-a` e `sim-b` (variável `MARKET_SOURCES`), atrás da mesma porta `MarketSourceAdapter` (C19), sempre com `simulated = true`.
- Os 50 primeiros tokens são comuns às duas fontes (mesma chain e mesmo mint, gerados da mesma semente). Nesses tokens a `sim-b` diverge de propósito: preço 10% maior em um a cada dez tokens (acima da tolerância de 5%), liquidez 2% maior em outro (dentro da tolerância) e holders ausentes em um terceiro. A semente é fixa (`MARKET_SIMULATED_SEED`, padrão 42), então a CI e o smoke veem sempre os mesmos casos.
- Cada campo exibido é o valor `KNOWN` mais recente entre as fontes que não estão `FAILED`; empate no horário vai para o menor `sourceId` (BR1.2). Sem nenhum `KNOWN`, o campo é `unknown` com o motivo; zero informado continua zero (BR1.9).
- A divergência compara, sem divisão e sem arredondar, `|outro - exibido| x 100` com `tolerância x |exibido|`, só entre valores não desatualizados (BR1.7).
- O estado de cada fonte (`PENDING`, `OK`, `DEGRADED`, `FAILED`) fica no banco e muda só no fim de uma coleta; reiniciar a aplicação não o muda (BR1.5). A coleta de cada fonte tem agendamento, bloqueio, tempo limite e novas tentativas próprios.
- As fontes que deixaram de ser configuradas (a fonte `simulated` da U1) ficam no banco pelo histórico, mas com `enabled = false`: não contam na lista, na saúde nem na avaliação.

## Consequências

- A lista e a avaliação continuam funcionando com uma fonte fora; só o que dependia dela degrada (BR1.3).
- O valor exibido pode vir de fontes diferentes em campos diferentes do mesmo token. A interface mostra a fonte e o horário de cada valor e, quando há divergência, as duas origens.
- A tolerância é uma só para os quatro campos no beta; o modelo (`DivergencePolicy`) já aceita uma por campo.
- Adaptadores reais futuros só precisam implementar a porta; a validação na porta e a resolução entre fontes não mudam.

## Alternativas rejeitadas

| Alternativa | Por que foi rejeitada |
|---|---|
| Média ou mediana das fontes | Cria um valor que nenhuma fonte informou e esconde a divergência que a pessoa precisa ver (Q6). |
| Fonte preferida fixa, com a outra só como reserva | Ignora um valor mais novo da outra fonte; a regra do mais recente é mais simples de explicar e de testar. |
| Uma só fonte simulada com divergência artificial | Não exercita casamento por chain + mint, falha isolada de uma fonte nem o estado por fonte. |
