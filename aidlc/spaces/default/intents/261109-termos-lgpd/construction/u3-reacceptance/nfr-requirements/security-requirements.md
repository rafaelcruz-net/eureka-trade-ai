# Requisitos de segurança — U3 `u3-reacceptance`

Conversa: português (Brasil). Fontes: `construction/u3-reacceptance/functional-design/` (BR3.1 a BR3.8), `inception/requirements-analysis/requirements.md` (NFR1, NFR2, NFR9), `inception/contract-design/contract-summary.md` (L2, L3) e as regras do projeto: tenant só da autenticação e aceite só de inserção.

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR1.1 | O `403 TERMS_REACCEPTANCE_REQUIRED`, o `409 TERMS_VERSION_OUTDATED` e o evento `TERMS_ACCEPTED` não trazem carteira, token nem texto dos documentos; só versões e códigos | teste de API e o `LogScanIT` existente |
| NFR2.1 | O aceite e a trava usam só a pessoa e o tenant da autenticação; o corpo traz apenas `version` | teste de API com token de outro tenant: o aceite fica na pessoa do token |
| NFR2.2 | A trava vale só para tokens de convidado; o token de administrador e o worker não passam por ela | teste de API e teste do ciclo do worker com um bot ativo |
| NFR2.3 | O aceite é só de inserção: a aplicação não pode alterar nem apagar `terms_acceptance` | teste existente de `UPDATE` e `DELETE` recusados, mantido verde depois da migração |
| NFR2.4 | A rota de volta da tela de novo aceite só aceita caminho interno do app; um endereço externo volta ao painel | teste de componente com `//exemplo.com` e `https://...` |
| NFR9.1 | Teste obrigatório do time: "a versão nova dos termos exige novo aceite antes de qualquer outra rota". Ele percorre o registro de rotas do convidado; cada rota fora da lista liberada recebe `403`, e cada rota liberada não recebe | teste de integração |
| NFR9.2 | A lógica de novo aceite é lógica de dados pessoais: os testes de BR3.1, BR3.4, BR3.5 e BR3.6 são escritos antes do código | ordem no plano de código |

## Ameaças consideradas

| Ameaça | Controle |
|---|---|
| Aceitar em nome de outra pessoa ou de outro tenant | NFR2.1 |
| Pular a trava com uma rota não listada | NFR9.1 percorre o registro de rotas |
| Redirecionamento aberto pela rota de volta | NFR2.4 |
| Apagar ou alterar um aceite antigo | NFR2.3 |
