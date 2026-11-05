# Requisitos de segurança e integridade — U6 Barreira de limites

Requisitos de segurança e de integridade financeira da Unit `u6-risk-control`. As fontes são:
- as regras de [rules.md](../functional-design/rules.md) e os fluxos de [functional-spec.md](../functional-design/functional-spec.md);
- o NFR4 a NFR8 de [requirements.md](../../../inception/requirements-analysis/requirements.md);
- os contratos C9, C11, C13, C14 e C21 de [contract-summary.md](../../../inception/contract-design/contract-summary.md);
- as regras do `project.md` e do `team.md`.

As decisões vêm de [nfr-requirements-questions.md](nfr-requirements-questions.md) (Q3 e Q5).

## Classificação dos dados da U6

| Dado | Classe | Onde aparece | Proteção |
|---|---|---|---|
| Avaliações e recusas de ordens PAPER | Pessoal e financeiro (simulado) | Banco, rota C21 | Só a própria pessoa lê (tenant e pessoa do token). Registros só de inserção, com uma única exceção de expurgo (NFR4.3) |
| Valores medidos, limites e dados de mercado da recusa | Interno da pessoa | Banco, rota C21 | Os mesmos controles |
| Identificadores de bot e decisão | Referência | Banco | Só como identificador; a U6 não lê tabelas da U5 |

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR4.1 | Nenhuma ordem acima dos limites | Catálogo E1 a E21 escrito antes do código, cada linha no instante da fronteira e um incremento depois; mais teste de propriedade com 1.000 combinações aleatórias de seed fixa confirmando que uma ordem aprovada nunca passa de nenhum limite (teste obrigatório de limites) | NFR4, AC4.6.1, AC4.7.1, AC4.7.2 |
| NFR4.2 | Sem ponto flutuante | Teste de arquitetura: nenhum `double`, `float`, `Double` ou `Float` no pacote `riskcontrol`; colunas de valor `numeric`; JSON em `DecimalString` | `project.md` |
| NFR4.3 | Registros imutáveis | Privilégios do papel da aplicação pela matriz abaixo; gatilhos recusam `UPDATE` em todas as tabelas da U6 e `DELETE` em `order_rejection` e `rejection_reason_detail` para qualquer papel. Um teste com o papel da aplicação para cada linha da matriz | BR4.3, Q5 |
| NFR4.4 | Uma avaliação por decisão | Restrição única (tenant, modo, bot, decisão) em `order_evaluation`. Teste com a mesma decisão em sequência, em 2 threads ao mesmo tempo (50 repetições) e depois de rollback: uma avaliação, a segunda vira `DUPLICATE`, sem erro de banco (teste obrigatório de deduplicação) | BR2.8, E8, E20, E21 |
| NFR4.5 | Dado ausente nunca vira zero | E5, E6, E13, E18 e E19: recusa com medido `UNKNOWN`; teste de arquitetura sem `BigDecimal.ZERO` como substituto de `MarketValue` desconhecido no pacote (teste obrigatório de `unknown`) | BR3.2, C-05 |
| NFR5.1 | Só a própria pessoa vê as recusas | Toda consulta da C21 filtra por tenant, pessoa e modo do token. Testes negativos: bot de outra pessoa já avaliado, bot de outra pessoa nunca avaliado, bot de outro tenant e bot inexistente respondem `404` e nunca o dado | BR4.1, DP-1, NFR5 |
| NFR5.2 | Tenant do contexto no ciclo | `evaluate` grava tenant e pessoa só do contexto de autenticação; um teste com uma ordem de outra pessoa no contexto confirma que a exceção da C13 desfaz tudo e nada é gravado | BR1.1 |
| NFR5.3 | Só PAPER | `CHECK mode = 'PAPER'` em todas as tabelas da U6; toda consulta filtra `mode = 'PAPER'` (teste obrigatório de separação REAL e PAPER) | `project.md`, FR10.6 |
| NFR6.1 | Nunca assinar nem enviar transação | Teste de arquitetura: o pacote `riskcontrol` não depende de cliente HTTP, SDK Solana, assinatura nem roteador de swap; não depende do pacote de execução da U7 além da interface `PortfolioRiskView` (teste obrigatório de REAL desligado) | FR9.2, `project.md` |
| NFR6.2 | Só leitura na API | O OpenAPI do convidado tem só `GET /api/v1/bots/{botId}/rejections` para a U6. Teste de contrato falha se aparecer outro método sob essa rota | BR4.2, C21 |
| NFR6.3 | Entrada validada | `botId` em UUID; `cursor` opaco e assinado; `limit` de 1 a 100; parâmetro desconhecido recusado com `400 VALIDATION_FAILED`, sem SQL nem stack trace; consultas sempre parametrizadas | Regras comuns |
| NFR6.4 | Sem limite de requisições próprio | A rota C21 não tem limite por pessoa (Q3). A proteção contra abuso é a autenticação e a paginação limitada a 100 itens | Q3 |
| NFR7.1 | Nada sensível nos logs nem na trilha | Logs da U6 usam só identificadores (`rejectionId`, `botId`, `decisionId`); o evento `ORDER_REJECTED` não traz endereço de carteira, token de sessão nem cabeçalho `Authorization`. A varredura de logs da U1 (`LogScanIT`) cobre um ciclo com recusa | NFR7, `project.md` |
| NFR8.1 | Recusa reproduzível | Para qualquer recusa gravada, reavaliar com os `MarketInputs`, a visão de risco e a versão dos limites gravados dá os mesmos motivos e gatilhos | NFR8, BR3.5 |

## Matriz de privilégios do papel da aplicação (NFR4.3)

| Tabela | INSERT | SELECT | UPDATE | DELETE | Restrição no banco |
|---|---|---|---|---|---|
| `order_evaluation` | sim | sim | não | só o expurgo | Gatilho recusa `UPDATE`. `DELETE` só de `outcome = APPROVED` com `evaluated_at` mais de 30 dias antes e sem recusa que a referencie (a chave estrangeira impede o resto) |
| `order_rejection` | sim | sim | não | não | Gatilho recusa `UPDATE` e `DELETE` para qualquer papel |
| `rejection_reason_detail` | sim | sim | não | não | Gatilho recusa `UPDATE` e `DELETE` para qualquer papel |

`TRUNCATE` é revogado nas três tabelas.

O expurgo das aprovadas refina a BR4.3 do design funcional, que dizia "no beta não há expurgo". A resposta Q5 desta etapa abriu essa única exceção. As recusas e os detalhes continuam sem expurgo no beta.

## Ameaças consideradas (STRIDE)

| Categoria | Ameaça | Controle |
|---|---|---|
| Falsificação | Pessoa consulta recusas de outro bot pelo `botId` | Filtro por pessoa e `404` uniforme (NFR5.1) |
| Adulteração | Apagar ou mudar uma recusa para esconder uma violação | Banco recusa `UPDATE` e `DELETE` (NFR4.3); sem rota de escrita (NFR6.2) |
| Adulteração | Executar uma ordem sem passar pela barreira | `OrderGate` é a única entrada da U6; a U5 chama a execução só com `Approved` (C14, ADR-005); teste de arquitetura da U5 confere a ordem das chamadas |
| Repúdio | Negar que uma ordem foi barrada | Evento `ORDER_REJECTED` por recusa na trilha (observability-requirements.md) |
| Divulgação | Descobrir se um bot alheio existe | Resposta `404` igual para todos os casos sem avaliação da pessoa (NFR5.1) |
| Negação de serviço | Pedir páginas grandes em laço | Paginação até 100 itens e índice próprio (NFR6.3, scalability-requirements.md) |
| Elevação de privilégio | Operação REAL | Só PAPER no banco e nas consultas; nada assina (NFR5.3, NFR6.1) |

## Conformidade

- **Sem promessa de lucro.** Os textos dos motivos que a tela da U5 mostra passam pelo catálogo de termos proibidos da U4.
- **Dados pessoais.** As recusas ligam-se a uma pessoa. A retenção sai do inventário de dados (SP-3), como nas outras Units; no beta, as recusas não são apagadas (Q5).
