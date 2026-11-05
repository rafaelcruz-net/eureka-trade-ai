# Design de segurança — U6 Barreira de limites

Como a Unit `u6-risk-control` atende NFR4.1 a NFR4.5, NFR5.1 a NFR5.3, NFR6.1 a NFR6.4, NFR7.1 e NFR8.1 de [security-requirements.md](../nfr-requirements/security-requirements.md), com a resposta Q1 de [nfr-design-questions.md](nfr-design-questions.md).

## Papéis do banco (NFR4.3, Q1)

Segue o padrão da U2 para a retenção da trilha (`infra/postgres/bootstrap/10-roles.sql`):

| Papel | Tipo | O que pode nas tabelas da U6 |
|---|---|---|
| Papel da aplicação (`DB_USER`) | Login da API e do worker | `INSERT` e `SELECT` em `order_evaluation`, `order_rejection` e `rejection_reason_detail`; nenhum `UPDATE`, `DELETE` nem `TRUNCATE`. A migração da U6 revoga o que os privilégios padrão concedem |
| `risk_retention` | Papel de grupo sem login, nome fixo | `SELECT` e `DELETE` só em `order_evaluation`; nada nas outras duas tabelas |
| Login de retenção (`DB_RETENTION_USER`) | Login já existente da U2 | Passa a ser membro de `risk_retention` além de `audit_retention`. O preparo dos papéis (`10-roles.sql`) cria `risk_retention` e faz a concessão, de forma idempotente |
| Dono do esquema | Só migrações | Cria as tabelas, índices e gatilhos |

A tarefa de expurgo usa a conexão de retenção que a U2 já criou para a purga da trilha. A aplicação não tem como apagar uma avaliação.

## Gatilhos (NFR4.3)

| Tabela | Gatilho |
|---|---|
| `order_evaluation` | Recusa `UPDATE` para qualquer papel. Recusa `DELETE` quando: o papel atual não é membro de `risk_retention`; `outcome` não é `APPROVED`; ou `evaluated_at` não é anterior a agora menos 30 dias. A chave estrangeira de `order_rejection.evaluation_id` (sem `ON DELETE`) impede apagar uma avaliação citada por qualquer recusa |
| `order_rejection` e `rejection_reason_detail` | Recusam `UPDATE` e `DELETE` para qualquer papel |

`TRUNCATE` é revogado nas três tabelas. Um teste com cada papel confere o permitido e o recusado em cada linha da matriz.

## Alteração formal da BR4.3 (pedida pela revisão dos requisitos)

A geração de código segue esta versão da BR4.3, no lugar do texto do design funcional:
- **Imutabilidade.** Avaliações e recusas não aceitam `UPDATE`. Recusas e detalhes não aceitam `DELETE` no beta.
- **Única exceção.** Avaliações `APPROVED` com mais de 30 dias e sem nenhuma recusa que as cite são apagadas pela tarefa de expurgo, com o papel `risk_retention` (Q5 dos requisitos, Q1 deste design).
- **Aprovada citada por duplicidade.** Uma aprovada citada por uma recusa `DUPLICATE` nunca é apagada. Essas linhas ficam fora da projeção de volume e são raras: só aparecem por defeito da U5.
- **Rota de recusas.** Um bot cujas avaliações foram todas apagadas responde `404` (BR4.1), como um bot ainda não avaliado. A tela do bot (U5) trata esse `404` como "nenhuma recusa". Isso tem teste na U6 e é avisado à U5 na tabela de mudanças de contrato do design funcional.
- **Decisão antiga reavaliada.** Uma decisão com mais de 30 dias avaliada de novo não é reconhecida pela U6. A segunda execução é contida pela chave de idempotência da U7. Um teste de integração apaga a avaliação aprovada, reavalia a decisão e confirma que a U7 devolve `Duplicate` e que não há segunda ordem.

## Isolamento (NFR5.1 a NFR5.3)

- **Ciclo.** O tenant e a pessoa vêm do `AuthenticationContext` (C10) que a U5 liga no ciclo; a U6 nunca os lê da ordem nem dos limites.
- **Rota C21.** Toda consulta tem `tenant_id`, `owner_user_id` e `mode = 'PAPER'` do token na cláusula; nenhuma consulta procura avaliações de outra pessoa. Sem avaliação do bot para a pessoa, a resposta é `404 NOT_FOUND`.
- **Testes.** Cobrem bot de outra pessoa avaliado, nunca avaliado, de outro tenant, inexistente e com avaliações todas apagadas.
- **Expurgo.** É a única tarefa que atravessa tenants. Ela só apaga linhas de `order_evaluation`, nunca lê dados de outra Unit e não devolve nem registra nada por pessoa: o log traz só o total.
- **Só PAPER.** `CHECK (mode = 'PAPER')` nas três tabelas.

## Validação de entrada e API (NFR6.2 a NFR6.4)

- **Validação.** `botId` é validado como UUID pelo framework. `limit` vai de 1 a 100. O cursor é assinado e recusado quando adulterado. Um parâmetro desconhecido é recusado. Os erros saem em `ProblemDetail`, sem SQL nem stack trace.
- **Só leitura.** O controlador só expõe `GET`, e o teste de contrato do OpenAPI falha com outro método.
- **Sem limite de requisições** próprio (Q3 dos requisitos).

## Arquitetura e REAL desligado (NFR4.2, NFR4.5, NFR6.1)

Um teste de arquitetura (ArchUnit) sobre `com.memetrade.riskcontrol` confere que o pacote:
- não depende de cliente HTTP, SDK Solana, assinatura nem roteador de swap;
- não depende de `com.memetrade.ledger` (só de `com.memetrade.pnl.api.PortfolioRiskView`) nem de pacotes internos de outras Units;
- não tem `double`, `float`, `Double` nem `Float`;
- não usa `BigDecimal.ZERO` no lugar de um valor `MarketValue.Unknown`.

## Logs e trilha (NFR7.1, NFR8.1)

- **Logs.** Trazem só `rejectionId`, `botId`, `decisionId` e o motivo principal.
- **Evento.** O `ORDER_REJECTED` traz os detalhes do BR3.5, sem carteira e sem segredo.
- **Varredura.** O `LogScanIT` da U1 roda sobre um ciclo de teste com recusa.
- **Reprodução.** A recusa guarda `MarketInputs`, os detalhes e a versão dos limites. O teste de reprodução reavalia a partir desses dados e da visão de risco gravada no teste.
