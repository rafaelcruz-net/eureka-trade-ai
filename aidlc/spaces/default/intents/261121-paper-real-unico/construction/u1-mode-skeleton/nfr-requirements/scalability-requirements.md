# Requisitos de escala — U1 `u1-mode-skeleton`

Nada muda na escala: a U1 é leitura por pessoa e uma criação de bot, sobre o monólito atual (API e worker no mesmo app Spring Boot, um PostgreSQL) [Q6]. Os requisitos abaixo fixam esses limites para que a U1 não os mude sem uma decisão nova.

## Projeção de carga e concorrência

| ID | Requisito | Valor |
|---|---|---|
| NFR1.10 | Volume de referência por pessoa para as rotas da U1. | Até 10 bots por pessoa e 2 anos de ordens do próprio bot (a mesma condição de NFR1.1). Acima disso, nenhuma meta da U1 é prometida. |
| NFR1.11 | A U1 não acrescenta processo, réplica nem gatilho de escala. | 0 serviços novos, 0 réplicas novas, 0 filas novas, 0 caches novos; as rotas novas rodam no processo da API que já existe e escalam com ele. |
| NFR1.12 | Concorrência da criação: pedidos repetidos com a mesma `Idempotency-Key` nunca criam dois bots (BR1.4). | Teste de integração com 10 pedidos simultâneos de mesma chave e mesmo corpo (PAPER): exatamente 1 bot criado; cada uma das 10 respostas é ou 201 com o mesmo corpo do bot criado (a resposta guardada, repetida), ou 409 `IDEMPOTENCY_CONFLICT` com `reason` `IN_PROGRESS`, que o mecanismo comum de idempotência devolve enquanto o primeiro pedido ainda está em andamento (`IdempotencyService`); pelo menos 1 resposta é 201. Qualquer outra saída (outro status, outro corpo, um segundo bot) faz o teste falhar. |

## Crescimento de dados

| ID | Requisito | Valor |
|---|---|---|
| NFR1.13 | A U1 não grava dado novo de forma contínua. | 0 tabelas novas, 0 colunas novas e 0 migrações Liquibase na U1. As únicas gravações são o bot criado (C1), o registro de idempotência e o registro de auditoria da recusa (NFR3.5), que já existem com as retenções atuais. |

## Custo e ambientes

| ID | Requisito | Valor |
|---|---|---|
| NFR8.1 | A U1 roda no mesmo app, sem serviço AWS novo (BR10.4). | 0 recursos de serviço novos em `infra/terraform/`; as três variáveis sem segredo do `api_environment` do `infrastructure-specification.md` da U1 (alinhamento A7 da Code Generation da U2). |
| NFR8.2 | O orçamento de cada conta AWS não muda. | US$ 350 por mês por conta (`devnet` e `mainnet`); a U1 não acrescenta custo recorrente. |
| NFR8.3 | Os usuários têm um único ambiente, a mainnet; a devnet continua fechada aos usuários (BR10.4). | 0 rotas, telas ou configurações da U1 que abram a devnet a uma conta de usuário. |
| NFR8.4 | A U1 nunca é implantada num ambiente com usuários antes da U2 (BR10.3). | A U1 só vai para a `mainnet` (ou para uma devnet com convidados) num SHA que já contém a U2 com o Build and Test aprovado. A devnet não tem usuários (NFR8.3), então o fundador pode implantar a U1 nela para os próprios testes antes da U2; o local e a CI continuam sendo a verificação do ponto de controle do esqueleto. |

## Sources

- `nfr-requirements-questions.md`: Q6 e resumo confirmado [Q6].
- `inception/requirements-analysis/requirements.md`: NFR1 (10 bots, 2 anos), NFR8 (mainnet única, nenhum serviço AWS novo, US$ 350).
- `construction/u1-mode-skeleton/functional-design/rules.md`: BR1.4, BR10.3, BR10.4.
- `construction/u1-mode-skeleton/functional-design/entities.md`: nenhuma tabela nova na U1.
- `aidlc/spaces/default/memory/team.md`: Deployment (contas, orçamento, devnet).
- `aidlc/spaces/default/codekb/eureka-trader-ai/technology-stack.md`: sem fila de mensagens nem cache.
- Código lido: `identity/domain/idempotency/IdempotencyService.java`, `identity/api/IdempotencyGuard.java`, `shared/error/ErrorCode.java` (`IDEMPOTENCY_CONFLICT`, 409).

## Assumptions & Open Questions

1. O comportamento do mecanismo comum de idempotência para pedidos simultâneos de mesma chave foi conferido no código: `IdempotencyService` devolve 409 `IDEMPOTENCY_CONFLICT` (`ErrorCode.IDEMPOTENCY_CONFLICT`) com `reason` `IN_PROGRESS` enquanto o registro está `IN_PROGRESS`, e a resposta guardada depois que ele termina. A U1 não muda esse mecanismo.
