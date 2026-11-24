# Requisitos de segurança — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). Data: 2026-11-24.

Herdam o NFR3 (isolamento e segurança) e o NFR4 (separação dos modos) do intent. A U4 é dinheiro REAL: o ciclo de um bot passa a vender só a própria quantidade. Ela não cria rota, não cria caminho de assinatura ou envio, não muda o saque e não muda o PAPER. Os testes de risco do `team.md` continuam verdes [Q2].

## Isolamento e autorização

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.1 | Tenant e pessoa da leitura vêm da linha do bot e do contexto de autenticação do dono, nunca de parâmetro livre; as três leituras do instantâneo (C12, C13, C16) filtram pelo tenant (BR2.4; `project.md` ALWAYS validar o tenant). | 0 consultas do RealBotHoldings sem filtro de tenant (regra ArchUnit do módulo). Teste de integração com duas pessoas de tenants diferentes, com o mesmo mint e ordens no mesmo slot: a quantidade própria, o custo e as vendas em aberto de cada uma não mudam quando se acrescentam ordens, lançamentos ou decisões da outra (0 linhas alheias lidas). A leitura roda em segundo plano (worker) e usa o tenant do bot, como pede o teste obrigatório de tarefas em segundo plano. |
| NFR3.2 | Um bot que não é bot REAL da pessoa no tenant devolve `Unavailable(BOT_NOT_OWNED)` sem dado nenhum (BR2.4). | T6: com o id de um bot de outra pessoa e de outro tenant, o resultado é `BOT_NOT_OWNED`, 0 quantidades, 0 custos e 0 vendas em aberto devolvidos, e o ciclo não vende (BR2.3). |
| NFR3.3 | A U4 não cria caminho que assine ou envie transação; a venda continua passando pelo `RealDecisionStep` e pelo RealExecution como hoje (BR3.3, BR4.3). | O teste obrigatório "REAL desligado por padrão" continua verde com a configuração padrão (API, worker e TxSigner): 0 carteiras do bot criadas, 0 assinaturas pedidas ao KMS, 0 transações enviadas. T11: decisão reprocessada pela fila ou repetida com a mesma `Idempotency-Key` gera 1 transação (teste obrigatório "uma única transação por ordem REAL"). Regra ArchUnit: `realexecution` não depende de `pnl` nem do RealBotHoldings (T13). |
| NFR3.4 | O corte nunca vende mais que a quantidade disponível do bot no instantâneo, e a marca do bot impede que dois ciclos vendam a mesma quantidade (BR1.4, BR1.7, BR3.1). | T1, T2, T4, T9 e T10 escritos antes do código: em 100% dos casos, a soma das vendas do ciclo de um bot ≤ a quantidade própria dele; com dois ciclos do mesmo bot concorrendo, o segundo termina `STALE_SNAPSHOT` e grava 0 decisões. A única exceção é a documentada e aceita (D2, T18). |
| NFR3.5 | O saque, "Vender tudo e sacar", as condicionais e a liquidação nunca leem o RealBotHoldings nem a atribuição, com a flag ligada ou desligada (BR4.1; `project.md` ALWAYS saque disponível). | Regra ArchUnit (T13): 0 dependências desses caminhos e das compras para o RealBotHoldings. Teste de integração com a flag ligada e o RealBotHoldings falhando: o saque para a carteira principal, "Vender tudo e sacar" e a liquidação são aceitos (3 de 3). O teste obrigatório "saque só para a carteira principal registrada" continua verde. |
| NFR3.6 | A flag só muda por variável de ambiente das definições de tarefa, versionada no Terraform; nenhuma rota, tela administrativa ou linha de banco liga a flag (BR5.2; `team.md`, Flags). | 0 rotas, 0 endpoints de actuator graváveis e 0 tabelas para a flag. `application.yml`: `memetrade.real.own-quantity-sell: ${MEMETRADE_REAL_OWN_QUANTITY_SELL:false}`. Teste de configuração: sem a variável, o valor é `false` na API e no worker. |

## Proteção de dados, logs e migração

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.7 | Os logs e as métricas da U4 não trazem endereço de carteira, assinatura de transação, token, cabeçalho `Authorization`, nonce nem assinatura de login (`project.md` NEVER). As etiquetas de métrica levam só enums. | Teste de captura de log nos eventos da U4 (indisponibilidade, `STALE_SNAPSHOT`, `NO_OWN_QUANTITY`, corte): 0 registros com o endereço semeado da carteira do bot ou da carteira principal e 0 com a assinatura semeada. Etiquetas: só `reason` e `outcome` (NFR2.10, NFR2.11); 0 etiquetas com tenant, pessoa, bot, mint ou carteira. |
| NFR3.8 | A migração da U4 só troca a regra de valores aceitos de `bot_cycle.outcome`; não muda permissões, não toca dados e não muda tabela do ledger. | O changeset tem só `ALTER TABLE bot_cycle DROP CONSTRAINT`, `ADD CONSTRAINT ... NOT VALID` e `VALIDATE CONSTRAINT` (NFR2.7); 0 `GRANT`, `REVOKE`, `UPDATE`, `DELETE`, `INSERT` e 0 mudanças em `real_ledger_entry`, `real_order` e `real_entry_slot`. Teste de integração conta as linhas de `bot_cycle` antes e depois: diferença 0. O teste obrigatório "ledger sem edição" continua verde. |

## Separação dos modos

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR4.1 | O ciclo PAPER não muda: não lê a flag, o RealBotHoldings nem nada do REAL (BR4.4). | T14 escrito antes do código: o pedido de decisão do PAPER é igual ao de hoje, campo a campo, para os mesmos dados, com a flag ligada e desligada; 0 chamadas do RealBotHoldings e 0 leituras da flag no ramo PAPER. O motor de decisão não muda (0 linhas alteradas em `decision/domain`). |
| NFR4.2 | Os tipos novos da U4 (RealBotHoldings, HoldingsResult, OpenSell com `source`) ficam no lado REAL e não dependem de tipos do PAPER; nenhum total soma os modos (`project.md` NEVER). | Regra ArchUnit: 0 dependências das classes novas para o ledger, ordens ou P&L PAPER. `NeverSumModesArchitectureTest` e o teste de contrato e integração da U1 (nenhum número fora dos blocos igual à soma dos dois modos) continuam verdes. |
| NFR4.3 | O aviso da lista `/bots` aparece só com o filtro "Todos" ou "Real" e nunca dentro de um bloco ou total do simulado (BR6.1, BR6.3). | T15 e T16: com o filtro "Simulado", 0 avisos; com a flag desligada, 0 avisos em qualquer filtro. |

## Sources

- `nfr-requirements-questions.md`: Q2 e o resumo confirmado [Q2]; Q4 (flag no Terraform) [Q4].
- `inception/requirements-analysis/requirements.md`: NFR3, NFR4.
- `construction/u4-own-quantity-cycle/functional-design/rules.md`: BR1.4, BR1.7, BR2.3, BR2.4, BR3.1, BR3.3, BR4.1 a BR4.4, BR5.2, BR6.1, BR6.3; `functional-spec.md`: T1 a T18, "Efeito nos contratos".
- `inception/contract-design/contract-summary.md`: C12, C13, C15, C16.
- `construction/u2-real-bot-pnl/nfr-requirements/security-requirements.md`: forma e testes que continuam (NFR3.1, NFR3.10, NFR3.11, NFR4.1 a NFR4.3 da U2).
- `aidlc/spaces/default/memory/project.md`: Forbidden (log de segredos, soma dos modos), Mandated (tenant, separação, REAL desligado, saque sempre disponível, ledger append-only).
- `aidlc/spaces/default/memory/team.md`: Testing Posture (testes obrigatórios por risco), Deployment (Flags), Code Style (tenant no código, ArchUnit).
- Código lido: `apps/api/src/main/resources/application.yml` (padrão `${MEMETRADE_REAL_EXITS_ENABLED:false}`), `db/changelog/changes/0020-real-bot-cycle.sql` (regra `bot_cycle_outcome_ck`).

## Assumptions & Open Questions

1. [assumption] O nome da variável de ambiente `MEMETRADE_REAL_OWN_QUANTITY_SELL` segue o padrão das outras flags do REAL (`MEMETRADE_REAL_EXITS_ENABLED`), com o valor padrão escrito no `application.yml`.
2. [assumption] A U4 não muda o modelo de ameaças do ADR-005: ela só reduz a quantidade que um ciclo pede, e a política de conteúdo do TxSigner continua como última barreira.
