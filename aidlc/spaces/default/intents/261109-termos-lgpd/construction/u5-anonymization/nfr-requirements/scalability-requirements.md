# Requisitos de escalabilidade — U5 `u5-anonymization`

Conversa: português (Brasil). Fontes: `construction/u5-anonymization/functional-design/` (BR5.5, BR5.6).

A concorrência e a ordem de travas são integridade e ficam em `reliability-requirements.md` (NFR5.5 e NFR5.6).

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR3.3 | Volume: anonimizações raras (algumas por mês no beta) | projeção; passa quando NFR3.1 passa |
