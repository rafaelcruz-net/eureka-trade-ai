# Plano de testes de carga

Conversa: português (Brasil). Respostas 1A a 4A de `performance-validation-questions.md`. Metas dos `performance-requirements.md` e `scalability-requirements.md`; como medir, dos `performance-design.md` e `scalability-design.md` (`construction/<unit>/nfr-requirements/` e `nfr-design/`). Leitura em produção pelo painel "Privacidade e termos" (`operation/observability-setup/dashboards.md`).

## Cenários

Comando: `LOAD_SCENARIO=<cenário> SPRING_PROFILES_ACTIVE=test,loadtest bash scripts/load-test.sh` (local) ou o workflow manual `load-test.yml`.

| Cenário | Perfil | Metas (p95) | Units |
|---|---|---|---|
| `legal` | 20 usuários, 5 min, páginas e rotas legais | páginas ≤ 2 s; API ≤ 300 ms; 0 5xx; `/novo-aceite` ≤ 2 s | U1, U3 |
| `u1` | 20 usuários, 5 min, login com HMAC | verificação ≤ 300 ms | U2 |
| `u3` | 20 convites, 5 min | conta com convite ≤ 500 ms | U2 |
| `reacceptance` | rajada de 20 pessoas desatualizadas | aceite ≤ 500 ms; trava ≤ 5 ms | U3 |
| `data-requests` | 20 convidados e 1000 pedidos semeados, 5 min | convidado ≤ 500 ms; administrador ≤ 1 s; prévia ≤ 1 s | U4 |

## Medições na suíte (`mvnw verify`)

| Meta | Teste |
|---|---|
| Expurgo: lote ≤ 500 ms; 100 mil linhas ≤ 5 min; por índice | `SessionPurgePerformanceIT` (U2) |
| HMAC ≤ 1 ms | `WalletHmacOverheadTest` (U2) |
| Job de 15 min ≤ 30 s com 1000 pedidos; índices | `DataRequestTimersIT` (U4) |
| Anonimização ≤ 2 s; cinco consultas indexadas | `AnonymizationLatencyIT`, `AnonymizationIndexIT` (U5) |

## Escalabilidade

Sem auto escala: um único computador. Os `scalability-design.md` pedem uma só execução de cada expurgo e job entre instâncias (ShedLock, testado) e nenhum estado em memória na trava e no aceite (testado). A validação com mais de uma réplica da `api` fica para a AWS.

## Pendente para um ambiente parecido com produção

1. Rodar os cinco cenários na AWS (staging) com o catálogo real dos termos.
2. Rodar o `scripts/perf-audit.sh` da U2 (cerca de 37 milhões de eventos, 30 GB de disco) num ambiente com memória suficiente.
3. Validar o orçamento de conexões com `DB_POOL_MAX_SIZE` × réplicas da `api`.
