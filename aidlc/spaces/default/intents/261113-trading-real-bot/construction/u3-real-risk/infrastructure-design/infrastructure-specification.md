# Especificação de infraestrutura — U3 `u3-real-risk`

Conversa: português (Brasil). Fontes:
- `construction/u3-real-risk/nfr-design/` (`logical-components.md`, `scalability-design.md`, `reliability-design.md`);
- `construction/u1-bot-wallet/infrastructure-design/` e `construction/u2-real-market-data/infrastructure-design/`;
- `team.md` (Deployment);
- `infrastructure-design-questions.md` (Q1 a Q4).

## Compute

| Componente | Onde roda | Mudança |
|---|---|---|
| `evaluate` do ciclo, trabalhos F3, F4 e F5, medidor de reservas | worker (tarefa que já existe) | nenhuma na infraestrutura [Q1] |
| Liquidação, `blockToken`, `unblockToken`, `RealRiskPurgeJob` | API (tarefa que já existe) | nenhuma na infraestrutura [Q1] |

## Banco

**Migração Liquibase nova**, na sequência do `db.changelog-master.yaml`, só de expansão. Cria `real_risk_evaluation`, `real_risk_reservation`, `real_risk_day`, `real_blocked_token` e `real_risk_purge_run`, com os índices de `scalability-design.md`.

**Privilégios.** As permissões padrão do `db-bootstrap` dão ao login da aplicação SELECT, INSERT, UPDATE e DELETE em toda tabela nova. Por isso a migração ajusta cada tabela como a `0009` fez:

| Tabela | Aplicação | `risk_retention` | Sempre revogado |
|---|---|---|---|
| `real_risk_evaluation` | SELECT, INSERT | SELECT, DELETE | UPDATE e DELETE da aplicação; TRUNCATE, REFERENCES e TRIGGER de todos; ALL de PUBLIC; TRUNCATE do dono |
| `real_risk_reservation` | SELECT, INSERT, UPDATE | SELECT, DELETE | DELETE da aplicação; TRUNCATE, REFERENCES e TRIGGER de todos; ALL de PUBLIC; TRUNCATE do dono |
| `real_risk_day` | SELECT, INSERT | SELECT, DELETE | UPDATE e DELETE da aplicação; o mesmo resto |
| `real_blocked_token` | SELECT, INSERT (`ON CONFLICT DO NOTHING`, para o bloqueio repetido não mudar nada), DELETE (desbloqueio) | nenhum | UPDATE; o mesmo resto |
| `real_risk_purge_run` | nenhum | SELECT, INSERT, DELETE (registros com mais de 90 dias) | UPDATE; o mesmo resto |

**Gatilhos:**
- `BEFORE DELETE` em `real_risk_evaluation`, `real_risk_reservation` e `real_risk_day`: recusa linha com menos de 13 meses. A coluna de idade é `evaluated_at`, `created_at` e `taken_at`, respectivamente.
- `BEFORE UPDATE` em `real_risk_reservation`: só deixa mudar `state` (de `OPEN` para `HANDED_OVER` ou `EXPIRED`) e `closed_at`; qualquer outra coluna ou transição é recusada.
- `BEFORE UPDATE` nas tabelas só de inserção, e `BEFORE TRUNCATE` em todas: recusam sempre, como na `0009`.

**Registro do expurgo.** O expurgo REAL grava as execuções em `real_risk_purge_run`, com a mesma forma de `risk_purge_run`: resultado `SUCCEEDED` ou `FAILED`, contagens e causa da falha. Não usa a tabela da `0009`, para não mexer nela.

**Testes e rollback:**
- Um teste de integração confere os privilégios: a aplicação não consegue UPDATE nem DELETE onde não deve, o `risk_retention` não apaga linha nova, e TRUNCATE falha.
- Cada changeset tem bloco de rollback (apagar gatilhos, revogar, apagar tabelas). Ele serve só para desenvolvimento; em produção as tabelas ficam (expand-contract).

**Conexões:**
- A trava (`WalletLock`) usa a conexão da transação do ciclo, do pool da aplicação, e a espera de até 1 s segura essa conexão. Com cerca de 4 avaliações por segundo e o pool padrão do worker, sobra folga.
- O pool `risk_retention` (uma conexão) atende o expurgo PAPER das 03:30 e o REAL das 04:00, um depois do outro e com nomes distintos no ShedLock. Se o PAPER ainda estiver rodando às 04:00, o REAL espera a conexão e roda em seguida.

## Rede e segredos

- Nenhuma rede nova: a barreira não chama nada fora do banco.
- Nenhum segredo novo.

## Configuração

- `memetrade.real.risk.*`: os cinco limites de `security-requirements.md`, com os padrões no `application.yml`.
- No `.env.example`, as chaves só aparecem comentadas.
- Fora de `test` e `local`, a subida recusa valor mais frouxo.

## Local

- O Compose e o `verify.sh` não mudam [Q1].
- A barreira só é exercitada de ponta a ponta quando a U4 passar a chamá-la; até lá, os testes de integração cobrem a U3.

## Implantação e rollback [Q4]

**Ordem:**
1. migração;
2. worker;
3. API.

A barreira entra sem efeito em ordem nenhuma, porque a U4 ainda não chama a M5.

**Rollback:**
1. desligar o REAL (`memetrade.real.enabled` e `txsigner.real.enabled`) ou acionar o kill switch REAL;
2. voltar a imagem.

As tabelas ficam (expand-contract). O saque continua disponível o tempo todo. Uma implantação só termina com o smoke test verde (`team.md`).

**Durante o rollback:**
- Voltar para uma imagem sem a U3 faz o alerta de trabalho parado disparar. O runbook `docs/runbooks/real-risk.md`, escrito na Code Generation, manda silenciá-lo durante o rollback.
- Até a U4 chamar a barreira, o smoke test não a exercita; a cobertura vem dos testes de integração do job `backend`.
