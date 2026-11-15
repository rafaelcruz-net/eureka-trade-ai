# Monitoramento — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes: `construction/u1-bot-wallet/nfr-design/observability-design.md`; `construction/u1-bot-wallet/nfr-requirements/observability-requirements.md` e `reliability-requirements.md`; `infrastructure-specification.md` (recuperação e CloudTrail); `infrastructure-design-questions.md` (Q5, Q8).

## Coleta

- **Local:** Micrometer em `/actuator/prometheus` nos três processos. As regras de alerta ficam em `infra/observability/` no formato do Prometheus e são validadas na CI com `promtool check rules`.
- **Nuvem:** Micrometer com o registro do CloudWatch (`micrometer-registry-cloudwatch2`). Os alarmes são do CloudWatch e definidos no Terraform, com as mesmas condições das regras locais. O custo é bem menor que o de um Prometheus gerenciado [Q8].
- **Rota dos alarmes:** tópico SNS `eureka-alerts`. O e-mail do fundador recebe todos os alarmes; os de severidade crítica e alta também vão por SMS para o celular do fundador.
- **Incidente:** todo alarme crítico pede a abertura de um registro em `docs/incidents/`, seguindo o runbook do Incident Response. A mensagem do alarme traz o link do runbook.
- **Eventos do CloudTrail:** uma regra do EventBridge envia ao mesmo tópico SNS os eventos administrativos do KMS. A criação de chaves é contada por um filtro de métrica do CloudWatch Logs sobre a trilha.

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| `realwallet_active` | worker | — | tamanho do piloto |
| `realwallet_balance_lamports_total` | worker | acima de 210 SOL: informativo | exposição total |
| `realwallet_deposits_total{source}` | worker | — | origem dos depósitos |
| `realwallet_unknown_deposit_over_threshold_total` | worker | aumentou | depósito estranho acima de 0,001 SOL |
| `realwallet_withdrawal_oldest_pending_seconds{state}` | worker | AMBIGUOUS > 300 s; qualquer estado > 600 s | saques parados |
| `realwallet_withdrawal_seconds` | worker | p95 > 60 s | tempo até a confirmação |
| `txsigner_decisions_total{kind,decision,reason}` | txsigner | recusa de WITHDRAWAL > 0; recusa de consentimento > 5 por hora | ataque ou erro |
| `txsigner_latency_seconds` | txsigner | p95 > 1 s | desempenho do TxSigner (NFR5.5) |
| `txsigner_auth_failures_total{status,caller}` | txsigner | > 5 em 5 min | credencial errada ou tentativa de acesso |
| `txsigner_credential_age_days` | txsigner | ≥ 80 | giro |
| `kms_sign_seconds`, `kms_errors_total{type}` | txsigner | > 3 erros em 5 min | dependência crítica |
| `realwallet_orphan_keys` | txsigner e worker | > 0 | chave sem carteira |
| `txsigner_recovery_blocked_keys` | txsigner | > 0 | rede ou MAC divergente na recuperação |
| `txsigner_recovery_pending_seconds` | txsigner | > 600 | recuperação sem os rebaixamentos da API |
| `rpc_provider_up{provider}` | worker | 0 por mais de 30 min | decidir o modo de um provedor só |
| `up` / presença de métrica | coletor | processo sem métricas por 2 min | processo fora do ar |
| `http_server_requests` 5xx | api | SLI mensal | SLO 99,5% |

## Alerts

| Alert | Condition | Severity | Routes to |
|---|---|---|---|
| Recusa de saque no TxSigner | `txsigner_decisions_total{kind="WITHDRAWAL",decision="REFUSED"}` aumentou | crítica | SNS: e-mail e SMS; incidente |
| Chave órfã | `realwallet_orphan_keys` > 0 | crítica | SNS: e-mail e SMS; incidente |
| Chave bloqueada na recuperação | `txsigner_recovery_blocked_keys` > 0 | crítica | SNS: e-mail e SMS; incidente |
| TxSigner fora do ar | sem métricas do TxSigner por 2 min (local: `up{job="txsigner"} == 0`; nuvem: alarme de dados ausentes) | crítica | SNS: e-mail e SMS; incidente |
| Worker fora do ar | sem métricas do worker por 2 min | crítica | SNS: e-mail e SMS; incidente |
| Ação administrativa no KMS | regra do EventBridge sobre o CloudTrail | crítica | SNS: e-mail e SMS; incidente |
| Saque ambíguo | mais antigo em AMBIGUOUS > 300 s | alta | SNS: e-mail e SMS |
| Saque parado | mais antigo de qualquer estado > 600 s | alta | SNS: e-mail e SMS |
| Falhas de credencial | 401/403 > 5 em 5 min | alta | SNS: e-mail e SMS |
| KMS com erro | > 3 em 5 min | alta | SNS: e-mail e SMS |
| Criação de chaves na conta | > 10 por hora (filtro de métrica do CloudTrail) | alta | SNS: e-mail e SMS |
| Recuperação parada | `txsigner_recovery_pending_seconds` > 600 | alta | SNS: e-mail e SMS |
| Latência do TxSigner | p95 > 1 s por 10 min | média | e-mail |
| Recusas de consentimento | > 5 por hora | média | e-mail |
| Depósito de origem desconhecida | `realwallet_unknown_deposit_over_threshold_total` aumentou | média | e-mail |
| Provedor de RPC fora | > 30 min | média | e-mail pedindo decisão |
| Credencial antiga | idade ≥ 80 dias | baixa | e-mail |

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| Fração de pedidos de leitura da carteira e de saque sem 5xx | 99,5% | 30 dias corridos |
| p95 de leituras da carteira | 300 ms | 7 dias |
| p95 do pedido de saque | 500 ms | 7 dias |

## Logs & Tracing

| Item | Desenho |
|---|---|
| Formato | JSON nos três processos, com `correlationId` propagado no cabeçalho até o TxSigner; endereços e assinaturas sempre mascarados |
| Destino | Local: saída padrão. Nuvem: CloudWatch Logs, com 30 dias de retenção e criptografia |
| CloudTrail | Bucket próprio, com bloqueio de objeto e 1 ano de retenção. Não guarda o endereço da carteira principal, porque a etiqueta da chave é um MAC |

## Teste de carga (NFR3.9)

O teste de carga roda no Compose local, com o validador e o `kms-sim`, antes do B4. Antes da mainnet, roda na pilha da devnet com o KMS real. É uma medição informativa, sem portão.

## Dashboard "Carteiras REAL"

Painel do CloudWatch, na nuvem, ou Grafana local, opcional. Mostra:

- carteiras ativas e saldo total;
- depósitos por origem;
- saques por estado e tempo até a confirmação;
- decisões e latência do TxSigner;
- a latência do KMS;
- a situação da recuperação e dos provedores de RPC;
- os alarmes abertos;
- o SLI do mês.
