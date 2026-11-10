# Perguntas de NFR Design — U2 `u2-retention`

Conversa: português (Brasil). Todas as respostas são as recomendadas (A), pelo pedido do responsável do projeto em 2026-11-10: "Siga sempre as recomendações sem me pergunta".

## Q1 — Onde o HMAC é calculado

A. No publicador da auditoria (`JdbcAuditEventPublisher`), por um componente `WalletHasher` do Audit que recebe a chave da configuração na subida. O comando de auditoria ganha o campo `wallet` (endereço válido), que nunca é serializado. Os chamadores só preenchem esse campo. (recomendado)
B. Num componente do Identity, antes de publicar.
X. Other (please specify)

[Answer]: A

## Q2 — Execução dos expurgos novos

A. Uma classe por expurgo no Identity (`LoginChallengePurgeJob`, `SessionPurgeJob`), com um executor comum do Identity no padrão do `AuditJobRunner`: trava, lotes, gravação da execução, métricas e evento. A recuperação na subida usa o mesmo executor. (recomendado)
B. Uma classe genérica no pacote compartilhado para todos os módulos.
X. Other (please specify)

[Answer]: A

## Q3 — Onde fica o teste de conformidade

A. No módulo `privacy`, como teste de unidade que monta o catálogo e recebe as declarações de cada módulo pela configuração real deles (`ApplicationContextRunner` só com as classes de configuração das declarações) e lê o `retention.tsv` do classpath. Sem banco. (recomendado)
B. Como teste de integração com o contexto inteiro.
X. Other (please specify)

[Answer]: A
