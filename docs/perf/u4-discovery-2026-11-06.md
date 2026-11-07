# Carga local — U4 Descoberta e avaliação (2026-11-06)

- Cenário: `u4` · usuários: 20 · duração: 600 s · tokens por fonte: 2000
- Resultado: **aprovado**

| Rota | p95 | Limite |
|---|---|---|
| challenge | 33.554431 ms | 300 ms |
| verify | 44.739241 ms | 300 ms |
| list | 500 ms | 1000 ms |
| detail | 11.184809 ms | 500 ms |

- Espera do pool: máximo de 6 conexões pendentes; maior sequência 0 s (limite 10 s)
- Memória máxima: api 461 MB, postgres 825 MB (limite 900 MB)
- Plano do histórico sem `Seq Scan` em `observation_sample`: sim
- Erros de cliente: 0
