# Desenho de escala — U4 Descoberta e avaliação

Como a U4 cumpre [scalability-requirements.md](../nfr-requirements/scalability-requirements.md), com o achado R-04 de [review-01.md](../nfr-requirements/reviews/review-01.md) resolvido.

## Arquitetura de escala do beta

- **Um processo de API** (Spring Boot) com os módulos `marketdata`, `scanner` e `riskassessment`; um PostgreSQL; nenhum cache externo (NFR2.7).
- **Trabalhos agendados** (coleta por fonte, recálculo, limpeza) no mesmo processo, cada um com bloqueio ShedLock próprio (`market-collect-<sourceId>`, `risk-recalculate`, `market-history-cleanup`), para continuarem corretos com mais de uma instância no futuro.
- **Pool do agendador:** 4 linhas de execução (2 coletas, 1 recálculo, 1 limpeza), separado das linhas das requisições; somado às 3 da U2, o pool do agendador da aplicação passa a 7.

## Histórico com resolução de 5 minutos (NFR2.4, NFR2.5)

- Intervalo de 5 minutos UTC calculado por `date_trunc` de 5 minutos sobre `observed_at`; coluna `bucket_start` em `observation_sample` com restrição única `(chain, mint, source_id, field, bucket_start)`.
- A coleta grava a amostra com `INSERT ... ON CONFLICT DO NOTHING`: a primeira observação do intervalo fica e as demais só atualizam o valor atual.
- **Teto (achado R-04):** com retenção efetiva de até 25 horas (24 h mais o intervalo da limpeza horária), o histórico tem no máximo 2 000 × 2 × 4 × 300 = 4,8 milhões de linhas. Estimativa: cerca de 110 bytes por linha mais índices, menos de 2 GB.
- `CREATED_AT` não entra no histórico (não muda; o valor atual basta).

## Variação de 1 hora com intervalos de 5 minutos

A janela da BR1.10 (60 a 65 minutos atrás) contém exatamente um início de intervalo; a consulta pega a amostra `KNOWN` de `PRICE` com `bucket_start` nessa janela, de qualquer fonte não `FAILED`, a mais recente.

## Crescimento para 10 000 tokens (NFR3.5)

Antes do G1, o script de carga roda com 10 000 tokens. Se a lista passar de 1 s no p95, a primeira alternativa registrada é a tabela de leitura (opção B da Q3 da NFR Design), que não muda o contrato; a segunda é uma réplica de leitura com a hospedagem.

## Limite por instância (NFR2.8)

Registrado como risco aceito no beta (security-design.md); com mais de uma instância, o limite passa para um armazenamento compartilhado (por exemplo, uma tabela ou Redis), decisão da Infrastructure Design da hospedagem.

## Política de degradação

Igual à de [scalability-requirements.md](../nfr-requirements/scalability-requirements.md): limpeza atrasa primeiro, recálculo junta gatilhos, `429` acima do limite, coleta nunca é pulada por causa de consultas (só pela coleta anterior ainda em andamento).
