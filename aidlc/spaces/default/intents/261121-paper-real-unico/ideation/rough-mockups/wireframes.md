# Rascunhos das telas — paper-real-unico

Fontes: `rough-mockups-questions.md` (Q1 a Q6), `ideation/intent-capture/intent-statement.md`, `ideation/scope-definition/scope-document.md` e `intent-backlog.md`.

Os rascunhos são de baixa fidelidade. Os valores são ilustrativos e não prometem resultado. Toda cor vem acompanhada de texto [Q4] [Q6].

## 1. Bots (`/bots`): lista única [Q1] [Q3]

```
+------------------------------------------------------------------+
| Meus bots                                     [+ Novo bot]       |
| Filtro: [Todos v] [Simulado] [Real]                               |
+------------------------------------------------------------------+
| Nome            | Modo        | Estado  | P&L (no seu modo)       |
|-----------------|-------------|---------|-------------------------|
| Momentum A      | SIMULADO    | Ativo   | +US$ 12,40              |
| Momentum A      | REAL        | Ativo   | +0,0312 SOL             |
| Reversão B      | SIMULADO    | Pausado | -US$ 3,10               |
+------------------------------------------------------------------+
```

- O modo aparece em texto: `SIMULADO` ou `REAL`, com o selo.
- O P&L sai sempre na moeda do próprio modo.
- Não há ordenação que misture os modos.

**Acessibilidade.** h1 "Meus bots". Regiões: header, nav e main. A entrada pelo teclado começa no filtro.

## 2. P&L (`/pnl`): blocos por modo [Q1] [Q3]

```
+-------------------------------+  +-------------------------------+
| SIMULADO (US$)                |  | REAL (SOL)                    |
| Total: +US$ 9,30              |  | Total: +0,0312 SOL            |
| Realizado  +US$ 7,00          |  | Realizado  +0,0200 SOL        |
| Não realiz.+US$ 2,30          |  | Não realiz.+0,0130 SOL        |
| Taxas       US$ 0,00          |  | Taxas      -0,0018 SOL        |
|-------------------------------|  |-------------------------------|
| Momentum A    +US$ 12,40      |  | Momentum A    +0,0312 SOL     |
| Reversão B    -US$  3,10      |  |   inclui 0,0050 SOL fechado   |
|                               |  |   por liquidação              |
| ! O resultado simulado não    |  |                               |
|   garante o resultado real.   |  |                               |
+-------------------------------+  +-------------------------------+
   (nenhuma linha soma os dois blocos)
```

- Quando falta preço, o não realizado aparece como "desconhecido", nunca como zero.
- Carregando, erro e `unknown` são estados visíveis e diferentes [Q4].

**Acessibilidade.** h1 "Resultado". Cada bloco tem um h2 ("Simulado, em dólares" e "Real, em SOL"). O total de cada bloco tem um rótulo para leitor de tela com o modo e a moeda [Q6]. A entrada pelo teclado começa no primeiro bloco.

## 3. Painel: os dois totais [Q1] [Q3]

```
+-------------------------------------------------------------+
| Resultado simulado: +US$ 9,30  |  Resultado real: +0,0312 SOL |
| ! O resultado simulado não     |                              |
|   garante o resultado real.    |                              |
+-------------------------------------------------------------+
```

Quando a pessoa não tem bot REAL ou a carteira do bot está inativa, o lado Real mostra "Sem bots reais" e o link "Ativar a carteira do bot". Os estados de carregando, erro e `unknown` são os mesmos da tela P&L [Q4].

**Acessibilidade.** Os totais ficam numa região rotulada "Resultados por modo", e cada total tem o seu rótulo de modo e moeda.

## 4. Detalhe do bot REAL (`/bots/{id}`) [Q2]

```
+------------------------------------------------------------------+
| Momentum A  [REAL]                                               |
| P&L do bot: +0,0312 SOL  (realizado +0,0200 / não realiz. ...)   |
|------------------------------------------------------------------|
| Data (seu fuso) | Lado  | Token | Resultado     | Origem           |
| 20/11 14:02     | Compra| XYZ   | —             | Bot              |
| 20/11 15:40     | Venda | XYZ   | +0,0150 SOL   | Bot              |
| 21/11 09:10     | Venda | XYZ   | +0,0050 SOL   | Fechado por      |
|                 |       |       |               | liquidação       |
+------------------------------------------------------------------+
```

**Acessibilidade.** h1 com o nome do bot. A tabela tem cabeçalhos de coluna, e a origem aparece em texto.

## 5. Celular (a partir de 360 px) [Q5]

```
+----------------------+
| SIMULADO (US$)       |
| Total +US$ 9,30      |
| Momentum A +12,40    |
| ! O simulado não     |
|   garante o real.    |
+----------------------+
| REAL (SOL)           |
| Total +0,0312 SOL    |
| Momentum A +0,0312   |
+----------------------+
```

Os blocos viram cartões empilhados, sem rolagem lateral.

**Acessibilidade.** Seguem os mesmos títulos (h1 e h2 por modo) e rótulos das telas de computador. Os cartões aparecem na ordem de leitura, Simulado e depois Real, e o foco do teclado anda de cartão em cartão.

## 6. Estados e marcas que valem para todas as telas [Q3] [Q4]

- **Estados:** carregando, erro (com "tentar de novo") e `unknown` são estados visíveis e diferentes na lista, no painel, no P&L e no detalhe.
- **Estado vazio:** sem nenhum bot, a tela mostra "Você ainda não tem bots" e o botão "+ Novo bot".
- **Origem das linhas no detalhe do bot REAL:**
  - "Bot";
  - "Fechado por liquidação";
  - "Fechado por condicional";
  - "Vendido por outro bot", para o excedente rateado enquanto o ciclo não muda (suposição A8 do escopo).
- **Itens Should:** o filtro da lista e a tabela de ordens do detalhe são itens Should do escopo. Eles estão desenhados para mostrar o lugar que vão ocupar, e a entrega deles fica depois dos itens Must.

## Assumptions & Open Questions

None.
