# Desenho de confiabilidade — U1 Esqueleto de ponta a ponta

Como a Unit `u1-walking-skeleton` cumpre os requisitos de [reliability-requirements.md](../nfr-requirements/reliability-requirements.md) (NFR10.1, NFR4.2, NFR1.3, NFR13.1, NFR13.2, NFR12.3), para os fluxos F5, F6 e F7 de [functional-spec.md](../functional-design/functional-spec.md) e o contrato C9 de [contract-summary.md](../../../inception/contract-design/contract-summary.md). Decisões em [nfr-design-questions.md](nfr-design-questions.md) (Q3, Q4, Q6, Q10).

## Relay da auditoria (NFR4.2)

Entrega ao menos uma vez, com desduplicação na trilha (Q3):

1. A ação sensível grava o evento na tabela de saída na mesma transação (C9). Se a ação desfaz, o evento some com ela.
2. A cada 1 s, o relay abre uma transação e reserva até 100 entradas com `delivered_at` nulo, em ordem de criação, usando `SELECT ... FOR UPDATE SKIP LOCKED`.
3. Para cada entrada, grava em `audit_event` com `event_id` único; um evento já gravado é ignorado (`INSERT ... ON CONFLICT (event_id) DO NOTHING`).
4. Marca `delivered_at` nas entradas e confirma a transação.

Se o processo cair entre os passos, a transação desfaz, as entradas continuam pendentes e voltam no próximo ciclo. Se cair depois do passo 3 numa entrega feita fora dessa transação (por exemplo, numa versão futura com trilha externa), o evento repetido é descartado pelo `event_id`. Resultado: cada evento chega à trilha exatamente uma vez, por entrega ao menos uma vez mais desduplicação.

Falhas do relay: erro de banco faz o ciclo terminar com log `ERROR` e o próximo ciclo tenta de novo; a métrica de pendências e idade da mais antiga mostra o atraso (ver [observability-design.md](observability-design.md)).

Teste de integração: derrubar o processo depois do commit da ação e antes do relay, subir de novo e conferir uma única linha na trilha; repetir com uma entrega forçada duas vezes e conferir a desduplicação.

## Fonte simulada (NFR1.3)

| Situação | Comportamento |
|---|---|
| Coleta normal | A cada 15 s (Q4); grava a observação vigente de cada campo com fonte e horário; fonte `OK` |
| Coleta falha | Fonte `FAILED`; as observações anteriores ficam com seus horários; nenhum valor vira zero |
| Idade acima de 5 min | A lista marca o campo como desatualizado (`stale`) |
| Erro repetido | Sem repetição imediata dentro do ciclo; o próximo ciclo tenta de novo. Log `WARN` por falha |

A coleta é recuperável por natureza (o próximo ciclo corrige), então não há disjuntor nem repetição com espera na U1. Fontes reais (U4) trazem disjuntor e repetição com espera por fonte.

## Idempotência (NFR4.1)

- Respostas guardadas com prazo por rota (Q6): o desafio, até expirar (5 min); as demais rotas, 24 horas.
- Limpeza agendada a cada hora apaga os registros vencidos em lotes de até 1.000, para não travar a tabela.

## Erros nas fronteiras

| Fronteira | Erro | Tratamento |
|---|---|---|
| Banco indisponível | Conexão recusada ou esgotamento do pool | `503` com `ProblemDetail`; saúde da API passa a `DOWN` |
| Assinatura inválida | Recusa de domínio | `401 LOGIN_CHALLENGE_REJECTED`, nonce consumido, evento `LOGIN_REJECTED` |
| Limite excedido | Recusa de domínio | `429` com `Retry-After` |
| Coleta simulada | Exceção do gerador | Fonte `FAILED`, log `WARN` |
| Relay | Erro de banco | Ciclo termina com `ERROR`; próximo ciclo tenta de novo |

Nenhum `catch` vazio: toda exceção é relançada com contexto, convertida em resultado explícito ou registrada.

## Saúde e subida (NFR13.1)

- API: verificação de saúde na porta de gestão (Q10), `UP` só depois de aplicar as migrações Liquibase e conectar ao banco.
- App do convidado: rota de saúde que responde quando o servidor Next.js está pronto.
- PostgreSQL: verificação de saúde do próprio contêiner.
- O Docker Compose usa essas verificações em `depends_on` com condição de saudável.
- O script `scripts/verify.sh` sobe o Compose, espera os três serviços saudáveis e a primeira coleta (limite de 60 s), roda o smoke e devolve código diferente de zero em qualquer falha.

## Portabilidade e determinismo (NFR13.2, NFR12.3)

- `.gitattributes` e `.editorconfig` fixam LF e UTF-8 (CRLF só em `*.cmd` e `*.bat`).
- Relógio injetável em todo o domínio; o gerador simulado usa semente fixa; nenhum teste acessa a rede real.
- Testes de integração usam as mesmas migrações sobre PostgreSQL em contêiner, sem estado compartilhado nem dependência de ordem.
- Teste instável é defeito e nunca é ignorado.

## Disponibilidade e recuperação (NFR10.1)

- Sem SLO de disponibilidade na U1; definido com a hospedagem, antes dos convites.
- RTO local: subir de novo com o script de verificação. RPO local: o volume do banco persiste entre reinícios; sem backup.
- Rollback: voltar ao commit anterior e subir de novo; migrações em expand-contract.
