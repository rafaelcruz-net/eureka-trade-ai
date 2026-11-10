# Instruções de testes de desempenho

Conversa: português (Brasil). As metas numéricas vêm de `construction/<unit>/nfr-requirements/performance-requirements.md` das Units U1 a U5.

## Ferramentas

| Ferramenta | O que mede | Onde roda |
|---|---|---|
| `scripts/load-test.sh` + `scripts/load/load-test.mjs` | p95 das rotas e das páginas, erros 5xx | local; `.github/workflows/load-test.yml` sob demanda |
| `scripts/perf-audit.sh` | U2: lotes do expurgo com 100 mil linhas e custo do HMAC (o script é do responsável) | só local |
| Testes `*LatencyIT`, `*IndexIT`, `EXPLAIN` com `enable_seqscan = off` | medições pequenas e planos no Failsafe | `mvnw verify` |

## Cenários de carga

```bash
export MEMETRADE_AUDIT_WALLET_HMAC_KEY=$(openssl rand -hex 32)
LOAD_SCENARIO=<cenário> SPRING_PROFILES_ACTIVE=test,loadtest bash scripts/load-test.sh
```

| Cenário | Metas | Unit |
|---|---|---|
| `legal` | `/termos` e `/privacidade` p95 ≤ 2 s; rotas `/api/v1/legal/**` p95 ≤ 300 ms; 0 respostas 5xx; `/novo-aceite` p95 ≤ 2 s | U1 NFR3.1 a 3.3; U3 NFR3.3 |
| `u1` | verificação do login p95 ≤ 300 ms com a chave HMAC | U2 NFR3.4 |
| `u3` | criação de conta com convite p95 ≤ 500 ms | U2 NFR3.4 |
| `reacceptance` | `POST /api/v1/terms/acceptances` p95 ≤ 500 ms com 20 convidados | U3 NFR3.1 |
| `data-requests` | criar, listar e abrir pedido p95 ≤ 500 ms; rotas administrativas p95 ≤ 1 s com 1000 pedidos; prévia do resumo p95 ≤ 1 s | U4 NFR3.1 a 3.3 |

O `load-test.yml` ainda não tem as opções `reacceptance` e `data-requests` (pendência do responsável); localmente o script já tem os dois casos.

## Medições na suíte

| Meta | Teste |
|---|---|
| U2 NFR3.5, NFR3.8: expurgo de sessões e busca por `wallet_hmac` indexados | `SessionPurgePerformanceIT`, `WalletAuditIT` |
| U3 NFR3.2: trava ≤ 5 ms (p95) | temporizador `memetrade.terms.reacceptance.check` no cenário `reacceptance` |
| U4 NFR3.4, NFR3.5: job de 15 minutos ≤ 30 s com 1000 pedidos; índices | `DataRequestTimersIT` |
| U5 NFR3.1: anonimização p95 ≤ 2 s (20 bots, 100 sessões, 50 feedbacks) | `AnonymizationLatencyIT` |
| U5 NFR3.2: cinco comandos indexados | `AnonymizationIndexIT` |

## Metas da U2 com o `perf-audit.sh`

U2 NFR3.1 (lote ≤ 500 ms p95), NFR3.2 (100 mil linhas em até 5 minutos) e NFR3.3 (HMAC ≤ 1 ms p95) são medidas pelo teste de desempenho do expurgo e pelo microteste do publicador na suíte. O `scripts/perf-audit.sh` com a chave gerada é do responsável do projeto.

## Regressão

Um p95 acima da meta reprova o cenário. A meta nunca é relaxada para passar.
