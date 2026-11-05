# Perguntas de requisitos não funcionais — U6 Barreira de limites

O design funcional da U6 está em [functional-spec.md](../functional-design/functional-spec.md) e [rules.md](../functional-design/rules.md). Os requisitos da inception que tocam a U6 são:
- **NFR2:** 60 bots PAPER ativos; cada ciclo termina antes de o dado ficar desatualizado, em até 60 s.
- **NFR3:** p95 ≤ 2 s nas telas principais com 20 usuários.
- **NFR4:** 0 operações acima dos limites.
- **NFR5:** isolamento entre tenants.
- **NFR9:** métrica de saúde, métrica de erro e identificador de correlação.
- **NFR10:** sem meta de disponibilidade no beta local.
- **NFR12:** cobertura de 80% e TDD no núcleo financeiro.
- **NFR13:** mesmo resultado no Windows e no Linux.

Já resolvido sem pergunta (pode corrigir na revisão do resumo):
- **TDD em toda a U6.** A barreira é o núcleo financeiro do `team.md` ("limites de risco"). O catálogo E1 a E21 é escrito antes do código.
- **Processo e transação.** A barreira roda no processo e na transação do ciclo da U5 (worker). A rota C21 roda no processo de API.
- **Sem alertas automáticos** enquanto o beta roda só localmente, como nas Units anteriores.
- **Sem dependência nova.** Decimal exato do Java (`BigDecimal`) e PostgreSQL com `numeric`.
- **Registros imutáveis.** Avaliações e recusas não aceitam UPDATE nem DELETE (BR4.3).

## Q1. [Desempenho] Quanto tempo a avaliação de uma ordem pode levar dentro do ciclo do bot?

A barreira roda dentro da transação do ciclo, com a linha do bot travada. Ela soma uma leitura de mercado, uma leitura da visão de risco da U7 e as gravações. Para comparar: a execução da U7 tem p95 ≤ 250 ms.

A. p95 ≤ 100 ms para `evaluate`, com 60 bots ativos.
B. p95 ≤ 250 ms para `evaluate`, com 60 bots ativos.
X. Other (please specify)

[Answer]: B

## Q2. [Desempenho] Qual o tempo máximo da rota de recusas de um bot (C21)?

O NFR3 fala da tela inteira (p95 ≤ 2 s com 20 usuários). A rota precisa de uma meta própria, menor.

A. p95 ≤ 500 ms na primeira página, com até 10.000 recusas por bot e 20 usuários simultâneos.
B. p95 ≤ 1 s na primeira página, com a mesma carga.
X. Other (please specify)

[Answer]: B

## Q3. [Segurança] A rota de recusas tem limite de requisições por pessoa?

A. Não. É uma leitura, como as rotas de P&L e ledger da U7, que ficaram sem limite.
B. Sim: 60 requisições por minuto por pessoa, como o scanner da U4. Acima disso, `429` com `Retry-After`.
X. Other (please specify)

[Answer]: A

## Q4. [Observabilidade] Quais métricas próprias a U6 publica?

A. Contadores de ordens aprovadas e recusadas, com as recusas por motivo e por gatilho de pausa, e um histograma do tempo de `evaluate`. Sem identificador de pessoa, bot ou token nos rótulos.
B. Só a métrica de saúde e a de erro exigidas pelo NFR9.
X. Other (please specify)

[Answer]: B

## Q5. [Confiabilidade] Por quanto tempo guardar as avaliações e as recusas?

No pior caso, cada um dos 60 bots gera uma avaliação por ciclo. Isso dá dezenas de milhares de linhas por dia. As recusas explicam o que o bot deixou de fazer; as avaliações aprovadas só servem para reconhecer uma decisão repetida.

A. Guardar tudo durante o beta, sem expurgo, como o ledger da U7. O volume é medido no teste de carga.
B. Guardar as recusas durante todo o beta. Apagar as avaliações aprovadas com mais de 30 dias por uma tarefa de expurgo, a única exceção à imutabilidade.
X. Other (please specify)

[Answer]: B

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
