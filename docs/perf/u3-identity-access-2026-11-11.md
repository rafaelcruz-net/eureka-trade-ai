# Carga local — U3 Convites, contas e sessões (2026-11-11)

- Cenário: `u3` · usuários: 20 · duração: 300 s
- Resultado: **aprovado**

| Rota | p95 | Limite |
|---|---|---|
| verifyWithInvite | 224 ms | 500 ms |
| lookup | 4 ms | 300 ms |
| account | 6 ms | 300 ms |

- Verificação com convite: 20 contas criadas em verificações simultâneas (tempo medido por requisição no cliente)
- Respostas 429 na consulta do convite e na tela Conta (perfil `loadtest`): 0
- Espera do pool: máximo de 0 conexões pendentes; maior sequência 0 s (limite 10 s)
- Memória máxima: api 326 MB, postgres 101 MB (limite 900 MB)
- Erros de cliente: 0
- Falhas: nenhuma
