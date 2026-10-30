# Mapa de histórias por Unit — MemeTrade Analytics (beta PAPER por convite)

Distribuição das 32 histórias de [stories.md](../user-stories/stories.md) (29 Must e 3 Should; o resumo de prioridade daquele documento diz 30 Must, mas a contagem das histórias listadas dá 29) entre as 9 Units de [unit-of-work.md](unit-of-work.md), com o grafo em [unit-of-work-dependency.md](unit-of-work-dependency.md). Os requisitos de cada história estão em [requirements.md](../requirements-analysis/requirements.md); os componentes e as decisões que justificam o recorte, em [components.md](../domain-design/components.md) e [decisions.md](../domain-design/decisions.md).

Cada Unit nomeada numa linha rastreia todos os critérios de aceite daquela história no seu Functional Design. Quando duas Units aparecem na mesma linha, cada uma implementa a parte da história que cabe ao seu componente, e as duas cobrem os critérios dessa parte.

## Mapa

| História | Unit ID | Directory | Prioridade | Observação |
|---|---|---|---|---|
| US1.1 | U3 | u3-identity-access | Must | Convite, termos e criação de conta |
| US1.2 | U1 | u1-walking-skeleton | Must | Login por carteira no esqueleto (Q8) |
| US1.3 | U3 | u3-identity-access | Must | Expiração de sessão e saída |
| US1.4 | U1 | u1-walking-skeleton | Must | Habilitadora entregue com US1.2; cada Unit seguinte cumpre DP-1 |
| US1.5 | U3 | u3-identity-access | Should | Tela Conta (Q11) |
| US2.1 | U1 | u1-walking-skeleton | Must | Fatia fina do `team.md` |
| US2.2 | U4 | u4-discovery | Must | Filtros combinados |
| US2.3 | U4 | u4-discovery | Must | Desatualizado e falha de fonte |
| US2.4 | U4 | u4-discovery | Must | Divergência; tolerância e fontes reais dependem de SP-1 (Q12) |
| US3.1 | U4 | u4-discovery | Must | Indicador de risco e painel lateral |
| US3.2 | U4 | u4-discovery | Must | Cobertura insuficiente e textos proibidos |
| US4.1 | U5 | u5-bots-decision | Must | Estratégia e tokens |
| US4.2 | U5 | u5-bots-decision | Must | Limites e saldo virtual |
| US4.3 | U5 | u5-bots-decision | Must | Revisar e iniciar |
| US4.4 | U5 | u5-bots-decision | Must | Lista e transições |
| US4.5 | U5 | u5-bots-decision | Must | Ciclo e decisões determinísticas |
| US4.6 | U6; U7 | u6-risk-control; u7-ledger-pnl | Must | U6: barreira e recusas; U7: idempotência e unicidade no banco |
| US4.7 | U5; U6 | u5-bots-decision; u6-risk-control | Must | U6: verificação de perda diária e drawdown; U5: motivos de pausa e meia-noite UTC |
| US4.8 | U5 | u5-bots-decision | Must | Pausar e retomar |
| US4.9 | U5 | u5-bots-decision | Should | Edição de limites de bot ativo (Q11) |
| US4.10 | U5 | u5-bots-decision | Must | Tela do bot com uso do dia, operações e recusas |
| US4.11 | U7 | u7-ledger-pnl | Must | Preenchimento simulado e gravação atômica |
| US4.12 | U5; U7 | u5-bots-decision; u7-ledger-pnl | Must | U5: confirmação e estados; U7: fechamento e fechamento pendente |
| US5.1 | U7 | u7-ledger-pnl | Must | P&L por token, bot e período |
| US5.2 | U7 | u7-ledger-pnl | Must | Ledger e reconciliação |
| US5.3 | U9 | u9-dashboard | Must | Painel |
| US5.4 | U9 | u9-dashboard | Must | Painel ao vivo |
| US6.1 | U8 | u8-admin-feedback | Should | Feedback (Q11) |
| US7.1 | U3; U8 | u3-identity-access; u8-admin-feedback | Must | U3: regras e rotas de convite; U8: telas administrativas |
| US7.2 | U5; U8 | u5-bots-decision; u8-admin-feedback | Must | U8: kill switch e telas; U5: motivo de pausa "sistema" e recusa de iniciar ou retomar |
| US7.3 | U2 | u2-audit-trail | Must | Trilha, mascaramento e varredura |
| US7.4 | U8 | u8-admin-feedback | Must | Estado do sistema só com contagens |

## Histórias que atravessam Units

| História | Units | Como se repartem |
|---|---|---|
| US4.6 | U6, U7 | A tabela de exemplos e as recusas ficam na barreira (U6); a operação única com chave de idempotência e a recusa de segunda linha pelo banco ficam no ledger (U7) |
| US4.7 | U5, U6 | A barreira detecta perda diária e drawdown e recusa a ordem (U6); a gestão de bots aplica e remove o motivo de pausa e trata a meia-noite UTC (U5) |
| US4.12 | U5, U7 | A gestão de bots confirma e muda o estado (U5); o ledger fecha as posições e conclui o fechamento pendente uma única vez (U7) |
| US7.1 | U3, U8 | Regras e rotas de convite em IdentityAccess (U3); telas administrativas no app do administrador (U8) |
| US7.2 | U5, U8 | Kill switch e telas na Administração (U8); motivo de pausa "sistema" e recusas no bot (U5), conforme ADR-007 |

Critérios transversais da definição de pronto (DP-1 a DP-4 e CT-UX-1 a CT-UX-9 em [stories.md](../user-stories/stories.md)) valem para toda Unit que toca o assunto e não geram linhas próprias.

## Ordem de implementação dentro de cada Unit

Ordem das histórias dentro de cada Unit, seguindo as dependências entre histórias de [stories.md](../user-stories/stories.md). A ordem entre Units fica para o planejamento de entrega.

| Unit | Ordem interna |
|---|---|
| U1 | Fundação do repositório e script de verificação → US2.1 (lista mínima com fonte simulada) → US1.2 (login e administrador inicial, com auditoria mínima) → US1.4 (isolamento nas rotas existentes) |
| U2 | US7.3 |
| U3 | US7.1 (regras de convite) → US1.1 → US1.3 → US1.5 |
| U4 | US2.3 → US3.1 → US2.2 → US3.2 → US2.4 |
| U5 | US4.1 → US4.2 → US4.3 → US4.4 → US4.5 → US4.8 → US4.7 (parte de pausa) → US4.12 (parte de estado) → US4.10 → US7.2 (parte de pausa de sistema) → US4.9 |
| U6 | US4.6 (barreira) → US4.7 (perda diária e drawdown) |
| U7 | US4.11 → US4.6 (idempotência) → US5.1 → US5.2 → US4.12 (fechamento) |
| U8 | US7.1 (telas) → US7.2 → US7.4 → US6.1 |
| U9 | US5.3 → US5.4 |

## Verificação de cobertura

- Histórias enumeradas: 32 (US1.1 a US1.5, US2.1 a US2.4, US3.1 e US3.2, US4.1 a US4.12, US5.1 a US5.4, US6.1, US7.1 a US7.4). Todas têm ao menos uma Unit.
- Units com histórias: U1 (3), U2 (1), U3 (4), U4 (5), U5 (11), U6 (2), U7 (5), U8 (4), U9 (2). Nenhuma Unit sem linha.
- Fora do mapa por não serem histórias: SP-1 a SP-5 (FR14.1 a FR14.4, FR15.1), acompanhados no planejamento de entrega (Q12), e FR15.3, bloqueado por gate.
- Testes obrigatórios por risco do `team.md`: isolamento (U1 e todas as Units por DP-1), REAL e PAPER (U5, U7), deduplicação (U5, U7), limites (U6), `unknown` (U1, U4, U5, U7), pausa (U5, U8), ledger sem edição (U7), P&L determinístico (U7), REAL desligado (U5), login sem reutilização (U1).
