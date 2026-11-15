# Desenho de confiabilidade — U2 `u2-real-market-data`

Conversa: português (Brasil). Fontes: `construction/u2-real-market-data/nfr-requirements/reliability-requirements.md` (NFR4.1 a NFR4.6), `construction/u2-real-market-data/functional-design/rules.md` (BR1.3, BR1.9), `inception/contract-design/contract-summary.md` (X2), o código atual do `SourceCaller` e `nfr-design-questions.md` (Q2 a Q4). Revisado depois da primeira revisão para caber no `SourceCaller` que já existe.

## Dois níveis de proteção

**1. Rodada inteira (`SourceCaller`, que já existe).** Hoje o `SourceCaller` envolve o `fetch()` inteiro em 5 s e faz até 3 tentativas, inclusive em caso de 429. A U2 faz o `SourceCaller` aceitar uma configuração por fonte:
- `sim-a` e `sim-b` continuam com 5 s e 3 tentativas.
- `coingecko` passa a ter limite de 20 s para a rodada inteira e 1 tentativa, sem repetição da rodada. Quem repete é cada chamada, dentro do adaptador. Assim as repetições não se empilham.

**2. Cada chamada HTTP (dentro do `CoinGeckoAdapter`).**

| Proteção | Configuração |
|---|---|
| Repetição | 1 vez, só em tempo esgotado e 5xx, e só se couber no prazo restante da rodada. Nunca em 4xx, 3xx, 429 ou circuito aberto [Q4] |
| Limite de tempo | 4 s por chamada, abaixo dos 5 s do X2, para caber no prazo da rodada |
| Circuito | Resilience4j: janela de 60 s, mínimo de 5 chamadas, 50% de falhas técnicas, 60 s aberto (NFR4.2) |
| Limite por minuto | Contador próprio do `CallBudget`, em janela deslizante: 250 no total e 2 por minuto para detalhes. Não usa o RateLimiter do Resilience4j, que não está no `pom`. Acima do limite, a chamada não é feita e o item fica para a próxima rodada [Q2] |

## Rodada (prazo de 20 s, em série)

1. **Preços primeiro:** lotes de até 30 tokens. São 2 chamadas com 50 tokens e 4 com até 100. No pior caso, sem repetição, 2 × 4 s = 8 s.
2. **Descoberta:** 2 chamadas, só se ela venceu (5 min) e se restarem pelo menos 8 s de prazo. Senão, fica para a próxima rodada.
3. **Fatia de detalhes:** só se restarem pelo menos 4 s de prazo e o limite de 2 por minuto deixar.

Um 429 não é repetido dentro da rodada. A rodada termina DEGRADED, e o `Retry-After` vale para a próxima rodada.

**Estados (BR1.9):**
- OK: todos os preços atualizados.
- DEGRADED: preços parciais, ou a primeira rodada sem nenhum preço.
- FAILED: a segunda rodada seguida sem nenhum preço.

**Frescor no pior caso:**
- **Uma rodada OK:** o intervalo de 30 s mais o prazo de 20 s dão até 50 s de idade, dentro da meta de 60 s.
- **Uma rodada perdida:** a idade chega a cerca de 80 s e conta como minuto ruim no SLI (NFR4.1).

## Orçamento (`CallBudget`) [Q2, Q3]

**Fonte da verdade:** a tabela `marketdata_provider_usage`, que existe sempre, com migração própria:

| Coluna | Tipo |
|---|---|
| `source` | texto |
| `month` | texto `AAAA-MM`, UTC |
| `calls` | inteiro |
| `updated_at` | instante UTC |

- A chave primária é (`source`, `month`).
- Cada rodada soma as próprias tentativas com um upsert, na mesma transação curta da gravação das observações.
- As linhas são guardadas por 13 meses e apagadas pela limpeza do histórico de mercado que já existe.

**Conferência com a CoinGecko:** a cada hora, quando a rota de uso existir, o orçamento usa o maior valor entre a tabela e o informado pela CoinGecko. Um reinício nunca zera o contador, porque ele vem da tabela (NFR3.7).

**Prioridade pelo uso contado:**
- preços param a 98% do orçamento, para guardar 2% de margem;
- descoberta para a 95%;
- detalhes param a 90%.

**Modo conservador:** acontece só quando a tabela não pode ser lida, por exemplo com o banco fora do ar. Nesse caso a rodada não chama o fornecedor e termina DEGRADED.

## Reinício

O conjunto ativo é refeito na primeira rodada. O contador vem da tabela. As observações continuam valendo, com o horário delas (NFR4.4).
