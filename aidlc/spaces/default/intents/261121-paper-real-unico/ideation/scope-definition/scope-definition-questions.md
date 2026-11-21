# Perguntas da Scope Definition — paper-real-unico

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele.

Fontes: `ideation/intent-capture/intent-statement.md`, `ideation/feasibility/feasibility-assessment.md`, `constraint-register.md` e `raid-log.md`.

## Q1. Escopo mínimo que entrega valor

Qual é o menor escopo que já entrega valor?

A. A tela de bots mostra os bots PAPER e REAL lado a lado, com o modo em texto. O painel mostra o P&L de cada bot e o total de cada modo, separados e nunca somados. O P&L REAL por bot é calculado na leitura, com custo médio por bot e o rateio das vendas sem bot. (recomendado)
B. Só a visão PAPER por bot e o total REAL por pessoa, como hoje, na mesma tela
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. Must-have e nice-to-have

O que é obrigatório e o que é desejável?

A. Dividido assim (recomendado):
   - **Must:**
     - lista única de bots com o modo;
     - P&L por bot nos dois modos;
     - total por modo, separado;
     - rateio das vendas sem bot, com a marca de origem;
     - P&L REAL fechando com o ledger;
     - rótulo "simulado" em texto e o aviso de que o simulado não garante o real.
   - **Should:** filtro por modo e por período, e o detalhe do P&L de um bot com as ordens que o compõem.
   - **Could:** comparar um bot PAPER e um bot REAL da mesma estratégia lado a lado, sem ranking misturado.
   - **Won't nesta versão:** exportação fiscal do simulado, conversão de bot e nova rede.
B. Tudo Must
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. Dependências entre capacidades

Quais são as dependências entre as capacidades?

A. Em três camadas (recomendado):
   - **Base:** o P&L REAL por bot depende da confirmação e da correção das saídas condicionais no P&L REAL, no intent 261113.
   - **Depois da Performance Validation:** a venda só da própria quantidade pelo bot REAL depende de o intent 261113 terminar essa etapa.
   - **Sem dependência externa:** a tela e o total por modo.
B. Nenhuma dependência
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. Ordem de entrega

Em que ordem entregar?

A. Primeiro o risco:
   1. o cálculo do P&L REAL por bot na leitura, com custo médio e rateio, e os testes escritos antes do código;
   2. a API do P&L por bot e por modo;
   3. a tela;
   4. por último, a mudança do ciclo, depois da Performance Validation do REAL.

   (recomendado: o cálculo é a parte de dinheiro e a mais arriscada)
B. Primeiro o valor (tela primeiro)
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. Prazos

Há prazo fixo para alguma capacidade?

A. Nenhum prazo fixo. A mudança do ciclo espera a Performance Validation do REAL. (recomendado)
B. Prazo fixo (diga qual)
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Menor escopo com valor:**
  - a lista de bots PAPER e REAL lado a lado, com o modo em texto;
  - o P&L por bot e o total de cada modo, separados e nunca somados;
  - o P&L REAL por bot calculado na leitura, com custo médio e rateio das vendas sem bot [Q1].
- **Must:**
  - a lista com o modo;
  - o P&L por bot nos dois modos;
  - o total por modo;
  - o rateio com a marca de origem;
  - o P&L REAL fechando com o ledger;
  - o rótulo "simulado" e o aviso.
- **Should:** os filtros por modo e por período, e o detalhe do bot.
- **Could:** a comparação PAPER e REAL da mesma estratégia.
- **Won't:** a exportação fiscal do simulado, a conversão de bot e uma rede nova [Q2].
- **Dependências:**
  - o P&L REAL por bot depende das condicionais no P&L REAL, no intent 261113;
  - a mudança do ciclo depende da Performance Validation do REAL;
  - a tela e os totais não dependem de nada externo [Q3].
- **Ordem, com o risco primeiro:**
  1. cálculo, com testes antes do código;
  2. API;
  3. tela;
  4. ciclo, depois da Performance Validation [Q4].
- **Prazo:** nenhum prazo fixo [Q5].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
