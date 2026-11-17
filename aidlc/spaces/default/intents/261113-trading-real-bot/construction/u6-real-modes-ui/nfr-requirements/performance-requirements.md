# Requisitos de desempenho — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes: `nfr-requirements-questions.md` (Q1) e `construction/u6-real-modes-ui/functional-design/rules.md` (BR3, BR6).

As metas são provadas nos testes de componente com relógio falso, onde o tempo é controlado. O e2e confere a mesma meta com folga, para não ficar instável na CI.

| ID | Requisito | Verificação |
|---|---|---|
| U6-NFR3.1 | A faixa de assinatura aparece no mesmo ciclo de render em que o evento `real.signing.requested` chega, sem espera nem consulta antes de mostrar | teste de componente: evento entregue, faixa presente sem avançar o relógio; e2e: faixa visível em até 5 s depois da sessão aberta |
| U6-NFR3.2 | A contagem do diálogo erra no máximo 1 s em relação ao `deadline` do servidor, descontada a diferença de relógio por `serverTime` | teste de componente com o relógio do navegador defasado em -30 s, 0 s e +30 s |
| U6-NFR3.3 | Depois do clique numa ação com dinheiro, o botão fica desabilitado e o texto "enviando" aparece antes de o pedido sair | teste de componente que confere o estado antes de resolver o `fetch` |
| U6-NFR3.4 | O saque e a venda total são acompanhados por leitura do estado a cada 3 s, por até 2 min, com uma só leitura em andamento por vez | teste de componente com relógio falso que conta as leituras (no máximo 40) |
