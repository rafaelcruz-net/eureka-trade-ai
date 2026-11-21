# Documento de escopo — paper-real-unico

Fontes: `scope-definition-questions.md` (Q1 a Q5), `ideation/intent-capture/intent-statement.md` e `ideation/feasibility/` (avaliação, restrições e RAID).

## Objetivo

Num único ambiente, na mainnet, o usuário cria bots simulados (PAPER) e reais (REAL) lado a lado. Ele acompanha o P&L de cada bot e o total de cada modo, sempre separados e nunca somados [intent Q1, Q2; scope Q1].

## Dentro do escopo

### Must
- **Lista única de bots.** A lista mostra os bots PAPER e REAL juntos, com o modo escrito em texto, não só em cor [Q2].
- **P&L por bot nos dois modos.**
  - No PAPER, o cálculo já existe.
  - No REAL, o P&L por bot é calculado na leitura, com custo médio por bot [Q1] [feasibility Q8].
- **Total por modo.** O total do PAPER e o total do REAL aparecem separados e nunca são somados. As moedas são diferentes: dólar no PAPER e SOL no REAL [Q2] [feasibility Q3].
- **Rateio das vendas sem bot.** O resultado de "vender tudo e sacar" e das saídas condicionais é rateado entre os bots donos das quantidades vendidas, com a marca "fechado por liquidação" ou "fechado por condicional" [Q2] [feasibility Q9].
- **Conferência com o ledger.** O P&L REAL fecha com o ledger REAL, e o PAPER fecha com o ledger PAPER [Q2] [intent Q4].
- **Aviso no simulado.** O rótulo "simulado" aparece em texto, junto com o aviso de que o resultado simulado não garante o resultado real. Nada promete lucro (C-16) [Q2] [feasibility R6].

### Should
- **Filtros** por modo e por período [Q2].
- **Detalhe do P&L de um bot**, com as ordens que o compõem [Q2].

### Could
- **Comparação lado a lado** de um bot PAPER com um bot REAL da mesma estratégia, sem ranking que misture os dois modos [Q2].

## Fora do escopo (Won't nesta versão)

- Misturar saldos, ordens ou P&L entre os modos [intent Q8].
- Converter um bot de um modo para o outro [intent Q8].
- Criar uma nova rede de execução [intent Q8].
- A exportação fiscal do simulado [intent Q8].

## Dependências

- **P&L REAL por bot.** Depende de confirmar e corrigir, no intent 261113-trading-real-bot, se as vendas das saídas condicionais entram no P&L REAL [Q3] [feasibility R5].
- **Venda só da própria quantidade.** A mudança no ciclo do bot REAL, para vender só o que ele comprou, espera o intent 261113 terminar a Performance Validation. Até lá, a separação é calculada só na leitura [Q3] [feasibility Q10].
- **Tela e totais por modo.** Não dependem de nada externo [Q3].

## Ordem de entrega

A entrega começa pelo que tem mais risco [Q4]:
1. o cálculo do P&L REAL por bot na leitura, com custo médio e rateio, e os testes escritos antes do código;
2. a API do P&L por bot e por modo;
3. a tela;
4. a mudança do ciclo, depois da Performance Validation do REAL.

## Prazos

Nenhum prazo fixo [Q5].

## Assumptions & Open Questions

- [assumption] Até o ciclo mudar, quando um bot vender mais do que comprou, o excedente é rateado entre os outros bots donos do token, como nas vendas sem bot. O relatório marca essa parte. A regra exata fica para os requisitos (`raid-log.md`, A8).
