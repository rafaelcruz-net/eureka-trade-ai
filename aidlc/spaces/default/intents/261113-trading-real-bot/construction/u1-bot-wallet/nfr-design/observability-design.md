# Desenho de observabilidade — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes: `construction/u1-bot-wallet/nfr-requirements/observability-requirements.md` (NFR5.x), `construction/u1-bot-wallet/nfr-requirements/reliability-requirements.md` (NFR4.1, NFR4.12) e `nfr-design-questions.md` (Q8).

## Coleta

- Micrometer e Prometheus em `/actuator/prometheus` nos três processos: API, worker e TxSigner [Q8].
- Nomes das métricas:
  - `realwallet_active`;
  - `realwallet_balance_lamports_total`;
  - `realwallet_deposits_total{source=known|unknown}`;
  - `realwallet_withdrawals_total{state}`;
  - `realwallet_withdrawal_seconds` (histograma até a confirmação);
  - `txsigner_decisions_total{kind,decision,reason}`;
  - `txsigner_latency_seconds`;
  - `txsigner_auth_failures_total{status}`;
  - `kms_sign_seconds`;
  - `kms_errors_total{type}`;
  - `realwallet_orphan_keys`;
  - `realwallet_reconciliation_marks_total`;
  - `realwallet_withdrawal_oldest_pending_seconds{state}` (medidor da idade do saque mais antigo não terminado em cada estado);
  - `realwallet_unknown_deposit_over_threshold_total` (contador de depósitos de origem desconhecida acima de 0,001 SOL) e `realwallet_unknown_deposit_lamports` (histograma do valor);
  - `txsigner_credential_age_days` (medidor da idade da credencial de serviço vigente);
  - `txsigner_auth_failures_total{status,caller}`, em que `caller` é a identidade do certificado (`api`, `worker`) ou `unknown`.
- Os rótulos nunca levam endereço, assinatura, tenant nem usuário.

## Logs e rastreio

- Logs em JSON com `correlationId`, que vem do cabeçalho `X-Correlation-Id` ou é gerado. O worker o grava no saque e o repassa ao TxSigner (NFR5.13).
- O filtro de máscara roda antes da escrita (NFR5.12).

## Alertas (regras versionadas, ativadas no Observability Setup)

| ID | Regra |
|---|---|
| NFR5.7 | `txsigner_decisions_total{kind="WITHDRAWAL",decision="REFUSED"}` aumentou; recusas de consentimento acima de 5 por hora |
| NFR5.8 | `realwallet_withdrawal_oldest_pending_seconds{state="AMBIGUOUS"}` > 300 |
| NFR5.9 | `realwallet_unknown_deposit_over_threshold_total` aumentou; resumo diário pelo histograma |
| NFR1.5 | `txsigner_credential_age_days` ≥ 80 |
| NFR5.10 | `realwallet_orphan_keys` maior que 0 |
| NFR5.11 | TxSigner sem resposta de saúde por mais de 2 min |
| NFR5.17 | mais de 5 respostas 401 ou 403 do TxSigner em 5 min |
| NFR5.18 | o maior valor de `realwallet_withdrawal_oldest_pending_seconds` entre os estados > 600 |
| NFR5.19 | mais de 3 erros ou recusas por limite do KMS em 5 min |

## Painel "Carteiras REAL"

O painel mostra:
- carteiras ativas e saldo total;
- depósitos por origem;
- saques por estado e tempo até a confirmação;
- decisões do TxSigner por motivo e a latência do KMS;
- alertas abertos;
- o SLI do mês: a fração de pedidos de leitura e de saque sem 5xx, comparada à meta de 99,5% (NFR4.1).
