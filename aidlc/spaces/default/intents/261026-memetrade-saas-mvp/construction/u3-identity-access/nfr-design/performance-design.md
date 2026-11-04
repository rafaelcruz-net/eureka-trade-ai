# Desenho de desempenho — U3 Convites, contas e sessões

Como a Unit `u3-identity-access` atende a [performance-requirements.md](../nfr-requirements/performance-requirements.md) (NFR3.1 a NFR3.4 da U3).

## Decisões

| Decisão | Detalhe | Requisito |
|---|---|---|
| Verificação com convite numa transação curta | Nenhuma chamada externa dentro da transação: a assinatura é conferida em memória (Ed25519 da U1) antes do bloqueio do convite; o bloqueio da linha dura só as conferências e as cinco inserções | NFR3.1 |
| Buscas por índice | `invite.code_digest` (único), `wallet.address` (único), `invite(created_at desc, invite_id)` para a lista, `app_user(role, status)` para as contagens | NFR3.1, NFR3.2, NFR3.3 |
| Termos em memória | O arquivo dos termos é lido uma vez na subida e mantido em memória; a consulta do convite não lê disco | NFR3.2 |
| Tela Conta em uma consulta | Pessoa, carteira, sessão e aceite numa consulta com junções pela chave da pessoa; sem cache, porque o volume é pequeno e o dado é pessoal | NFR3.2 |
| Lista paginada | Cursor por `(created_at, invite_id)`, `limit` até 100; estado derivado calculado em memória com um único instante por página | NFR3.3 |
| Página do convite renderizada no cliente | A página `/convite/[codigo]` carrega o esqueleto e busca o convite na API; termos são texto simples, sem biblioteca de Markdown | NFR3.4 |

## Orçamento da verificação com convite (p95 500 ms)

| Etapa | Orçamento | Como se garante |
|---|---|---|
| Assinatura e consumo do desafio | 60 ms | Verificação em memória; `UPDATE` do desafio pela chave |
| Bloqueio e conferências | 60 ms | `SELECT … FOR UPDATE` pelo índice do resumo; carteira pelo índice único |
| Inserções (pessoa, carteira, aceite, convite usado, evento) | 120 ms | Inserções simples na mesma conexão |
| Sessão | 60 ms | Mesmo caminho da U1 |
| Confirmação | 50 ms | — |
| Serialização e folga | 150 ms | — |

## Medição

- `scripts/load-test.sh` ganha três cenários: verificação com convite (20 verificações simultâneas, cada uma com convite e carteira de teste próprios, criados pelo código de teste), consulta do convite e tela Conta (20 usuários, uma ação a cada 2 s, 5 minutos).
- A carga roda com os perfis `test` e `loadtest`, que elevam os limites por IP da entrada e da consulta do convite; sem isso quase tudo seria `429` e o p95 não mediria a rota.
- Rotas administrativas: teste de integração com 10 ações seguidas e marcação de tempo.
- Telas: Playwright marca o tempo até os termos ou os dados da conta ficarem visíveis.
