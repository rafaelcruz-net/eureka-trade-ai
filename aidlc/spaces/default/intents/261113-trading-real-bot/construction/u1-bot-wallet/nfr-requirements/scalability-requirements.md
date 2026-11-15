# Requisitos de escala — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes: `construction/u1-bot-wallet/functional-design/functional-spec.md` (F3), `inception/requirements-analysis/requirements.md` (NFR3, NFR9), `inception/contract-design/contract-summary.md` (X3, X4) e `nfr-requirements-questions.md` (Q7).

## Carga

| ID | Requisito |
|---|---|
| NFR3.6 | Piloto com até 21 carteiras do bot; o desenho e os testes de carga consideram 1.000 carteiras ativas [Q7] |
| NFR3.7 | A varredura de depósitos faz uma consulta por carteira, limitada pela taxa configurada do RPC (`rpcMaxRps`): 5 por segundo no plano gratuito e 50 por segundo no plano pago exigido antes de passar de 100 carteiras. Duração do ciclo = carteiras ÷ `rpcMaxRps`. Com até 75 carteiras no plano gratuito, o ciclo fica em até 15 s; com 1.000 carteiras no plano pago, em até 20 s. As carteiras com tela de depósito aberta nos últimos 30 min entram em todo ciclo, à frente das outras. O cursor garante que nenhum depósito se perde, mesmo com ciclo mais longo |
| NFR3.9 | Perfil de carga do teste: 1.000 carteiras, 10 leituras por segundo e 1 pedido de saque por segundo, por 10 min. É uma medição informativa antes da mainnet, não um portão da CI (`team.md`: sem portão de desempenho) |
| NFR3.10 | No piloto, o TxSigner roda numa instância; o limite de assinaturas por segundo do KMS (cota da conta) fica acima da carga prevista de 1 assinatura por segundo; acima de 100 carteiras, a escala é revista |
| NFR3.8 | Saques concorrentes de carteiras diferentes não se bloqueiam (a trava é por carteira) |

## Crescimento de dados

- Depósitos, saques, lançamentos e registros de assinatura crescem com o uso e não são apagados (ledger e auditoria são só inclusão).
- Os nonces usados são apagados depois de `purgeAfter`.
- Volume estimado no piloto: menos de 10.000 linhas por mês.

## Custo (NFR9)

| ID | Requisito |
|---|---|
| NFR9.1 | Custo do KMS no piloto: cerca de US$ 1 por chave por mês (21 chaves, cerca de US$ 21) mais as assinaturas (cerca de US$ 0,03 a cada 10 mil). Somado ao RPC e aos dados, fica dentro do teto de US$ 100 por mês. O custo é revisto antes de passar de 100 carteiras, quando o RPC pago entra |
