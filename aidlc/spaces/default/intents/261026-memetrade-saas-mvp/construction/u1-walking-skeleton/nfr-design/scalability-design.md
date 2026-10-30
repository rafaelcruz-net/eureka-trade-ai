# Desenho de escala — U1 Esqueleto de ponta a ponta

Como a Unit `u1-walking-skeleton` atende aos requisitos de [scalability-requirements.md](../nfr-requirements/scalability-requirements.md) (NFR3.3, NFR3.4, NFR3.5), com a topologia de processos de [contract-summary.md](../../../inception/contract-design/contract-summary.md) (C20) e os fluxos de [functional-spec.md](../functional-design/functional-spec.md). Decisões em [nfr-design-questions.md](nfr-design-questions.md) (Q1, Q5).

## Arquitetura de escala

| Aspecto | Beta local (U1) | Caminho para crescer |
|---|---|---|
| Instâncias de API | 1 | Escala horizontal: a API não guarda sessão em memória (a sessão está no banco), então novas instâncias só exigem mover os contadores do limite |
| Sessão | No PostgreSQL, lida por chave primária a cada requisição | Igual; um cache curto da sessão só se a leitura passar do orçamento de 20 ms |
| Contadores do limite | Memória do processo de API | Redis antes de subir a segunda instância (NFR3.5) |
| Origem do IP | Conexão direta (Q1) | Com balanceador, cabeçalho só de proxies confiáveis da lista configurada |
| Coleta simulada | Agendador no processo de API | Com mais de uma instância, a coleta precisa de um único executor (bloqueio no banco ou mudança para o worker da U5) |
| Relay da auditoria | Processo de API (C20), com reserva de linhas que pula linhas já reservadas | Funciona com várias instâncias sem mudança, porque cada lote é reservado por uma só |
| Banco | Um PostgreSQL local, pool de 10 conexões | Pool revisto com o número de instâncias |

## Capacidade

| Dimensão | Meta da U1 | Margem do desenho |
|---|---|---|
| Usuários simultâneos | 20 (NFR3.3) | Cerca de 10 pedidos por segundo contra pool de 10 conexões e consultas de até 150 ms |
| Tokens | Até 500 com 5 campos (NFR3.4) | Uma observação vigente por token, campo e fonte; índice pela ordem da lista; páginas de até 100 |
| Eventos de auditoria | Dezenas por minuto | Lotes de até 100 por segundo, várias vezes acima do volume |

## Dados

- Observação vigente: uma linha por token, campo e fonte, atualizada a cada coleta; o histórico de observações não é guardado na U1.
- Retenção de 24 horas para tokens sem nova observação (ADR-008), com limpeza agendada.
- Respostas de idempotência com prazo por rota e limpeza em lotes (ver [reliability-design.md](reliability-design.md)).
- Sem particionamento na U1; o volume é pequeno.

## Degradação

- Quando o pool de conexões esgota por mais de 2 s, a requisição falha com `503` e `ProblemDetail`, em vez de esperar além do tempo limite do cliente (10 s).
- Login e lista degradam juntos, porque compartilham o processo de API.

## Gatilhos para revisar

- Mais de uma instância de API: mover contadores para Redis e garantir um executor único da coleta.
- p95 da lista acima de 200 ms no teste de carga: rever índice e considerar cache curto.
- Fontes reais (SP-1) com mais de 500 tokens: rever volume, retenção e paginação.
