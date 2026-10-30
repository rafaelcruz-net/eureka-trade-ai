# Conferência de fase — Inception → Construction

**Resultado: APROVADA** — nenhum item `GAP` ou `ORPHAN`, nenhum alvo inválido e nenhum ID upstream ausente. Os itens `Deferred` e `N/A` têm justificativa e destino.

Fontes conferidas:

- `inception/user-stories/traceability.json` (requisitos → histórias)
- `inception/domain-design/traceability.json` (histórias → componentes)
- `inception/units-generation/traceability.json` (histórias → Units)

O Contract Design não produz `traceability.json` (é dono de contratos, não de cobertura de requisitos) e não entra nesta conferência.

## Requisitos → histórias

| Situação | IDs | Destino ou justificativa |
|---|---|---|
| OK | FR1 a FR13, FR15.2, NFR1, NFR4 a NFR8, NFR14 | Histórias de [stories.md](../inception/user-stories/stories.md) |
| Deferred | FR14.1 a FR14.4, FR15.1 | Investigações SP-1 a SP-5, acompanhadas em [external-dependency-map.md](../inception/delivery-planning/external-dependency-map.md) |
| Deferred | NFR2, NFR3, NFR9, NFR10 | NFR Requirements na Construction |
| Deferred | NFR11 | Mockups refinados (acessibilidade) e critérios CT-UX |
| Deferred | NFR12, NFR13 | Build and Test |
| N/A | FR15.3 | Bloqueado até G2/G3, revisão independente, análise jurídica e aprovação explícita |
| N/A (reverso) | US1.5, US4.10, US7.4 | Histórias derivadas de wireframes e da persona P3, com origem registrada |

## Histórias → componentes

Todas as histórias têm componente dono em [components.md](../inception/domain-design/components.md); nenhuma linha `GAP` ou `ORPHAN`.

## Histórias → Units

As 32 histórias têm Unit atribuída em [unit-of-work-story-map.md](../inception/units-generation/unit-of-work-story-map.md); nenhuma Unit sem histórias. Cinco histórias se repartem entre duas Units (US4.6, US4.7, US4.12, US7.1, US7.2); o `traceability.json` aponta a Unit principal e o mapa traz as duas.

## Pontos para a Construction

- Achados aceitos no Contract Design (R-10 a R-19) têm Bolt dono em [risk-and-sequencing-rationale.md](../inception/delivery-planning/risk-and-sequencing-rationale.md).
- A decisão de moeda e escala (OQ-3) precisa sair antes do design funcional do B4.
- [ ] Aprovação humana desta conferência (no gate do Delivery Planning)
