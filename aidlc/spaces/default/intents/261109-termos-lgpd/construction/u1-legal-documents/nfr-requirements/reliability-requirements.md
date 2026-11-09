# Requisitos de confiabilidade — U1 `u1-legal-documents`

Conversa: português (Brasil). Fontes: `construction/u1-legal-documents/functional-design/` (BR1.1, BR1.2, BR1.14, BR1.15), `inception/requirements-analysis/requirements.md` (NFR7), `inception/contract-design/contract-summary.md` (L1) e `nfr-requirements-questions.md` (Q4).

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR7.1 | Sempre existe uma versão vigente: a legacy v1 garante uma ativável; sem nenhuma, a API não sobe e diz o motivo (falha rápida) | teste de integração com catálogo vazio e com só versões não ativáveis |
| NFR7.2 | Uma versão ignorada não derruba a API: vale a anterior, com aviso no log | teste de integração |
| NFR7.3 | Uma versão não publicável deixa a saúde `legalDocuments` em `DEGRADED`, sem derrubar a saúde geral nem a prontidão. O indicador fica fora dos grupos de liveness e readiness, e o status `DEGRADED` responde HTTP 200, como o indicador de tarefas da auditoria | teste de integração da saúde e dos grupos |
| NFR7.4 | Com a API fora do ar, as páginas mostram erro com "tentar de novo", nunca página vazia | teste de componente |

## Recuperação

Nada a recuperar em tempo de execução: o catálogo é parte da imagem. Corrigir um texto exige uma versão nova e uma implantação nova (rollback por imagem).
