# Requisitos de segurança e integridade — U7 Ledger e P&L

Requisitos de segurança e de integridade financeira da Unit `u7-ledger-pnl`, derivados das regras de [rules.md](../functional-design/rules.md), dos fluxos de [functional-spec.md](../functional-design/functional-spec.md), do NFR4 a NFR8 de [requirements.md](../../../inception/requirements-analysis/requirements.md), dos contratos C5, C9, C11, C13 e C15 de [contract-summary.md](../../../inception/contract-design/contract-summary.md) e das regras do `project.md` e do `team.md`. As decisões vêm de [nfr-requirements-questions.md](nfr-requirements-questions.md) (Q3). Os IDs `NFRx.y` são os da U7; IDs de outra Unit vêm com a Unit.

## Classificação dos dados da U7

| Dado | Classe | Onde aparece | Proteção |
|---|---|---|---|
| Ordens, preenchimentos, lançamentos e posições PAPER | Pessoal e financeiro (simulado) | Banco, rotas C5 | Só a própria pessoa lê (tenant e pessoa do token); ledger só inserção |
| Maior patrimônio e reconciliação | Interno da pessoa | Banco, rotas C5, C13 | Só a própria pessoa e o ciclo do bot dela |
| Política de simulação | Interno | Banco (semeado pela migração) | Sem rota de escrita |
| Identificadores de bot e decisão | Referência | Banco | Só como identificador; a U7 não lê tabelas da U5 |

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR5.1 | Só a própria pessoa vê seus dados PAPER | Toda rota C5 filtra por tenant e pessoa do token. Teste negativo entre tenants e entre pessoas do mesmo tenant para `trades`, `day-usage`, `ledger`, `pnl`, `reconciliation` e `reconciliation/run`: bot de outra pessoa responde `404` e nunca o dado; bot não ativado responde vazio. As tarefas (marcação, fechamento pendente, reconciliação) leem tenant e pessoa da própria linha; teste com duas pessoas confere que nada se mistura | BR9.1, NFR5, `team.md` |
| NFR5.2 | Interfaces recusam conta de outra pessoa | `execute`, `closePositions` e `snapshot` para um bot cuja conta é de outra pessoa ou de outro tenant: `NotFilled(TENANT_MISMATCH)` (ou recusa de domínio no `snapshot`) e um `REQUEST_FORBIDDEN` na trilha; nenhum lançamento | BR9.1 |
| NFR5.3 | Só PAPER | `CHECK mode = 'PAPER'` em todas as tabelas da U7; toda consulta filtra `mode = 'PAPER'`; requisição com `mode` diferente de `PAPER` responde `403 REAL_MODE_UNAVAILABLE`, auditado. Teste obrigatório de separação REAL e PAPER | BR9.2, FR10.6, `project.md` |
| NFR6.1 | Nunca assinar nem enviar transação | Teste de arquitetura: os pacotes `ledger` e `pnl` não dependem de cliente HTTP, de SDK Solana, de assinatura nem de roteador de swap; nenhuma classe de produção constrói transação. Teste obrigatório de REAL desligado | BR1.9, FR9.2 |
| NFR6.2 | Ledger e registros financeiros imutáveis no banco | Privilégios do papel da aplicação pela matriz abaixo; `TRUNCATE` revogado em todas as tabelas da U7; gatilhos recusam `UPDATE` e `DELETE` em `ledger_entry` e `fill` para qualquer papel. Cada linha da matriz tem um teste com o papel da aplicação que confirma o permitido e o recusado (teste obrigatório de ledger sem edição) | BR4.1, FR10.1, AC5.2.2 |
| NFR6.3 | Nenhuma rota de alteração | O OpenAPI do convidado só tem `GET` nas rotas C5, mais `POST /api/v1/reconciliation/run`, que só lê e grava o resultado da conferência. Teste de contrato falha se aparecer `PUT`, `PATCH`, `DELETE` ou outro `POST` sob `/ledger`, `/pnl`, `/trades` | BR4.3 |
| NFR6.4 | Compensação fora do alcance da pessoa | `LedgerCompensation` é o mecanismo de domínio no código de produção (AC5.2.3 pede o mecanismo, exercitado em teste), com visibilidade de pacote dentro de `ledger.domain`. Teste de arquitetura sobre todo o código de produção: nenhuma classe fora de `ledger.domain` o referencia, e nenhum controlador, tarefa ou interface pública o chama; só o código de teste o usa | BR4.2, Q15 das histórias |
| NFR6.5 | Limite em "verificar agora" | `POST /api/v1/reconciliation/run`: até 6 por minuto por pessoa; acima, `429 RATE_LIMITED` com `Retry-After`. As leituras não têm limite próprio (Q3) | BR8.3, Q3 |
| NFR6.6 | Entrada validada | `botId` em UUID; `from` e `to` instantes UTC com `from < to` e até 366 dias; `cursor` e `limit` (1 a 100); parâmetro desconhecido recusado; erros `400 VALIDATION_FAILED` com a lista de campos, sem SQL nem stack trace; consultas sempre parametrizadas | BR6.1, regras comuns |
| NFR6.7 | Sem ponto flutuante | Teste de arquitetura: nenhum `double`, `float`, `Double` ou `Float` nos pacotes `ledger` e `pnl`; colunas monetárias e de quantidade `numeric`; JSON em `DecimalString` | `project.md`, FR10.2 |
| NFR6.8 | Idempotência sem duplicata | `paper_order` com restrição única (tenant, modo, chave); `fund` e `closePositions` pelo `IdempotencyGuard` da U1; teste com a mesma chave em sequência, em 2 threads ao mesmo tempo (50 repetições) e depois de falha injetada: uma operação e uma série de lançamentos; chave com outro conteúdo é `Conflict` sem gravação (teste obrigatório de deduplicação) | BR3.1 a BR3.3, BR2.6, AC4.6.3, AC4.6.4 |
| NFR7.1 | Nada sensível nos logs | Logs da U7 usam só identificadores (`orderId`, `botId`); nunca endereço de carteira, token de sessão ou cabeçalho `Authorization`. A varredura de logs da U1 (`LogScanIT`) cobre um fluxo completo da U7 | NFR7, `project.md` |

## Matriz de privilégios do papel da aplicação (NFR6.2)

| Tabela | INSERT | SELECT | UPDATE | DELETE | Restrição no banco |
|---|---|---|---|---|---|
| `ledger_entry` | sim | sim | não | não | Gatilho recusa `UPDATE` e `DELETE` para qualquer papel |
| `fill` | sim | sim | não | não | Gatilho recusa `UPDATE` e `DELETE` para qualquer papel |
| `paper_order` | sim | sim | só `status` | não | Gatilho: a única mudança aceita é `status` de `CLOSE_PENDING` para `FILLED`; qualquer outra coluna ou transição é recusada; ordens `FILLED`, `PARTIALLY_FILLED` e `NOT_FILLED` ficam imutáveis |
| `position` | sim | sim | `quantity`, `cost_basis`, `status`, `updated_at`, `version` | não | `CHECK quantity >= 0`, `CHECK cost_basis >= 0`, `CHECK (quantity = 0) = (cost_basis = 0)` |
| `paper_account` | sim | sim | só `status` | não | Gatilho: só `OPEN` → `CLOSING` → `CLOSED`, nunca para trás |
| `equity_peak` | sim | sim | `peak_equity`, `peak_at`, `last_equity`, `last_equity_at` | não | Gatilho recusa `peak_equity` menor que o gravado |
| `reconciliation_check` | sim | sim | não | sim | `DELETE` só para manter os últimos 100 por pessoa (BR8.1) |
| `simulation_policy` | não (só a migração) | sim | não | não | Semeada pelo dono do esquema |

## Ameaças consideradas (STRIDE)

| Categoria | Ameaça | Controle |
|---|---|---|
| Falsificação | Pessoa consulta P&L de outro bot pelo `botId` | Filtro por pessoa e `404` (NFR5.1) |
| Adulteração | Alterar um lançamento para mudar o resultado | Banco recusa `UPDATE` e `DELETE` (NFR6.2); sem rota de alteração (NFR6.3) |
| Adulteração | Registrar a mesma operação duas vezes | Unicidade no banco e mecanismo comum de idempotência (NFR6.8) |
| Repúdio | Negar uma operação ou um fechamento | Eventos na trilha por ordem e fechamento (observability-requirements.md) |
| Divulgação | Ler dados PAPER de outra pessoa por tarefa em segundo plano | Tarefas filtram pela linha (NFR5.1) |
| Negação de serviço | Disparar "verificar agora" em laço | Limite de 6 por minuto (NFR6.5) |
| Elevação de privilégio | Usar a compensação para "corrigir" o próprio saldo | Compensação só em teste (NFR6.4) |
| Elevação de privilégio | Operação REAL | Só PAPER no banco e nas rotas; nada assina (NFR5.3, NFR6.1) |

## Conformidade

- **Sem promessa de lucro:** os textos das telas de P&L, ledger e reconciliação passam pelo catálogo de termos proibidos da U4.
- **Dados pessoais:** os dados PAPER ligam-se a uma pessoa; a retenção sai do inventário de dados (SP-3), como nas outras Units. O ledger não é apagado no beta (FR10.1).
