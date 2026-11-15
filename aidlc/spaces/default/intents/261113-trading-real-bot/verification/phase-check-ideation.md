# Verificação de fase — Ideation → Inception (Trading REAL)

Conversa: português (Brasil). Data: 2026-11-13.

## Consistência intenção → escopo → backlog

| Item da intenção (intent-statement) | No escopo (scope-document) | No backlog (intent-backlog) | Situação |
|---|---|---|---|
| Carteira do bot com teto, chave no KMS, saque só para a principal | PB-02 | PB-02 | OK |
| Execução pela Jupiter | PB-03 | PB-03 | OK |
| Ledger REAL separado e reconciliado | PB-04 | PB-04 | OK |
| Limites por ordem e por dia, kill switch | PB-05 | PB-05 | OK |
| Modos (Assinada padrão, saídas, total) e troca | PB-06, PB-07, PB-08 | PB-06, PB-07, PB-08 | OK |
| Liberação: devnet, fundador, até 20 convidados, 24 horas | PB-09 | PB-09 | OK |

## Lastro de viabilidade de cada item do escopo

| Item | Viabilidade (feasibility-assessment, constraint-register) | Situação |
|---|---|---|
| PB-01 Dados reais | TR-C06; CoinGecko paga | OK, condicionado à contratação |
| PB-02 Carteira do bot | TR-C02, TR-C09; Ed25519 no KMS | OK, condicionado à prova |
| PB-03 Executor | TR-C03, TR-C05, TR-C07; validador clonado | OK, condicionado à prova |
| PB-04 Ledger e reconciliação | TR-C01 | OK |
| PB-05 Barreira | TR-C13 | OK |
| PB-06, PB-07 Modos e termos | TR-C14, TR-C22 | OK |
| PB-08 Saídas automáticas | ordens condicionais da Jupiter | OK (Should) |
| PB-09 Liberação | TR-C15 | OK |
| PB-10 Exportação fiscal | TR-C23 | OK (Should) |

## Avisos

- Três provas técnicas condicionam a continuidade (Ed25519 no KMS, swap no validador clonado, CoinGecko contratada).
- Regras de `project.md` e `team.md` sobre o REAL precisam mudar pelo registro do AI-DLC antes da Construction.

## Resultado

Sem lacunas nem contradições entre intenção, escopo e backlog.

- [ ] Aprovado pelo fundador no gate de Aprovação e Passagem
