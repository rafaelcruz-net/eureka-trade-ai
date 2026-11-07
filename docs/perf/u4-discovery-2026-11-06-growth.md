# Carga local — U4 Descoberta e avaliação (2026-11-06)

- Cenário: `growth` · usuários: 20 · duração: 600 s · tokens por fonte: 10000
- Resultado: **só anotado (crescimento)**

| Rota | p95 | Limite |
|---|---|---|
| challenge | 44.739241 ms | 300 ms |
| verify | 134.217727 ms | 300 ms |
| list | 10021.590356 ms | 1000 ms |
| detail | 39.146836 ms | 500 ms |

- Espera do pool: máximo de 6 conexões pendentes; maior sequência 0 s (limite 10 s)
- Memória máxima: api 695 MB, postgres 990 MB (limite 900 MB)
- Plano do histórico sem `Seq Scan` em `observation_sample`: sim
- Erros de cliente: 0
