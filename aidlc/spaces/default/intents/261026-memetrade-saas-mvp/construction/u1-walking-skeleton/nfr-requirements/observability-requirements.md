# Requisitos de observabilidade — U1 Esqueleto de ponta a ponta

Observabilidade da Unit `u1-walking-skeleton` para os fluxos de [functional-spec.md](../functional-design/functional-spec.md) e as regras de [rules.md](../functional-design/rules.md) (BR4.3), a partir do NFR7 e do NFR9 de [requirements.md](../../../inception/requirements-analysis/requirements.md) e das regras comuns de [contract-summary.md](../../../inception/contract-design/contract-summary.md) (`correlationId`). Decisão em [nfr-requirements-questions.md](nfr-requirements-questions.md) (Q6).

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR9.1 | Métrica de saúde e de erro por componente | Cada componente da U1 (IdentityAccess, MarketData, Scanner, AuditTrail) expõe saúde e contagem de erros; a API expõe taxa de requisições, erros e latência por rota | NFR9, Q6 |
| NFR9.2 | `correlationId` em toda requisição | 100% dos logs de requisição trazem `correlationId`; o valor volta no cabeçalho e no `ProblemDetail` | NFR9, C1 |
| NFR9.3 | Logs estruturados em JSON | Campos mínimos: horário UTC, nível, componente, mensagem, `correlationId`; nada de segredo, token, nonce, assinatura ou cabeçalho `Authorization` | NFR9, BR4.3 |
| NFR9.4 | Atraso da trilha de auditoria visível | Métrica de entradas pendentes na tabela de saída e idade da mais antiga | C9 |
| NFR9.5 | Estado da fonte de mercado visível | Métrica com o estado da fonte simulada e a idade da última coleta | NFR1 |
| NFR7.3 | Varredura de logs nos testes | Teste que roda a suíte, coleta os logs e falha se encontrar endereço completo, token, nonce ou assinatura | NFR7 |

## Métricas da U1

| Métrica | Tipo | Uso |
|---|---|---|
| Requisições por rota e status | Contador | Taxa e erros (RED) |
| Latência por rota | Histograma | Conferir NFR3.1 |
| Logins aceitos e recusados por motivo | Contador | Ataques e erros de assinatura |
| Respostas `429` | Contador | Limite de tentativas |
| Pendências da tabela de saída e idade | Medidor | Atraso da auditoria |
| Estado e idade da fonte simulada | Medidor | Frescor dos dados |

## Logs

| Nível | Quando | Retenção local |
|---|---|---|
| ERROR | Falha não recuperável (banco indisponível, relay falhando) | 7 dias |
| WARN | Falha recuperável (coleta falhou, `429` em volume) | 7 dias |
| INFO | Início e fim de operações de negócio (login aceito ou recusado, coleta concluída) | 7 dias |
| DEBUG | Só em desenvolvimento, desligado por padrão | Não guardado |

## Fora desta Unit

- Rastreamento distribuído: fora da U1 (Q6); reavaliado no NFR Design de Units posteriores.
- Alertas com notificação: definidos com a hospedagem e a operação; no ambiente local, as métricas ficam só expostas.
