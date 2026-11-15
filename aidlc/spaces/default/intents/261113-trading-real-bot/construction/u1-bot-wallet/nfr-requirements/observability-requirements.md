# Requisitos de observabilidade — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes: `construction/u1-bot-wallet/functional-design/functional-spec.md`, `inception/requirements-analysis/requirements.md` (NFR5), `inception/contract-design/contract-summary.md` e `nfr-requirements-questions.md` (Q9).

## Métricas

| ID | Métrica |
|---|---|
| NFR5.1 | Carteiras do bot ativas |
| NFR5.2 | Saldo total em carteiras do bot, em lamports |
| NFR5.3 | Depósitos, separados por origem conhecida e desconhecida |
| NFR5.4 | Saques por estado e tempo do pedido à confirmação (p50 e p95) |
| NFR5.5 | Assinaturas e recusas do TxSigner por motivo; latência do TxSigner |
| NFR5.6 | Chaves órfãs encontradas pela verificação diária |
| NFR5.14 | KMS: latência do Sign, recusas por limite (throttling) e erros |
| NFR5.15 | Respostas 401 e 403 do TxSigner por origem |
| NFR5.16 | Pedidos de reconciliação deixados pela U1 (ReconciliationMark); a métrica e o alerta de divergência entre ledger e rede são entregues pela U5, que roda a reconciliação |

## Alertas

| ID | Condição | Limite |
|---|---|---|
| NFR5.7 | Recusa do TxSigner | qualquer recusa de WITHDRAWAL (o normal é zero); recusas de consentimento só acima de 5 por hora |
| NFR5.17 | 401 ou 403 no TxSigner | mais de 5 em 5 min |
| NFR5.18 | Saque parado (NFR4.12) | sem CONFIRMED nem FAILED em 10 min |
| NFR5.19 | KMS com recusa por limite ou erro | mais de 3 em 5 min |
| NFR5.8 | Saque ambíguo | há mais de 5 min |
| NFR5.9 | Depósito de origem desconhecida | acima de 0,001 SOL; os menores entram num alerta agregado por dia |
| NFR5.10 | Chave órfã | qualquer uma |
| NFR5.11 | TxSigner fora do ar | mais de 2 min |

## Logs e rastreio

| ID | Requisito |
|---|---|
| NFR5.12 | Logs estruturados com `correlationId`; endereços e assinaturas sempre mascarados; sem segredo, token ou nonce |
| NFR5.13 | O `correlationId` atravessa API, worker e TxSigner |

## SLI e SLO

O SLI é a fração de pedidos de leitura e de saque respondidos sem erro 5xx. O SLO é 99,5% ao mês (NFR4.1). O alerta de consumo do orçamento de erro é definido no Observability Setup.
