# Perguntas de requisitos não funcionais — U4 Descoberta e avaliação

Contexto: o design funcional da U4 está em [functional-spec.md](../functional-design/functional-spec.md) e [rules.md](../functional-design/rules.md). Os requisitos da inception que tocam a U4 são NFR1 (frescor: 95% dos valores com idade ≤ 60 s, 100% dos valores acima de 5 minutos marcados), NFR3 (p95 ≤ 2 s para carregar o scanner com 20 usuários simultâneos), NFR9 (métrica de saúde e de erro por componente, correlação nos logs), NFR11 (acessibilidade básica), NFR12 (cobertura de 80%) e NFR13 (Windows e Linux com o mesmo resultado). O contrato C19 propõe tempo limite de 5 s e até 2 novas tentativas na chamada a uma fonte.

Já resolvido sem pergunta (pode corrigir na revisão do resumo):
- A medição de 1 hora do NFR1 com fonte real fica para depois do G0 (nota da AC2.3.4); na U4, o frescor é verificado por testes com relógio injetável.
- Sem alertas automáticos enquanto o beta roda só localmente: métricas e logs, como na U2.
- Nada de rede real nos testes: fixtures gravadas para os adaptadores simulados.

## Q1. [Desempenho] Quantos tokens a janela de 24 horas deve suportar no beta?

O número de tokens define o custo da lista filtrada, do recálculo das avaliações após cada coleta e do histórico de 24 horas.

- A. Até 2 000 tokens na janela de 24 horas
- B. Até 10 000 tokens na janela de 24 horas
- C. Até 50 000 tokens na janela de 24 horas
- X. Other (please specify)

[Answer]: A

## Q2. [Desenvolvimento] Quantas fontes simuladas a U4 entrega?

A divergência, a degradação por fonte e o aviso de falha precisam de mais de uma fonte.

- A. Duas fontes simuladas (A e B), com parte dos tokens em comum e parte só numa delas
- B. Três fontes simuladas, para testar também divergência entre mais de duas origens
- X. Other (please specify)

[Answer]: A

## Q3. [Segurança] As rotas do scanner e da avaliação têm limite de requisições por pessoa?

Essas rotas exigem sessão, mas cada consulta da lista com filtros percorre a janela inteira.

- A. Sim: 60 requisições por minuto por pessoa em cada rota; acima disso, `429` com `Retry-After`
- B. Sim: 120 requisições por minuto por pessoa em cada rota; acima disso, `429` com `Retry-After`
- C. Não no beta local; decidir junto com a hospedagem
- X. Other (please specify)

[Answer]: A

## Q4. [Tecnologia] Como são feitos o tempo limite e as novas tentativas na chamada às fontes?

- A. Biblioteca Resilience4j (tempo limite e novas tentativas com espera crescente e variação aleatória), versão fixa e varrida como as demais dependências
- B. Código próprio do módulo MarketData, sem biblioteca nova, com relógio e espera injetáveis para os testes
- X. Other (please specify)

[Answer]: A

## Q5. [Desempenho] Qual o tempo máximo de resposta da API para a lista e para o detalhe?

O NFR3 fala da tela inteira (p95 ≤ 2 s com 20 usuários). A API precisa de uma meta própria, menor, para sobrar tempo à tela.

- A. Lista: p95 ≤ 500 ms; detalhe da avaliação: p95 ≤ 300 ms; com 20 usuários simultâneos e a janela cheia da Q1
- B. Lista: p95 ≤ 1 s; detalhe da avaliação: p95 ≤ 500 ms; com 20 usuários simultâneos e a janela cheia da Q1
- X. Other (please specify)

[Answer]: B

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
