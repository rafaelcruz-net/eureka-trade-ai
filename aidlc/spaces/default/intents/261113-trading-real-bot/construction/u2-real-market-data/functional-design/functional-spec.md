# Especificação funcional — U2 `u2-real-market-data`

Conversa: português (Brasil). Esta Unit implementa a parte de dados da US6.3 de `inception/user-stories/stories.md` (a recusa da ordem é da U3), conforme `inception/units-generation/unit-of-work-story-map.md` e `inception/units-generation/unit-of-work.md`. Outras fontes:
- `inception/requirements-analysis/requirements.md` (FR1);
- `inception/domain-design/components.md` (MarketData);
- `inception/contract-design/contract-summary.md` (M4, X2);
- o código atual do módulo `marketdata`;
- `functional-design-questions.md` (Q1 a Q10).

Este arquivo é a fonte da verdade dos fluxos. O resumo de regras no fim é derivado de `rules.md`.

## Escopo

**Entra:**
- adaptador `coingecko` atrás da porta `MarketSourceAdapter`;
- `coingecko` em `KNOWN_SOURCES`;
- três ritmos de chamada com cota e orçamento;
- valores inválidos como `NOT_PROVIDED` com métrica própria;
- `stale` por campo (2 h em holders e criação);
- `activeSourceKind()` e `attribution()` na M4;
- porta `WatchedTokens` (vazia na U2);
- recusa de subida com fonte simulada;
- atribuição na API do scanner e na tela.

**Não entra:**
- a regra de 60 s e a recusa da ordem (U3);
- a marcação do P&L REAL e as posições observadas (U4).

## Fluxos

### F1 — Ciclos de coleta da CoinGecko (BR1.1, BR1.3)

1. **Descoberta, a cada 5 min:** pools novos e em alta da rede `solana` (2 chamadas), sem repetidos, até 50 tokens, mais os tokens de `WatchedTokens`. O conjunto ativo fica na memória do worker.
2. **Preços, a cada 30 s:** preço, liquidez e volume dos tokens ativos, em lotes de até 30 (2 chamadas com 50 tokens).
3. **Detalhes, em rodízio:** holders e pool principal (criação), 2 chamadas por token. O limite é de 2 chamadas por minuto, começando pelos tokens com detalhe mais antigo. Cada token é renovado a cada 60 min, o que dá 100 chamadas por hora com 50 tokens. Descoberta e detalhes acontecem dentro da rodada de preços (`fetch`), quando o tempo e o orçamento permitem. O adaptador guarda o estado na memória do worker.
4. **Cota:** toda chamada passa pelo limitador (250 por minuto) e pelo orçamento mensal (450 mil por padrão, alerta a 80%). Acima do orçamento, os detalhes param e os preços continuam.
5. **Chamada:** tempo limite de 5 s, uma repetição em leitura. O 429 espera o `Retry-After`.
6. **Gravação:** cada campo vira `Known` ou `Unknown` (BR1.2), com `observedAt` igual ao horário da busca, e é gravado pelo coletor que já existe.

### F2 — Estados da fonte (BR1.9)

| Situação no ciclo | Estado | Leitura |
|---|---|---|
| todos os preços dos tokens ativos atualizados | OK | valores novos |
| parte dos preços atualizada; cota ou 429 longo cortou a rodada; primeira rodada sem nenhum preço | DEGRADED | valores antigos com o horário deles |
| segunda rodada seguida sem nenhum preço (erro, tempo esgotado, malformado) | FAILED | `FieldResolver` devolve `Unknown SOURCE_UNAVAILABLE` |

Descoberta e detalhes que não rodam numa rodada não mudam o estado. Os campos não renovados não vêm no `fetch` e mantêm a observação anterior.
| antes da primeira coleta | PENDING | `Unknown NOT_YET_OBSERVED` |

Nunca há volta para a fonte simulada.

### F3 — Leitura pela M4 (BR1.4, BR1.5, BR1.7)

- **Preço (`referencePrice`):** é o `quote` do contrato. Devolve `Known` com `observedAt` e `source.simulated`, ou `Unknown`.
- **`activeSourceKind()`:** devolve REAL ou SIMULATED. A U3 recusa a ordem com SIMULATED ou com idade acima de 60 s pelo `observedAt`.
- **`attribution()`:** devolve o texto e o link da fonte real ativa.
- **Fonte única:** é derivada de `ResolvedField`, com uma origem e sem divergência.

### F4 — Subida (BR1.6)

Com `memetrade.real.enabled=true`, fora dos perfis `test` e `local`, a API e o worker não sobem se `MARKET_SOURCES` contiver `sim-a` ou `sim-b`.

### F5 — Atribuição (BR1.8)

A API do scanner e a do detalhe do token passam a trazer a atribuição. A tela mostra "Dados: CoinGecko" com o link, ou "fonte simulada ativa". Carregando, erro e `unknown` continuam como estados distintos.

## Resumo das regras (derivado de rules.md)

| Grupo | Regras |
|---|---|
| Coleta e cota | BR1.1, BR1.2, BR1.3 |
| Leitura e fonte | BR1.4, BR1.5, BR1.6, BR1.7 |
| Tela | BR1.8 |
| Falha e segredo | BR1.9, BR1.10 |

## Cenários de teste

**Valores (BR1.2):**
- Campo ausente vira `NOT_PROVIDED`.
- Negativo, ilegível ou holders fracionário vira `NOT_PROVIDED` e conta em `marketdata_invalid_values_total`.
- Holders com 90 min não ficam `stale`; preço com 6 min fica.
- Uma rodada sem preço deixa a fonte DEGRADED; duas seguidas, FAILED.
- Zero informado vira `Known 0`.
- O valor é lido como decimal exato, sem perda.

**Coleta e cota:**
- Repetidos entre pools novos e em alta entram uma vez.
- Respeita o limite de 50 mais os observados.
- Os três ritmos e o limitador de 250 por minuto.
- Orçamento esgotado para os detalhes primeiro.
- 429 com `Retry-After`.
- Tempo limite com uma repetição.

**Estados da fonte:**
- Atualização parcial vira DEGRADED.
- Rodada sem nenhum token vira FAILED e `Unknown SOURCE_UNAVAILABLE`.

**Interface e subida:**
- `activeSourceKind()` devolve SIMULATED com `sim-a`.
- A subida é recusada com REAL ligado e fonte simulada fora de `test` e `local`.

**Segurança e isolamento:**
- A chave nunca aparece em log.
- Todos os testes usam WireMock com respostas sintéticas, sem rede.
