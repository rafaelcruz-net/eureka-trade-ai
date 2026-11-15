# Projeto de confiabilidade — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes: `construction/u4-real-execution/nfr-requirements/reliability-requirements.md` (U4-NFR4.1 a U4-NFR4.7), `construction/u4-real-execution/functional-design/rules.md` e `nfr-design-questions.md` (Q1, Q5, Q7).

## Posse e compare-and-set

- **Relógio:** sempre o do banco (`now()` do PostgreSQL), para que as instâncias não dependam de relógio local.
- **Etapas do condutor** (`OrderDriver`, `ConfirmationJob`): cada etapa grava o resultado com `UPDATE real_order SET ..., lease_until = now() + 10 s WHERE id = ? AND state = ? AND lease_owner = ?`. A posse é renovada a cada etapa. Nenhuma etapa passa de 10 s: a maior soma 3 s de RPC com troca, mais a transação.
- **Pegar uma ordem:** `WHERE state = ? AND (lease_owner IS NULL OR lease_until < now())`, com `SKIP LOCKED`.
- **Cancelamentos externos** (`cancelUnsent`, troca de modo, `SigningDeadlineJob`, recusa na sessão) e a reivindicação da sessão (BR4.2): o compare-and-set é **só sobre o estado** (`WHERE id = ? AND state IN (...)`), sem olhar a posse. Assim um cancelamento atômico nunca espera o condutor. A próxima gravação do condutor encontra o estado mudado, não atualiza nada e para.
- **Assinatura descartada:** se o condutor já tiver a assinatura do TxSigner quando perder a corrida, ela é descartada sem envio, porque a ordem ainda não estava `SENT`.

| Requisito | Como o projeto atende |
|---|---|
| U4-NFR4.1 (uma transação) | A coluna `idempotency_key` é única. A mensagem não assinada e o resumo são gravados na passagem a `QUOTED`, antes de qualquer pedido de assinatura. Só a recotação da Automática total (BR3.2) pode regravá-los, e só enquanto a ordem nunca foi enviada ao TxSigner (`signing_requested_at` vazio). Depois do primeiro pedido de assinatura, a mensagem nunca mais muda. A retomada usa sempre essa mensagem gravada com o mesmo `signingRequestId`, e o TxSigner devolve a mesma assinatura. Se a cotação ou o blockhash dela vencerem, a ordem vira `FAILED`, sem gerar outra mensagem. A transação assinada e a `tx_signature` são gravadas na passagem a `SIGNED`. O envio lê a transação da coluna. |
| U4-NFR4.2 (compare-and-set) | As regras acima. Zero linhas atualizadas quer dizer que outro ganhou, e nada mais é feito. |
| U4-NFR4.3 (queda) | A posse vence em 10 s e a retomada parte do estado e da mensagem gravados. Os testes fecham o contexto Spring depois de cada gravação, inclusive entre a assinatura e a passagem a `SIGNED`, e sobem outro sobre o mesmo banco. |
| U4-NFR4.4 (intenção de envio) | `SENT` e o `OrderAttempt` numa transação, o envio depois, o resultado em seguida. Uma `SENT` sem resultado é tratada pelo `ConfirmationJob`: consulta pela assinatura e reenvia a mesma transação enquanto o blockhash vale. |
| U4-NFR4.5 (ambígua) | O `AmbiguousJob` só consulta; não existe reenvio de ambígua no código. |
| U4-NFR4.6 (fornecedor) | Circuito por fornecedor; contagens por usuário (`real_upstream_health`); pausa e retomada pela M1. O saque é da U1 e não passa por esses componentes. |
| U4-NFR4.7 (instâncias) | As varreduras usam ShedLock: ambíguas, prazo de assinatura, saúde e liquidação. Os trabalhos de ordem usam `SKIP LOCKED` com posse: consumidor, condutor e confirmação. O consumidor e a criação da liquidação usam também a trava da pessoa; sem a trava, a decisão continua `PENDING` para a próxima rodada. Todo trabalho publica `real_execution_job_runs_total{job}` ao terminar bem. |
