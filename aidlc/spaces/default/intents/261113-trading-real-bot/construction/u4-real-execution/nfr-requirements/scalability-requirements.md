# Requisitos de escala e retenção — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes:
- `inception/requirements-analysis/requirements.md` (FR10.3, FR14);
- código-resumo da U3 (pool de agendamento do worker);
- `construction/u3-real-risk/infrastructure-design/infrastructure-specification.md` (padrão de privilégios);
- `nfr-requirements-questions.md` (Q5).

| ID | Requisito | Valor |
|---|---|---|
| U4-NFR3.6 | Volume do piloto | 21 usuários, até 5 bots REAL por usuário, uma ordem por bot em andamento, até cerca de 4 decisões por segundo no pico |
| U4-NFR3.7 | Pool de agendamento e de conexões do worker | uma thread por método `@Scheduled` do worker, como já faz o `RuntimeSettingsGuard`. A conta: 4 trabalhos de antes, 2 da U2 (`CoinGeckoCollectorJob`, `CoinGeckoUsageJob`), 4 da U3 (`day`, `daily-loss`, `reservation-expiry`, medidor de reservas) e 7 da U4 (consumidor da fila, condutor da ordem, confirmação, ambíguas, prazo de assinatura, saúde dos fornecedores, liquidação), num total de 17. O `SCHEDULER_POOL_SIZE` padrão do worker passa a 17, e o guard passa a aceitar de 17 a 24. O pool de conexões do worker (`DB_POOL_MAX_SIZE`) é 4 ciclos + 4 de reserva + 17 trabalhos = 25 no mínimo, e o guard passa a aceitar de 25 a 40. Um teste conta os métodos `@Scheduled` do worker e falha se o mínimo do guard for menor; outro teste sobe o worker com os padrões |
| U4-NFR3.8 | Índices | `real_order` único em `idempotency_key` e `tx_signature`; únicos parciais por bot e por (usuário, mint, venda) nos estados em andamento; índice por (usuário, criado em) para a listagem; `real_decision_request` por estado e criação |
| U4-NFR3.9 | Retenção | tabela abaixo |

## Retenção (U4-NFR3.9)

| Tabela | Prazo | Regra |
|---|---|---|
| `real_order`, `real_order_attempt`, `real_liquidation` | 5 anos | registro das operações para fins fiscais (FR14); o expurgo só apaga linha mais velha que 5 anos |
| `real_decision_request` em `PROCESSED` ou `DISCARDED` | 13 meses | expurgo diário |
| `real_upstream_health` | sem histórico | uma linha por usuário e fornecedor, atualizada |

**Base legal e risco:**
- **Base legal:** os 5 anos das ordens são cumprimento de obrigação legal (LGPD, art. 7º, II, e art. 16, I): o registro das operações para fins fiscais.
- **Conteúdo:** as tabelas guardam ids internos, mint, valores e assinaturas on-chain, nunca o endereço da carteira principal nem nome.
- **Risco aceito:** a assinatura on-chain permite chegar, na própria rede, à carteira do bot e, no modo Assinada, à carteira principal, que é a pagadora. A ligação existe fora do sistema e não pode ser apagada.
- **Anonimização (U5):** mantém as ordens, sem endereço nem ligação interna com a pessoa. O teste de anonimização cobre `real_order`, `real_order_attempt`, `real_liquidation`, `real_decision_request` e os logs.
- **Política de privacidade:** a menção a esse risco é decisão do responsável do projeto. Os privilégios e o gatilho de idade seguem o padrão da U3; a linha no registro de retenção segue a decisão do responsável do projeto, como na U3.
