# Requisitos de escala — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-23.

Nada muda na escala [Q4]. A U3 acrescenta telas e leituras por pessoa sobre o monólito atual (API e worker no mesmo app Spring Boot, um PostgreSQL), sem migração, sem configuração nova e sem infraestrutura nova. Os requisitos abaixo fixam esses limites.

## Projeção de carga e concorrência

| ID | Requisito | Valor |
|---|---|---|
| NFR1.12 | Volume de referência por pessoa para as leituras da U3. | Até 10 bots por pessoa e 2 anos de ordens do próprio bot (NFR1 do intent); C5 com 50 linhas por página e até 100 por pedido; até 10 parcelas rateadas por bot em C4. Acima disso, nenhuma meta da U3 é prometida. |
| NFR1.13 | A U3 não acrescenta processo, réplica, fila, cache nem gatilho de escala. | 0 serviços novos, 0 réplicas novas, 0 filas novas, 0 caches novos; C5 e C6 rodam no processo da API que já existe e escalam com ele. |
| NFR1.14 | A carga extra do canal ao vivo do painel é limitada (BR7.5). | Por cartão, no máximo 2 pedidos de C4 a cada 30 s, 1 imediato e 1 pendente no fim da janela, e 1 pedido a cada 30 s em regime sustentado (NFR1.7). Na referência de 20 pessoas com o painel aberto ao mesmo tempo (40 cartões): em regime sustentado, no máximo 40 pedidos de C4 a cada 30 s (cerca de 1,3 por segundo); no pior intervalo de 30 s, quando todas as janelas fecham juntas, no máximo 80 pedidos (cerca de 2,7 por segundo). Os dois ficam abaixo dos 10 pedidos por segundo do cenário `pnl-by-mode` (20 usuários, um pedido a cada 2 s). O canal ao vivo não muda e não ganha evento novo. |

## Crescimento de dados

| ID | Requisito | Valor |
|---|---|---|
| NFR1.15 | A U3 não cria estrutura de dados nova [Q4]. | 0 tabelas novas, 0 colunas novas, 0 índices novos e 0 migrações Liquibase. A única gravação é o evento de auditoria de NFR3.13, na tabela de auditoria que já existe, com a retenção atual, limitado a 1 evento por hora por pessoa e por ação (NFR3.14): no máximo 2 eventos por pessoa por hora, 48 por dia. Os contadores (NFR3.9, NFR3.15, NFR3.16) ficam só na memória do processo e no Prometheus. |

## Custo e ambientes

| ID | Requisito | Valor |
|---|---|---|
| NFR8.1 | A U3 roda no mesmo app, sem serviço AWS novo [Q4]. | 0 recursos novos em `infra/terraform/`; 0 variáveis de ambiente novas nas definições de tarefa; 0 chaves novas em `application.yml`. |
| NFR8.2 | O orçamento de cada conta AWS não muda. | US$ 350 por mês por conta (`devnet` e `mainnet`); a U3 não acrescenta custo recorrente. As métricas novas não entram na lista do CloudWatch (NFR8.4), então não aumentam a ingestão. O cenário de carga ampliado (NFR1.6) roda no runner do GitHub, nos minutos do Actions, e não na AWS. |
| NFR8.3 | Os usuários têm um único ambiente, a mainnet; a devnet continua fechada a eles. | 0 telas, rotas ou configurações da U3 que abram a devnet a uma conta de usuário. |

## Sources

- `nfr-requirements-questions.md`: Q1, Q4 e o resumo confirmado [Q1] [Q4].
- `reviews/review-01.md` (R-01, R-04) e as decisões do fundador de 2026-11-23 (D-NFR1 em `security-requirements.md`, D-NFR2 em `performance-requirements.md`).
- `inception/requirements-analysis/requirements.md`: NFR1 (10 bots, 2 anos), NFR8 (mainnet única, nenhum serviço AWS novo, US$ 350).
- `construction/u3-pnl-views/functional-design/rules.md`: BR4.3, BR4.7, BR7.5.
- `construction/u3-pnl-views/functional-design/entities.md`: as únicas mudanças de dados são de API (ENT-303, contagens de `BotPage`).
- `construction/u2-real-bot-pnl/nfr-requirements/performance-requirements.md`: NFR1.6 (tráfego do cenário `pnl-by-mode`).
- `inception/contract-design/contract-summary.md`: C5 (`limit` de 1 a 100).
- `aidlc/spaces/default/memory/team.md`: Deployment (contas, orçamento, devnet); `project.md`: NEVER abrir a devnet aos usuários.

## Assumptions & Open Questions

1. [assumption] As 20 pessoas com o painel aberto ao mesmo tempo (NFR1.14) repetem a referência dos cenários do `load-test`. Os tetos de NFR1.14 são de pior caso: exigem eventos dos dois modos em todas as janelas.
