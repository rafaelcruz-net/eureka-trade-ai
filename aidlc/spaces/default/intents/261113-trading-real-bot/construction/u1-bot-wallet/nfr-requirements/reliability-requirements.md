# Requisitos de confiabilidade — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes: `construction/u1-bot-wallet/functional-design/functional-spec.md` e `rules.md` (BR1.10, BR3.1, BR4.7, BR4.9), `inception/requirements-analysis/requirements.md` (NFR4), `inception/contract-design/contract-summary.md` (Q9, X3) e `nfr-requirements-questions.md` (Q6, Q10).

## Disponibilidade

| ID | Requisito |
|---|---|
| NFR4.1 | Meta do piloto: 99,5% ao mês para leitura da carteira e pedido de saque, medida como a fração de pedidos sem resposta 5xx nas métricas da API em 30 dias corridos [Q6] |
| NFR4.2 | Com o TxSigner ou o RPC fora do ar, o worker tenta de novo com espera crescente: começa em 5 s, dobra a cada tentativa e para de crescer em 5 min; o usuário é avisado. Um saque em REQUESTED (nunca assinado) que passa 24 h sem conseguir assinatura vira FAILED, com a reserva liberada e um alerta. Um saque em SIGNED, SENT ou AMBIGUOUS **nunca** é liberado pelo tempo: só a conferência na rede o leva a CONFIRMED ou FAILED (BR4.7) |
| NFR4.10 | O blockhash só é renovado enquanto o saque está em REQUESTED, antes de pedir a assinatura. Depois de assinado, a transação nunca é assinada de novo. Se o blockhash vencer antes do envio, o saque vira FAILED (com a reserva liberada), e o usuário pode pedir outro saque |
| NFR4.11 | Uma falha do RPC atrasa o saque, que é repetido, mas nunca o recusa; a pausa de novas ordens por falha do RPC fica na U4 |
| NFR4.12 | Alerta de saque parado: um saque que não chega a CONFIRMED nem a FAILED em 10 min |

## Integridade do dinheiro

| ID | Requisito |
|---|---|
| NFR4.3 | A taxa do saque é calculada antes de pedir a assinatura: a taxa base por assinatura mais a prioridade, com a prioridade em microlamports por unidade de compute vezes o limite de unidades, dividida por um milhão e arredondada para cima, sempre até o teto de taxa. Ela é gravada no saque e conferida pelo TxSigner. A reserva é o valor mais a taxa calculada; no saque total, valor mais taxa é exatamente o livre, o que zera a carteira e nunca deixa o livre negativo [Q10] |
| NFR4.4 | O blockhash, o `lastValidHeight` e o resumo da mensagem são gravados antes do pedido de assinatura; a transação assinada é guardada e todo reenvio a usa; uma recusa REQUEST_MISMATCH leva o saque a FAILED, com a reserva liberada (a primeira transação nunca foi enviada) [Q10] |
| NFR4.5 | Nenhuma operação gera transferência duplicada: idempotência no pedido, uma assinatura por saque, depósito registrado uma vez por assinatura |
| NFR4.6 | Os estados gravados sobrevivem a reinício; o worker retoma saques em REQUESTED, SIGNED, SENT e AMBIGUOUS depois de reiniciar |
| NFR4.7 | Um desafio IN_USE volta a PENDING depois de 30 s sem resposta do TxSigner, para a API que caiu no meio não travar a ativação [Q10] |

## Recuperação

| ID | Requisito |
|---|---|
| NFR4.8 | Os bancos do TxSigner e da aplicação têm backup contínuo com recuperação a um ponto no tempo: perda máxima de dados (RPO) de 5 min e tempo de volta (RTO) de 4 h na nuvem, mais uma cópia diária guardada por 30 dias. A restauração dos dois é testada antes da mainnet [Q6] |
| NFR4.9 | Perder o banco do TxSigner não perde fundos, porque as chaves estão no KMS. Até a restauração, a assinatura fica parada e os saques ficam pedidos, sem ser recusados, dentro do RTO. Ninguém tem o saque negado por regra de negócio; o atraso só existe dentro da disponibilidade de NFR4.1 |
| NFR4.13 | Depois de restaurado, o TxSigner entra em modo de recuperação e segue três passos. (a) Espera pelo menos 150 blocos (cerca de 2 min) antes de assinar qualquer coisa: uma assinatura perdida na restauração tinha blockhash que já venceu, então a regra de uma assinatura por saque continua valendo. (b) Recebe da API os rebaixamentos vigentes (LOWER_*) e aplica a política mais restrita entre a restaurada e a declarada; ampliações nunca são reaplicadas sem novo consentimento. (c) Os nonces perdidos não abrem repetição, porque todo consentimento vence em 5 min. O procedimento vai para o runbook do Incident Response |
