# Plano de carga

Conversa: português (Brasil). Respostas 1A a 5A de `performance-validation-questions.md`. Metas dos `performance-requirements.md` e `scalability-requirements.md`, e desenho dos `performance-design.md` e `scalability-design.md` de cada Unit. Evidência coletada pelas métricas de `operation/observability-setup/dashboards.md`.

## Ambientes

| Ambiente | Situação | Uso |
|---|---|---|
| Local (notebook Windows, Docker, limites de 1 GB e 512 MB por contêiner) | existe; cargas rodadas no Build and Test | regressão de desempenho por Unit; não representa a produção |
| Staging na AWS (parecido com produção, banco gerenciado com réplica de leitura) | pendente | validação antes do G1, com CloudWatch e X-Ray como evidência |

## Perfis de tráfego

| Perfil | Carga | Duração | Ferramenta |
|---|---|---|---|
| Regime normal | 20 usuários simultâneos, uma ação a cada 2 s, nas telas e rotas do beta | 10 min por cenário | `scripts/load-test.sh <cenário>` (`u1`, `u3`, `u4`, `u5`, `u6`, `u7`) |
| Pico de bots | 60 bots PAPER ativos, um ciclo por fatia | 10 min | cenários `u5`, `u6`, `u7`, `u7-jobs` |
| Rajada de login | 20 verificações simultâneas com convite | até terminar | cenário `u3` |
| Crescimento | 10 000 tokens na janela do scanner | 600 s | cenário `growth` |
| Auditoria contínua | publicação em todas as Units com relay ativo | 1 h | `scripts/perf-audit.sh` |
| Resistência (só no staging) | regime normal mais 60 bots | 2 h | cenários combinados |

## Critérios

- **Aprovação:** o p95 de cada NFR dentro da meta (`nfr-validation-matrix.md`). O p50 e o p99 são registrados no relatório de cada cenário.
- **No staging:** o p99 deve ficar em até 3× a meta do p95 da rota. Na resistência, a memória não pode crescer depois do GC, nenhum pool pode ficar pendente por mais de 2 s e não pode haver 5xx.
- **Vazão:** a dos requisitos (20 usuários, 60 bots, 99% da auditoria em até 5 s, rodadas do ledger em até 10 s, reconciliação em até 60 s). Sem meta em requisições por segundo até haver dados reais do beta.
- Nenhuma meta é afrouxada para um cenário passar. Teste instável é tratado como defeito.

## Gargalos acompanhados

| Gargalo | Sinal | Painel | Ação prevista |
|---|---|---|---|
| Lista da U4 com muitos tokens | p95 de `GET /api/v1/scanner/tokens`, CPU e memória do postgres | Visão geral; Domínio → scanner | réplica de leitura para as consultas do scanner (decisão de 2026-11-08) |
| Relay da auditoria sob carga contínua | `audit_outbox_oldest_age_seconds`, `audit_delivery` | Domínio → auditoria | confirmar no `perf-audit`; ajustar o lote do relay se a meta falhar |
| Memória dos contêineres e do notebook | `docker stats`, heap da JVM | Visão geral | rodar um cenário por vez localmente; no staging, dimensionar pelo resultado da resistência |
| Pool do HikariCP sob rajada | `hikaricp_connections_pending` | Visão geral → pool | tamanho do pool e `503` controlado já desenhados |

## Execução no staging (antes do G1)

1. Subir o staging com a release candidata e a réplica de leitura.
2. Rodar `u1`, `u3`, `u4`, `u5`, `u6`, `u7` e `u7-jobs` com o endereço do staging, um por vez.
3. Rodar `growth`: lista p95 ≤ 1 s com 10 000 tokens fecha o U4-NFR3.5.
4. Rodar `perf-audit.sh`: fecha os U2-NFR3.1, NFR3.3 e NFR4.2.
5. Rodar a resistência de 2 h.
6. Registrar os resultados e os gráficos do CloudWatch e do X-Ray em `docs/perf/` e atualizar `nfr-validation-matrix.md`.

Os dados de carga são sempre sintéticos: sem carteira, mint ou dado pessoal real.
