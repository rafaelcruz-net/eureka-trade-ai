# Requisitos de segurança — U5 `u5-anonymization`

Conversa: português (Brasil). Fontes: `construction/u5-anonymization/functional-design/` (BR5.1, BR5.4, BR5.6 a BR5.8), `inception/requirements-analysis/requirements.md` (NFR1, NFR2, NFR9) e `inception/contract-design/contract-summary.md` (P2, J1 a J3).

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR1.1 | Depois da anonimização, o endereço completo da carteira não aparece em nenhuma coluna de texto de nenhuma tabela (inclusive `audit_event`, o payload de `audit_outbox` e os JSON), nem nos logs capturados. É o teste obrigatório do time | teste de integração que varre o catálogo do esquema (todas as colunas `text`, `varchar` e `jsonb`) e o log capturado |
| NFR1.2 | O evento `ACCOUNT_ANONYMIZED`, o log e a resposta trazem só contagens e identificadores internos | teste de auditoria e de log |
| NFR2.1 | Só `ADMIN` aciona, a partir de um pedido de Exclusão em andamento de uma conta `BETA_USER`. Na origem EMAIL, exige identidade confirmada; a confirmação `ANONIMIZAR` é obrigatória | testes de API para cada recusa (AC6.1.1 a AC6.1.4 e conta ADMIN) |
| NFR2.2 | A conta anonimizada não entra mais, e o token já emitido deixa de valer na requisição seguinte | testes de login, renovação e requisição com o token antigo |
| NFR2.3 | O feedback só é apagado pela função `SECURITY DEFINER` (`EXECUTE` só do papel da aplicação, `search_path` fixo); um `DELETE` direto continua recusado pelo gatilho | teste de banco: `DELETE` direto falha, a função apaga só a pessoa pedida |
| NFR9.1 | Os testes da anonimização (transação, recusas, repetição, falha no meio, concorrência e teste obrigatório) são escritos antes do código | ordem no plano de código |

## Ameaças consideradas

| Ameaça | Controle |
|---|---|
| Anonimizar a conta errada | NFR2.1 (pedido de Exclusão, identidade, dupla confirmação) |
| Endereço sobrando em alguma tabela ou log | NFR1.1 |
| Reabrir a conta | NFR2.2 |
| Usar a função de apagar feedback para outra coisa | NFR2.3 |
