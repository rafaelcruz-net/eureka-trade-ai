# Componentes lógicos — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes: `inception/domain-design/components.md`, `inception/contract-design/contract-summary.md`, `construction/u1-bot-wallet/nfr-requirements/tech-stack-decisions.md` e os outros documentos deste estágio.

## Inventário

| Componente | Processo | Dono dos dados | Falha afeta | Isolamento |
|---|---|---|---|---|
| API (rotas R2) | api | banco da aplicação | ativação, leitura e pedido de saque | sem permissão no KMS; fala com o TxSigner só pela rede privada |
| Worker (depósitos, saques, desafio preso, órfãs sem carteira, reconciliação após restauração) | worker | banco da aplicação | detecção de depósitos e andamento de saques; nada se perde, fica pendente | ShedLock e `SKIP LOCKED`; o único que chama o RPC; sem permissão no KMS |
| TxSigner (assinatura, limpeza de nonces, órfãs sem política, recuperação) | txsigner (novo) | banco próprio (WalletPolicy, SigningRecord, nonces) | toda assinatura e criação de carteira | credencial e papel próprios; o único com acesso ao KMS; etiquetas da chave imutáveis |
| KMS | AWS (ou `kms-sim` só em teste) | chaves | assinatura | menor privilégio, CloudTrail |
| Rede Solana | RPC (dois provedores) ou validador local | — | depósitos e envio de saques | circuito por provedor |
| PostgreSQL | um servidor no local; dois bancos na nuvem | — | tudo | usuários separados |

## Domínios de falha e raio de impacto

| Falha | Impacto | Contenção |
|---|---|---|
| TxSigner fora do ar | sem ativação nova; saques ficam pedidos | circuito, nova tentativa e alerta (NFR5.11) |
| Um provedor de RPC fora do ar | nenhum | troca para o outro |
| Os dois provedores de RPC fora do ar | depósitos atrasam; saques esperam | cursor, novas tentativas e alerta |
| KMS fora do ar ou com limite | sem assinatura | 503 do TxSigner, nova tentativa e alerta (NFR5.19) |
| API invadida | não consegue sacar para outro destino nem assinar fora da política | política do TxSigner (BR5.1, BR5.3) |
| Banco do TxSigner perdido | assinatura parada até restaurar | RPO de 5 min, RTO de 4 h, modo de recuperação ancorado nas etiquetas da chave |
| Banco da aplicação perdido | leitura e saques parados até restaurar | RPO de 5 min, RTO de 4 h, reconciliação pelos SigningRecords do TxSigner |
| Um provedor de RPC atrasado | nenhum saque vira FAILED por engano | FAILED só com os dois provedores de acordo |

## Recursos compartilhados

- O servidor PostgreSQL no local é compartilhado, com esquemas e usuários separados.
- O worker e a API compartilham o banco da aplicação, como hoje.
- O limite do RPC é dividido: 80% para depósitos e 20% para saques.
