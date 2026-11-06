# Requisitos de desempenho — U8 Administração e feedback

Base: [respostas](nfr-requirements-questions.md) Q1, Q2 e Q6, [desenho funcional](../functional-design/functional-spec.md) e NFR2 e NFR3 de `requirements.md`. Ambiente de referência: o Docker Compose local e a CI em Linux.

## Requisitos

| ID | Requisito | Meta e verificação | Origem |
|---|---|---|---|
| NFR2.1 | Resposta do acionamento | O `PUT /api/v1/admin/kill-switch` com `state: ON` e 60 bots ativos (20 pessoas com 3 bots cada) responde em até 5 s (p95 de 20 execuções). Medido em teste de integração na CI | Q1, NFR2, BR2.1 |
| NFR2.2 | Pausa completa | Em até 35 s depois da confirmação do ON, os 60 bots têm o motivo SYSTEM. Isso vale também no pior caso: queda simulada entre gravar o estado e chamar a U5, ou exceção na chamada. Medido em teste de integração com relógio controlado | Q1, BR2.5, AC7.2.2 |
| NFR2.3 | Espera pela trava de aplicação | A requisição espera a trava de aplicação por até 10 s. Passado esse tempo, responde 200 com `reconcileNeeded = true`, e o trabalho conclui (NFR2.2). Teste com a trava presa por outro processo | Q1, BR2.8 |
| NFR3.1 | Telas do administrador | Estado, convites, kill switch e feedback carregam com p95 ≤ 2 s e 20 usuários simultâneos (premissa do NFR3) | NFR3, Q2 |
| NFR3.2 | Estado do sistema | `GET /api/v1/admin/system-status` com p95 ≤ 1 s. Cada consulta a outra Unit tem limite de 500 ms; acima disso, a parte volta como `UNKNOWN` com o motivo `TIMEOUT`. Medido no smoke e num teste de integração com uma parte lenta de propósito | Q2, BR3.3 |
| NFR3.3 | Envio de feedback | `POST /api/v1/feedback` com p95 ≤ 500 ms, inclusive com 20 envios simultâneos de pessoas diferentes | Q2, Q6 |

## Como medir

As metas são medidas em testes de integração na CI (Q6), sem cenário de carga novo. Os testes são:
- pausa de 60 bots cronometrada;
- 20 envios de feedback simultâneos;
- estado do sistema com uma parte lenta.

O tempo do estado do sistema também aparece no smoke `e2e/u8-admin-feedback`.

## Orçamento do estado do sistema

| Parte | Origem | Limite |
|---|---|---|
| Convidados e convites | U3 `IdentityAdministration.counts` | 500 ms |
| Bots por estado | U5 `BotAdministration.countByState` | 500 ms |
| Kill switch | Tabela da U8 | 100 ms |
| Fontes | U4, interface de dados de mercado | 500 ms |
| Atraso da trilha | U2 | 500 ms |
| Recusas de hoje | U6 `countRejectedSince` | 500 ms |

As partes são consultadas em paralelo, para caber no p95 de 1 s.
