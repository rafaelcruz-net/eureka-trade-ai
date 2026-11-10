# Perguntas de NFR Requirements — U2 `u2-retention`

Conversa: português (Brasil). Todas as respostas são as recomendadas (A), conforme o pedido do responsável do projeto em 2026-11-10: "Siga sempre as recomendações sem me pergunta".

## Q1 — Desempenho dos expurgos e da auditoria

A. Metas:
- cada lote de até 1000 linhas termina em até 500 ms (p95), medido no log do expurgo;
- uma execução com 100 mil linhas vencidas termina em até 5 minutos;
- o cálculo do HMAC e a checagem de `details` acrescentam no máximo 1 ms (p95) à publicação de um evento;
- o login mantém o p95 de hoje.

(recomendado)
B. Sem meta numérica.
X. Other (please specify)

[Answer]: A

## Q2 — Conexão usada pelos expurgos do Identity

A. A conexão principal do Identity, que é dono das tabelas `login_challenge`, `user_session` e `session_used_refresh_digest`. Elas não são append-only e não têm gatilho de proteção. A trava ShedLock usa `lockAtMostFor` de 30 minutos. (recomendado)
B. Um papel de banco próprio de retenção com gatilho, como o do feedback.
X. Other (please specify)

[Answer]: A

## Q3 — Métricas dos expurgos novos (NFR8)

A. Três métricas no `/actuator/prometheus`, só com o rótulo `purge`:
- `memetrade_purge_deleted_total{purge}`;
- `memetrade_purge_runs_total{purge,result}`;
- `memetrade_purge_last_success_seconds{purge}`.

Os logs trazem só totais e duração. (recomendado)
B. Só o log.
X. Other (please specify)

[Answer]: A

## Q4 — Volume do beta

A. Até 20 convidados simultâneos e cerca de 100 logins por dia. São poucas centenas de desafios e sessões por dia, então o lote de 1000 é folgado. O teste de desempenho do expurgo usa 100 mil linhas para ter margem. (recomendado)
B. Sem projeção.
X. Other (please specify)

[Answer]: A

## Q5 — Disponibilidade e recuperação

A. Uma falha do expurgo nunca derruba a API nem o login. A saúde fica `DEGRADED` só depois de 48 h sem sucesso. A recuperação na subida roda o expurgo atrasado há mais de 24 h. A chave HMAC ausente impede a subida (falha rápida, BR2.4). (recomendado)
B. Outra política.
X. Other (please specify)

[Answer]: A
