# Verificação de fase — Inception → Construction (paper-real-unico)

**Veredito: PASS**

Conversa: português (Brasil). Data: 2026-11-21.

Nenhum achado em aberto: nenhum `GAP`, nenhum `ORPHAN`, nenhum destino inválido e nenhum ID de origem sem cobertura nos três arquivos de rastreabilidade da Inception. A Construction pode começar pelo primeiro Bolt (uma passada de construção que termina em algo que roda), o B1 = U1.

## Sources

- `inception/user-stories/traceability.json` (requisitos → histórias).
- `inception/domain-design/traceability.json` (histórias → componentes e entidades do catálogo).
- `inception/units-generation/traceability.json` (histórias → Units).
- Para conferir os IDs e os destinos: `inception/requirements-analysis/requirements.md` (FR1.1 a FR8.1, NFR1 a NFR8), `inception/user-stories/stories.md` (US1.1 a US8.1, 34 histórias), `inception/domain-design/components.md` (Component Summary e Entity Ownership) e `inception/units-generation/unit-of-work.md` e `unit-of-work-story-map.md` (U1 a U4).
- O Contract Design não produz `traceability.json` e não entra nesta verificação.

## Como foi conferido

Para cada arquivo:
1. todo ID de `upstream_ids` tem uma linha em `coverage`, e não há linha de cobertura fora de `upstream_ids`;
2. todo ID de `upstream_ids` existe no artefato de origem (os FR e NFR no `requirements.md`; as histórias no `stories.md`), e todo FR e NFR definido no `requirements.md` e toda história do `stories.md` aparece em `upstream_ids`;
3. todo destino existe no artefato de destino: as histórias no `stories.md`; os componentes e entidades no `components.md` (Component Summary ou Entity Ownership); as Units U1 a U4 no `unit-of-work.md`;
4. o status de cada linha;
5. órfãos: toda história do `stories.md` é destino de pelo menos um requisito.

## Resumo

| Arquivo | IDs de origem | Linhas de cobertura | OK | Deferred | GAP | ORPHAN | Destino inválido | IDs sem cobertura |
|---|---|---|---|---|---|---|---|---|
| user-stories (requisitos → histórias) | 39 | 39 | 39 | 0 | 0 | 0 | 0 | 0 |
| domain-design (histórias → componentes) | 34 | 34 | 34 | 0 | 0 | 0 | 0 | 0 |
| units-generation (histórias → Units) | 34 | 34 | 34 | 0 | 0 | 0 | 0 | 0 |

Histórias sem requisito de origem (órfãs): nenhuma. Requisitos definidos fora de `upstream_ids`: nenhum.

## Tabelas consolidadas

### user-stories (requisitos → histórias)

| ID | Status | Destino |
|---|---|---|
| FR1.1 | OK | US1.1 |
| FR1.2 | OK | US1.2 |
| FR1.3 | OK | US1.3 |
| FR1.4 | OK | US1.4 |
| FR2.1 | OK | US2.1 |
| FR2.2 | OK | US2.2 |
| FR2.3 | OK | US2.3 |
| FR2.4 | OK | US2.4 |
| FR3.1 | OK | US3.1 |
| FR3.2 | OK | US3.2 |
| FR3.3 | OK | US3.3, US3.4 |
| FR3.4 | OK | US3.5 |
| FR3.5 | OK | US3.2, US3.8 |
| FR3.6 | OK | US3.10 |
| FR3.7 | OK | US3.3, US3.4, US3.5 |
| FR3.8 | OK | US3.6 |
| FR3.9 | OK | US3.7 |
| FR3.10 | OK | US3.8 |
| FR3.11 | OK | US3.9 |
| FR4.1 | OK | US4.1 |
| FR4.2 | OK | US4.2 |
| FR4.3 | OK | US4.3 |
| FR5.1 | OK | US5.1 |
| FR5.2 | OK | US5.2 |
| FR5.3 | OK | US5.3, US5.4 |
| FR5.4 | OK | US3.3, US3.4, US3.5, US3.6, US3.7, US3.8, US3.9, US5.1 |
| FR6.1 | OK | US6.1 |
| FR6.2 | OK | US6.2 |
| FR7.1 | OK | US7.1 |
| FR7.2 | OK | US7.2 |
| FR8.1 | OK | US8.1 |
| NFR1 | OK | US3.11 |
| NFR2 | OK | US3.2, US3.3, US3.4, US3.5, US5.3, US5.4 |
| NFR3 | OK | US3.1, US3.2, US4.1 |
| NFR4 | OK | US2.2, US4.1 |
| NFR5 | OK | US4.4 |
| NFR6 | OK | US4.5 |
| NFR7 | OK | US4.1 |
| NFR8 | OK | US1.5 |

### domain-design (histórias → componentes e entidades)

| ID | Status | Destino |
|---|---|---|
| US1.1 | OK | ModeStep |
| US1.2 | OK | RealCreationEligibility |
| US1.3 | OK | RealConfirmDialog |
| US1.4 | OK | Bot (entidade de Bots) |
| US1.5 | OK | Bots |
| US2.1 | OK | Bots |
| US2.2 | OK | ModePnlView |
| US2.3 | OK | BotModeFilter |
| US2.4 | OK | Bot (entidade de Bots) |
| US3.1 | OK | PaperPnl |
| US3.2 | OK | RealBotPnl |
| US3.3 | OK | RealProration |
| US3.4 | OK | RealProration |
| US3.5 | OK | RealProration |
| US3.6 | OK | RealBotPnl |
| US3.7 | OK | RealBotPnl |
| US3.8 | OK | RealBotPnlLine (entidade de RealBotPnl) |
| US3.9 | OK | RealBotPnl |
| US3.10 | OK | BotPnlLines |
| US3.11 | OK | RealBotPnl |
| US4.1 | OK | ModePnlView |
| US4.2 | OK | PnlModeBlock |
| US4.3 | OK | PnlModeBlock |
| US4.4 | OK | PnlModeBlock |
| US4.5 | OK | PnlModeBlock |
| US5.1 | OK | RealBotPnl |
| US5.2 | OK | RealCostBasis |
| US5.3 | OK | RealModeTotal (entidade de RealBotPnl) |
| US5.4 | OK | RealModeTotal (entidade de RealBotPnl) |
| US6.1 | OK | RealCycle |
| US6.2 | OK | RealCycle |
| US7.1 | OK | PeriodSelector |
| US7.2 | OK | PnlPeriod (entidade de ModePnlView) |
| US8.1 | OK | ComparisonView |

### units-generation (histórias → Units)

| ID | Status | Destino |
|---|---|---|
| US1.1 | OK | U1 |
| US1.2 | OK | U1 |
| US1.3 | OK | U1 |
| US1.4 | OK | U1 |
| US1.5 | OK | U1 |
| US2.1 | OK | U1 |
| US2.2 | OK | U1 |
| US2.3 | OK | U3 |
| US2.4 | OK | U1 |
| US3.1 | OK | U1 |
| US3.2 | OK | U2 |
| US3.3 | OK | U2 |
| US3.4 | OK | U2 |
| US3.5 | OK | U2 |
| US3.6 | OK | U2 |
| US3.7 | OK | U2 |
| US3.8 | OK | U2 |
| US3.9 | OK | U2 |
| US3.10 | OK | U3 |
| US3.11 | OK | U2 |
| US4.1 | OK | U1 |
| US4.2 | OK | U1 |
| US4.3 | OK | U3 |
| US4.4 | OK | U1 |
| US4.5 | OK | U1 |
| US5.1 | OK | U2 |
| US5.2 | OK | U2 |
| US5.3 | OK | U2 |
| US5.4 | OK | U2 |
| US6.1 | OK | U4 |
| US6.2 | OK | U4 |
| US7.1 | OK | U3 |
| US7.2 | OK | U3 |
| US8.1 | OK | U3 |

## Observações que não são achados

- **Histórias que atravessam Units.** O `units-generation/traceability.json` aponta uma Unit por história (a dona principal). O `unit-of-work-story-map.md` divide sete histórias entre duas Units: US3.2 (U1; U2), US4.1, US4.2, US4.4 e US4.5 (U1; U3), US5.3 (U1; U2) e US7.2 (U2; U3). A Unit do arquivo de rastreabilidade é sempre uma das Units do mapa, então não há destino inválido. O Functional Design de cada Unit rastreia todos os critérios de aceite das histórias da sua linha no mapa.
- **Decisões do Contract Design depois do Domain Design.** PQ-2 e PQ-4 mudaram o slot para a tabela lateral `real_entry_slot`, sem preenchimento das linhas antigas, e PQ-5 mudou a recusa de elegibilidade para 409. O ADR-011 e o ADR-005 são alinhados no Functional Design da U2 e da U1. Isso não muda nenhuma linha de rastreabilidade.
- **Volume de testes.** O NFR7 cita o volume `Comprehensive`, e o estado deste intent registra a Test Strategy `Standard` (`team.md`). O NFR Requirements de cada Unit confirma o volume com o responsável; a linha NFR7 → US4.1 não muda.

## Condições para começar a Construction

- Aprovação do comando de verificação dos pontos de controle (`bash scripts/verify-checkpoint.sh`) numa pergunta própria ao responsável, no fim do Delivery Planning.
- O ponto de controle do B1 depende do spec novo acrescentado pelo responsável ao `scripts/verify.sh` (`inception/delivery-planning/external-dependency-map.md`, D2).
- O B4 depende da Performance Validation do intent 261113 (D1).

## Assumptions & Open Questions

- [assumption] As entidades do Entity Ownership do `components.md` (Bot, RealBotPnlLine, RealModeTotal, PnlPeriod) são destinos válidos do Domain Design, porque fazem parte do catálogo e têm um componente dono.
