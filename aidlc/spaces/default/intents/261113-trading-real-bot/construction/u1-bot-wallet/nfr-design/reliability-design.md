# Desenho de confiabilidade — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes: `construction/u1-bot-wallet/nfr-requirements/reliability-requirements.md` (NFR4.x), `construction/u1-bot-wallet/functional-design/rules.md` (BR1.10, BR4.7, BR4.9), `inception/contract-design/contract-summary.md` (tempos limite) e `nfr-design-questions.md` (Q1, Q2, Q5). Revisado depois da primeira revisão.

## Proteção das chamadas (Q1)

Os circuitos contam só **falhas técnicas**: tempo esgotado, erro de conexão e respostas 5xx ou 429. Recusas de negócio (4xx, como `SIGNING_POLICY_REFUSED` ou `CONSENT_INVALID`) nunca abrem circuito, para que recusas provocadas de propósito não bloqueiem os saques de todos. Cada janela é por tempo, para funcionar com pouco tráfego.

| Destino | Tempo limite | Repetição | Circuito | Com o circuito aberto |
|---|---|---|---|---|
| TxSigner | 2 s | 1 vez, com o mesmo `signingRequestId` ou `requestId` | janela de 60 s, mínimo de 5 chamadas, abre com 50% de falhas técnicas, fica aberto 30 s | saque continua em REQUESTED e é tentado de novo; ativação responde 503 e o desafio volta a PENDING |
| RPC (por provedor) | 3 s | troca para o outro provedor | janela de 60 s, mínimo de 5, 50% | o outro provedor assume; com os dois abertos, o worker espera |
| KMS (no TxSigner) | 1 s por chamada | nenhuma | janela de 60 s, mínimo de 3, 50% | o TxSigner responde 503 |

As bibliotecas são o Resilience4j que o projeto já usa (retry e timelimiter), com o módulo de circuit breaker, que é novo.

## Trabalhos

| Trabalho | Processo | Frequência | Coordenação | Detalhe |
|---|---|---|---|---|
| Varredura de depósitos | worker | 15 s | ShedLock (uma execução por vez) | por cursor; usa a parte de depósitos do limite do RPC |
| Acompanhamento de saques | worker | 1 s | só `SKIP LOCKED`, sem ShedLock, para permitir mais de uma instância | pega até 20 saques prontos (`nextAttemptAt` no passado) e trata até 4 em paralelo, cada um de forma independente; as confirmações saem numa única consulta `getSignatureStatuses` em lote por ciclo |
| Desafio preso | worker | 10 s | ShedLock | IN_USE há mais de 30 s volta a PENDING (NFR4.7) |
| Limpeza de nonces | TxSigner | 1 h | ShedLock no banco do TxSigner | apaga os nonces vencidos há mais de um dia |
| Órfãs: chave sem política | TxSigner | diária | ShedLock no banco do TxSigner | chaves com `app = eureka-botwallet` sem WalletPolicy; com mais de 10 min geram alerta |
| Órfãs: política sem carteira | worker | diária | ShedLock | o worker chama `GET /internal/v1/wallets?createdBefore=` no TxSigner e compara com as carteiras da API |
| Reconciliação após restauração | worker | sob comando | ShedLock | ver "Restauração do banco da aplicação" |

O Withdrawal ganha as colunas técnicas `attempts` e `nextAttemptAt`, que controlam a espera crescente: 5 s, dobrando até 5 min (NFR4.2). Elas são acréscimos ao desenho funcional, sem mudar regra.

## Saque: regras de tempo e de rede

- **REQUESTED:** o blockhash pode ser renovado antes da assinatura (NFR4.10). Depois de 24 h sem assinatura, o saque vira FAILED e a reserva é liberada.
- **SIGNED, SENT e AMBIGUOUS:** só a conferência na rede encerra o saque. O saque vira **FAILED** só quando as três condições valem juntas:
  - os dois provedores respondem;
  - os dois não encontram a assinatura em finalized;
  - os dois informam altura de bloco acima de `lastValidHeight`.

  Se um provedor não responde ou discorda, o saque continua AMBIGUOUS. Isso impede liberar a reserva por causa de um provedor atrasado.
- **Reinício:** o worker retoma os saques não terminados a partir dos estados gravados (NFR4.6).

## Backup e restauração (NFR4.8, NFR4.9, NFR4.13, Q5)

- **Backup na nuvem:** recuperação a um ponto no tempo nos dois bancos (RPO de 5 min), mais uma cópia diária guardada por 30 dias.
- **Tempo de volta:** RTO de 4 h; o ensaio mira 2 h. As duas restaurações são ensaiadas antes da mainnet.
- **Desastre:** restauração completa é tratada como desastre. As respostas 5xx desse período contam no SLI e podem estourar o orçamento de erro do mês, o que leva a uma revisão pós-incidente.
- **Banco do TxSigner restaurado:** entra o modo de recuperação de `security-design.md`.

### Restauração do banco da aplicação

1. O worker sobe em modo de reconciliação. Pedidos novos de saque ficam pendentes; leitura e pedidos continuam aceitos.
2. O worker pede ao TxSigner os SigningRecords assinados desde o ponto restaurado (`GET /internal/v1/signatures?since=`).
3. Confere cada assinatura na rede, em finalized. Um saque que chegou à rede, mas não está no banco restaurado, é recriado como CONFIRMED, com lançamentos marcados como reconstrução.
4. A varredura de depósitos recomeça do cursor restaurado. Os depósitos já lançados são ignorados, porque a assinatura é a chave.
5. Ao fim, gera um pedido de reconciliação por carteira (ReconciliationMark) e libera os saques.

O contrato R1 ganha duas leituras internas, `GET /internal/v1/wallets` e `GET /internal/v1/signatures`, que serão registradas no Infrastructure Design.

## Verificação de saúde

- `/actuator/health` em cada processo.
- **TxSigner pronto:** quando o próprio banco responde e o KMS responde a `ListKeys`, o que funciona mesmo antes da primeira carteira.
- **Worker:** mostra o estado dos circuitos.

## Orçamento da ativação (NFR3.4)

| Etapa | Tempo |
|---|---|
| Conferência na API | 50 ms |
| Rede até o TxSigner | 20 ms |
| Conferência no TxSigner | 30 ms |
| `CreateKey` | até 1 s |
| `GetPublicKey` | até 1 s |
| Gravação | 50 ms |
| **Total** | cerca de 2,2 s no pior caso, sem repetição |

Com repetição, a meta de 3 s pode estourar. Nesse caso a ativação responde 503 e o mesmo desafio devolve a mesma carteira na próxima tentativa (BR1.10). A meta é medida com o KMS real antes da mainnet.
