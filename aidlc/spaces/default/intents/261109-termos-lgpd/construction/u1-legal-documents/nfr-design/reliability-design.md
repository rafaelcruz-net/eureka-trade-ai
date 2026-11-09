# Design de confiabilidade — U1 `u1-legal-documents`

Conversa: português (Brasil). Atende `construction/u1-legal-documents/nfr-requirements/reliability-requirements.md` (NFR7.1 a NFR7.4).

## Padrões

| Situação | Comportamento | Requisito |
|---|---|---|
| Catálogo inválido ou sem versão ativável | Falha rápida na subida, com a causa na mensagem; o Compose mostra a API não saudável | NFR7.1 |
| Versão marcada não ativável | Vale a anterior ativável; `WARN` com versão e motivo | NFR7.2 |
| Versão vigente não publicável | Indicador `legalDocuments` em `DEGRADED` (HTTP 200), fora dos grupos de liveness e readiness, como o indicador de tarefas da auditoria | NFR7.3 |
| API fora do ar ou lenta para o app | A página mostra erro com "tentar de novo", sem nova tentativa automática e sem guardar o erro em cache | NFR7.4 |

## Sem dependências externas

A U1 não chama banco nem serviço externo para servir documentos. Por isso não precisa de circuit breaker nem de nova tentativa. A tabela de retenção vem da porta J5, montada na subida.
