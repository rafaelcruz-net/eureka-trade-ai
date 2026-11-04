# Requisitos de desempenho — U3 Convites, contas e sessões

Requisitos de tempo de resposta da Unit `u3-identity-access`, derivados do NFR3 de [requirements.md](../../../inception/requirements-analysis/requirements.md), dos fluxos F1 a F9 de [functional-spec.md](../functional-design/functional-spec.md) e da resposta Q1 de [nfr-requirements-questions.md](nfr-requirements-questions.md). As metas valem no ambiente local de referência (Docker Compose), como na U1, e são revistas com a hospedagem.

## Requisitos

| ID | Requisito | Meta | Carga | Medição | Origem |
|---|---|---|---|---|---|
| NFR3.1 | Verificação com convite | p95 ≤ 500 ms | 20 verificações simultâneas, cada uma com um convite e uma carteira diferentes | Teste de carga leve medindo o tempo do servidor | NFR3, Q1 |
| NFR3.2 | Consulta do convite e tela Conta | p95 ≤ 300 ms em `GET /api/v1/invites/{code}` e `GET /api/v1/account` | 20 usuários simultâneos, uma ação a cada 2 s, por 5 minutos | Mesmo teste de carga da U1, ampliado | NFR3, Q1 |
| NFR3.3 | Rotas administrativas de convite | p95 ≤ 300 ms na emissão, na lista (página de até 100) e na revogação | 1 administrador, 10 ações seguidas | Teste de integração com marcação de tempo | NFR3 |
| NFR3.4 | Telas de entrada com convite e Conta | p95 ≤ 2 s do pedido da página até o conteúdo visível (termos ou dados da conta) | 20 usuários simultâneos | Teste de ponta a ponta com marcação de tempo no navegador | NFR3 |

O tempo da assinatura na carteira (Phantom ou Solflare) depende da pessoa e fica fora das metas.

## Orçamento da verificação com convite (p95)

| Etapa | Orçamento |
|---|---|
| Conferência da assinatura e consumo do desafio (U1) | 60 ms |
| Bloqueio do convite e conferências (BR3.2) | 60 ms |
| Criação de pessoa, carteira, aceite, convite usado e evento | 120 ms |
| Abertura da sessão (U1) | 60 ms |
| Confirmação da transação | 50 ms |
| Serialização e folga | 150 ms |
| **Total** | **500 ms** |

Concorrência no mesmo convite (BR3.4) serializa as verificações pelo bloqueio da linha; esse caso não entra na meta, porque uma das duas sempre é recusada.
