# Requisitos de segurança — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). Data: 2026-11-22.

Herdam o NFR3 (isolamento e segurança) e o NFR4 (separação dos modos) do intent, com a resposta Q3 = A [Q3]. A U2 não cria rota nova. Ela muda a leitura do P&L REAL (C4, C10, `GET /api/v1/real/pnl`), cria a tabela `real_entry_slot` (C14) e grava o slot em três confirmações (C13).

## Autenticação, autorização e isolamento

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.1 | O tenant e a pessoa vêm sempre do contexto de autenticação. As leituras em fluxo das ordens (`streamSettledOrders`, C13), dos lançamentos (`streamInNetworkOrder`, C12), das carteiras e dos bots (C8) filtram por tenant e pessoa (BR8.6). | 0 consultas dessas leituras sem filtro de tenant (regra ArchUnit do módulo continua verde). Teste de integração com duas pessoas de tenants diferentes que operam o mesmo mint no mesmo slot: o P&L, a conferência e o evento de auditoria de cada uma não contêm nenhum lançamento, ordem, bot, valor ou id da outra. Teste do tenant em segundo plano: a leitura de custo chamada pela barreira e pelo `RealCycle` (F6.2) usa o tenant da ordem e não lê nada de outro tenant. |
| NFR3.2 | Um bot de outra pessoa nunca devolve dado (BR3.4 e BR8.2 da U1, F5). | `botPnl` com o id de um bot de outra conta devolve vazio, e a rota responde 404 sem nenhum campo do bot alheio. Em C4, a linha de um bot alheio nunca aparece. |
| NFR3.3 | Uma ordem cujo bot não é bot REAL da pessoa (`UNKNOWN_BOT`) deixa só o bloco de P&L REAL indisponível e nunca expõe o bot alheio (BR3.4). | Teste de integração: com uma ordem semeada com o bot de outra pessoa, o bloco REAL sai `UNAVAILABLE` com `RECONCILIATION_MISMATCH`, o bloco PAPER sai normal, o corpo não contém o id nem o nome do bot alheio, e a venda, a liquidação e o saque da carteira continuam (NFR3.11). |

## Proteção dos dados do ledger

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.4 | `real_entry_slot` é só de inserção, como o ledger (C14, BR1.4). | Gatilhos `real_entry_slot_no_update_delete` (`BEFORE UPDATE OR DELETE`, por linha) e `real_entry_slot_no_truncate` (`BEFORE TRUNCATE`), com a função `ledger_reject_change`. O teste obrigatório "ledger sem edição" (`team.md`) passa a cobrir a tabela: UPDATE, DELETE e TRUNCATE falham em 3 de 3 tentativas, e a linha continua igual. |
| NFR3.5 | O papel da aplicação só lê e insere em `real_entry_slot`. | Changeset `0023-real-entry-slot-grants`: `REVOKE ALL` e depois `GRANT SELECT, INSERT` ao papel da aplicação; `REVOKE TRUNCATE` do dono da migração, como na `0019`. Teste de integração com o login da aplicação: SELECT e INSERT funcionam (2 de 2); UPDATE, DELETE e TRUNCATE são recusados por permissão ou pelo gatilho (3 de 3). |
| NFR3.6 | Nenhuma linha antiga do ledger é alterada e nenhuma coluna entra em `real_ledger_entry` ou `real_order` (BR1.5, C14). | A migração `0023` tem só `CREATE TABLE`, `CREATE INDEX`, `CREATE TRIGGER`, `GRANT` e `REVOKE`; 0 `ALTER TABLE`, `UPDATE`, `DELETE` ou `INSERT` em tabelas existentes. Teste de integração conta as linhas de `real_ledger_entry` e de `real_order` antes e depois da migração: diferença 0. |

## Logs, respostas e auditoria

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.7 | Nenhum endereço de carteira e nenhuma assinatura de transação completa aparece nos logs da U2. A assinatura aparece só abreviada, com 4 caracteres do início, "…" e 4 do fim, a mesma forma do `WalletDisplay.abbreviate`. | Teste de captura de log nos eventos da U2 (divergência, `UNKNOWN_BOT`, conflito de slot, falha ou tempo esgotado da atribuição): 0 registros com o endereço semeado da carteira do bot ou da carteira principal; 0 registros com a assinatura completa semeada; a forma abreviada aparece no registro do conflito de slot. 0 registros com token, cabeçalho `Authorization`, nonce ou assinatura de login (`project.md`). |
| NFR3.8 | As respostas de P&L não trazem endereço de carteira, assinatura nem as diferenças da conferência. | Teste de contrato: os esquemas de C4 e de `GET /api/v1/real/pnl` não têm campo de endereço nem de assinatura. Teste de integração: o corpo de um bloco `UNAVAILABLE` por `RECONCILIATION_MISMATCH` contém só `status`, `code` e `correlationId`; as diferenças em lamports ficam só no evento de auditoria (NFR3.9). |
| NFR3.9 | A auditoria registra `REAL_PNL_RECONCILIATION_MISMATCH` (com `UNKNOWN_BOT` entre as verificações que falharam) e `REAL_SLOT_CONFLICT` sem dado pessoal (ENT-214, ENT-215). No `REAL_SLOT_CONFLICT`, a assinatura da transação é guardada só abreviada (4 caracteres do início, "…" e 4 do fim) e como HMAC, calculado pelo mecanismo que a auditoria já usa para os endereços de carteira (`WalletHasher`, chave `memetrade.audit.wallet-hmac-key`, variável `MEMETRADE_AUDIT_WALLET_HMAC_KEY`) [fundador D-NFR2]. Os dois eventos seguem a retenção e a anonimização da auditoria que já existem. | Cada evento traz só ids (tenant, pessoa como uuid), enums (verificações, gravador), valores em lamports, slots, o mint da verificação que falhou quando houver, o `correlationId` e, no conflito, a assinatura abreviada e o HMAC dela. 0 eventos com a assinatura completa, endereço de carteira, nome, e-mail, assinatura de login ou segredo. Teste de integração: o evento de conflito guarda a forma abreviada e um HMAC igual ao que o `WalletHasher` calcula para a assinatura semeada, e a assinatura completa não aparece em nenhuma coluna da auditoria. Com a assinatura só abreviada e com HMAC, ninguém resolve o evento para o endereço da carteira num explorador da rede, e a afirmação "sem dado pessoal" vale para os dois eventos. O HMAC permite ao fundador achar a linha do ledger pela assinatura que ele já tem, sem guardá-la no evento. A gravação do evento de divergência segue o limite de NFR3.15 e é a única escrita da leitura (BR10.2). |
| NFR3.15 | O evento de divergência tem volume limitado, e a falha da gravação dele nunca muda a resposta (revisão R-03). | No máximo 1 evento `REAL_PNL_RECONCILIATION_MISMATCH` por impressão digital e por hora. A impressão digital é a pessoa mais o conjunto ordenado dos tipos de verificação que falharam, sem nenhum valor e sem o período (decisão do fundador de 2026-11-23, revisão R-05 da Code Generation): uma divergência cujo valor muda a cada leitura continua sendo 1 evento por hora, e só outro tipo de verificação grava outro evento na mesma hora. As diferenças em lamports e o período ficam só nos detalhes do evento. As leituras dentro da hora não gravam evento, mas sobem o contador de NFR2.11 em toda leitura (o alarme não muda). O log `ERROR` `REAL_PNL_RECONCILIATION_MISMATCH` sai no máximo 1 vez por minuto por pessoa e tipo de verificação. A gravação do evento roda só depois que a transação de leitura do bloco fechou (revisão R-01), com tempo máximo de 200 ms; se ela falhar ou passar do tempo, o bloco REAL sai `UNAVAILABLE` com `RECONCILIATION_MISMATCH` do mesmo jeito, a falha é registrada em log `ERROR` com o `correlationId` e conta em `pnl_real_reconciliation_audit_failures_total` (NFR3.16). Teste de integração: 10 leituras seguidas com a mesma divergência, cada uma com outro valor, gravam 1 evento e sobem o contador 10 vezes; outro tipo de verificação grava 1 evento a mais; com a auditoria falhando, as respostas são iguais e o contador de falhas sobe a cada tentativa de gravação. Teste de unidade: 10 leituras no mesmo minuto dão 1 log; outra pessoa ou outro tipo de verificação dão 1 log a mais; o contador sobe a cada leitura. |

## Caminhos de risco do REAL

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.10 | A U2 não acrescenta nenhum caminho que assine ou envie transação, e o REAL continua desligado por padrão. | Com a configuração padrão (API, worker e TxSigner), o teste obrigatório "REAL desligado por padrão" continua verde: 0 carteiras do bot criadas, 0 assinaturas pedidas ao KMS, 0 transações enviadas. `recordSlot` é chamado só dentro das três confirmações que já existem (C13) e não chama o TxSigner nem a rede. O slot nunca é buscado na rede durante a leitura (BR1.4). |
| NFR3.11 | A falha da leitura de atribuição é falha fechada para a compra e nunca bloqueia a saída do dinheiro (BR2.7, `project.md` ALWAYS saque disponível). | Teste de integração com a atribuição falhando (erro, tempo esgotado e `UNKNOWN_BOT`): a compra com posição sem preço é recusada (`POSITION_PRICE_UNKNOWN`) e a perda diária não é avaliada como cumprida; a venda, a liquidação e o saque para a carteira principal são aceitos (3 de 3). A dependência `ATTRIBUTION` aparece no mapeamento de falha do `RealGateInputsReader`, ao lado de `LEDGER`. |

## Separação dos modos

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR4.1 | Os tipos novos de resultado REAL entram na lista fechada da regra NEVER_SUM_MODES no mesmo commit em que são criados (BR10.5). | `NeverSumModesArchitectureTest` verde, com a lista atualizada; nenhum tipo da U2 aparece numa classe que também dependa de um tipo de resultado PAPER, fora do ponto que monta os dois blocos de C4. |
| NFR4.2 | Nenhuma resposta, tela ou relatório soma PAPER e REAL (`project.md` NEVER). | O teste de contrato e o de integração da U1 (NFR4.1 da U1) continuam verdes com o bloco REAL calculado pela U2: nenhum número fora dos blocos é igual à soma dos totais dos dois modos. |
| NFR4.3 | A leitura em fluxo, a tabela `real_entry_slot` e a conferência nunca leem nem gravam o ledger PAPER (C12: "nunca PAPER"). | Regra ArchUnit: as classes novas do `pnl.application` do REAL e do `ledger.infra` do REAL não dependem de tipos do ledger PAPER. Teste de integração: com ordens nos dois modos, o fluxo de C12 entrega 0 lançamentos PAPER e a conferência REAL não muda quando se acrescentam lançamentos PAPER. |

## Sources

- `nfr-requirements-questions.md`: Q3 e o resumo confirmado [Q3].
- `reviews/review-01.md` (R-03, R-07) e a decisão do fundador de 2026-11-22 D-NFR2 (assinatura abreviada e com HMAC no `REAL_SLOT_CONFLICT`, retenção e anonimização da auditoria).
- `inception/requirements-analysis/requirements.md`: NFR3, NFR4.
- `construction/u2-real-bot-pnl/functional-design/rules.md`: BR1.4, BR1.5, BR2.7, BR3.4, BR8.6, BR8.7, BR10.2, BR10.5.
- `construction/u2-real-bot-pnl/functional-design/entities.md`: ENT-213, ENT-214, ENT-215.
- `construction/u2-real-bot-pnl/functional-design/functional-spec.md`: F4, F5, F6.2, F7.
- `inception/contract-design/contract-summary.md`: C4, C8, C10, C12, C13, C14.
- `construction/u1-mode-skeleton/nfr-requirements/security-requirements.md`: NFR3.1, NFR3.2, NFR3.9, NFR4.1 da U1 (forma e testes que continuam).
- `aidlc/spaces/default/memory/project.md`: Forbidden (log de segredos, soma dos modos), Mandated (tenant, ledger append-only, separação, REAL desligado, saque sempre disponível).
- `aidlc/spaces/default/memory/team.md`: Testing Posture (testes obrigatórios por risco), Code Style (tenant no código, ArchUnit).
- Código lido: `audit/infra/AuditWalletConfig.java` e `WalletHasher` (HMAC dos endereços de carteira na auditoria), `audit/api/RealReferences.java`, `shared/wallet/WalletDisplay.java` (abreviação 4 … 4), `riskcontrol/real/infra/RealGateInputsReader.java` (`Dependency`), `architecture/NeverSumModesArchitectureTest.java`, `architecture/LedgerArchitectureTest.java`.

## Assumptions & Open Questions

1. **Decidido (fundador, D-NFR2, 2026-11-22):** o `REAL_SLOT_CONFLICT` guarda a assinatura abreviada e o HMAC dela, e não a assinatura completa (NFR3.9). O ENT-215 do Functional Design (`txSignature` completa) é alinhado na Code Generation; este estágio não o edita. Nos logs, a assinatura continua só abreviada (NFR3.7).
2. [assumption] `real_entry_slot` não tem `tenant_id` (C14). O isolamento vem da junção com `real_ledger_entry`, que filtra pelo tenant; uma leitura direta da tabela só mostra pares (assinatura, slot) públicos. Nenhum leitor da aplicação lê a tabela sem essa junção.
3. [assumption] A U2 não muda o modelo de ameaças do ADR-005: a única escrita nova é o par (assinatura, slot) dentro de uma confirmação que já existe, e a leitura nova é só consulta.
4. [assumption] A impressão digital de NFR3.15 e o limite de 200 ms da gravação do evento são escolhas deste estágio; o NFR Design pode trocar a forma da impressão digital, mantendo 1 evento por pessoa, por divergência e por hora.
