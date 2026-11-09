# Configuração de SLIs e SLOs

Conversa: português (Brasil). Respostas 1A a 8A de `observability-setup-questions.md`. Alvo implantado: a release local `v2026.11.08-1` (`operation/deployment-execution/deployment-log.md`). A AWS ainda está pendente, então nada aqui foi criado na nuvem.

Fontes: os `performance-design.md`, `reliability-design.md` e `security-design.md` de cada Unit (`construction/<unit>/nfr-design/`), os `monitoring-design.md` e `infrastructure-specification.md` de cada Unit (`construction/<unit>/infrastructure-design/`, U1 a U8; a U9 não tem desenho de infraestrutura próprio e usa as métricas das U5 e U7).

## Nível 1: metas das Units (SLIs medidos em teste)

Valem hoje. São medidas nos scripts de carga (`scripts/load*`), nos testes de integração e no `verify.sh`. Não são SLOs de produção.

| Unit | SLI | Meta | Onde se mede |
|---|---|---|---|
| U1 | p95 de desafio, verificação e lista | ≤ 300 ms com 20 usuários | carga U1, 5 min |
| U1 | frescor da fonte simulada | idade ≤ 60 s | `verify.sh` |
| U2 | entregas da auditoria em até 5 s do commit | 99% | `scripts/perf-audit.sh` (pendente de memória, ver Build and Test) |
| U2 | p95 da consulta da trilha | ≤ 2 s | `scripts/perf-audit.sh` |
| U2 | pendência mais antiga do outbox | < 60 s | smoke e integração |
| U3 | p95 da verificação com convite | ≤ 500 ms | carga U3, 20 simultâneas |
| U3 | p95 do convite e da tela Conta | ≤ 300 ms | carga U3, 5 min |
| U4 | p95 da lista | ≤ 1 s com 20 usuários e 2 000 tokens | carga U4, 10 min (cenário `growth` pendente da réplica de leitura) |
| U4 | p95 do detalhe | ≤ 500 ms | carga U4 |
| U4 | recálculo de risco em dia | último sucesso ≤ 5 min | saúde `riskRecalculation` |
| U5 | p95 do ciclo dos bots | ≤ 2 s | cenário `u5` |
| U5 | evento no canal ao vivo | ≤ 4 s após o commit | integração e cenário `u5` |
| U6 | p95 de `evaluate` | ≤ 250 ms com 60 bots | cenário `u6`, 10 min |
| U6 | lote do expurgo | ≤ 100 ms | cenário `u6` |
| U7 | p95 de `execute` | ≤ 250 ms | cenário `u7`, 10 min |
| U7 | p95 de P&L e ledger | ≤ 1 s | cenário `u7` |
| U7 | pessoas com divergência na reconciliação | 0 | cada rodada |
| U8 | `PUT ON` do kill switch com 60 bots | p95 ≤ 5 s | integração |
| U8 | envio de feedback | p95 ≤ 500 ms | integração |

## Nível 2: proposta de SLO para a produção do beta PAPER

**Situação: proposta.** Ela é confirmada pelo responsável do projeto quando houver hospedagem, antes dos convites (G1). Até lá, não existe SLO de disponibilidade (NFR10.1 e NFR10.6).

| SLO | SLI (fórmula) | Meta | Janela | Orçamento de erro |
|---|---|---|---|---|
| Disponibilidade da API | requisições com status < 500 ÷ todas as requisições de `/api/v1/**`, excluindo 4xx de domínio | 99,5% | móvel de 30 dias | 0,5%, cerca de 3 h 36 min de indisponibilidade equivalente |
| Latência de leitura | requisições GET de `/api/v1/**` com duração ≤ 1 s ÷ todas as GET | 95% | móvel de 28 dias | 5% das leituras |
| Latência de escrita de trading PAPER | `paper.execute` com duração ≤ 250 ms ÷ todas | 95% | móvel de 28 dias | 5% |
| Frescor da fonte de mercado | minutos com `market_source_last_collection_age_seconds` ≤ 60 ÷ minutos totais | 99% | móvel de 30 dias | cerca de 7 h 12 min |
| Atraso da auditoria | minutos com `audit_outbox_oldest_age_seconds` < 60 ÷ minutos totais | 99,9% | móvel de 30 dias | cerca de 43 min |

## Alertas de consumo do orçamento

Os limites ficam abaixo do estouro do SLO, para dar tempo de agir (regra da fase de operação).

| Alerta | Condição | Severidade |
|---|---|---|
| Consumo rápido | taxa de consumo ≥ 14,4× em 1 h **e** em 5 min (2% do orçamento de 30 dias em 1 h) | Alta |
| Consumo lento | taxa de consumo ≥ 6× em 6 h **e** em 30 min (5% do orçamento em 6 h) | Média |
| Orçamento quase no fim | orçamento restante < 25% | Média; congela releases que não sejam correção |

## Política

- Com o orçamento esgotado, só entram releases de correção ou de confiabilidade até o orçamento voltar.
- Os SLOs são revistos depois de 30 dias de dados reais.
- Nenhuma meta acima pode ser afrouxada para fazer uma etapa passar.
