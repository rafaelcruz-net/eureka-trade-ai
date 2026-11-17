# Requisitos de confiabilidade — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes: `nfr-requirements-questions.md` (Q5) e `construction/u6-real-modes-ui/functional-design/rules.md` (BR1.4, BR3.5, BR6).

| ID | Requisito | Verificação |
|---|---|---|
| U6-NFR4.1 | O canal ao vivo reconecta pela renovação que já existe (`channel-renewal.ts`). A faixa relê a sessão aberta na carga, a cada troca de rota e logo depois de cada reconexão. Com o canal fora, a faixa aparece no máximo na próxima troca de rota. | teste de componente: evento perdido, reconexão e uma releitura em seguida |
| U6-NFR4.2 | Uma ação com dinheiro nunca é reenviada sozinha; só o botão "Tentar de novo" reenvia, com o mesmo corpo e a mesma chave | teste de componente |
| U6-NFR4.3 | Um erro numa parte da tela (P&L, ordens, liberação) aparece só nela e não derruba as outras | teste de componente com uma leitura falhando |
| U6-NFR4.4 | O saque nunca fica bloqueado por erro de outra leitura, por pausa, pelo kill switch ou pela falta do novo aceite | testes de componente S21 a S23 e S42 |
| U6-NFR4.5 | A faixa de assinatura nunca provoca o redirecionamento global para o novo aceite (BR3.5) | teste de componente |
| U6-NFR4.6 | O acompanhamento do saque e da venda total termina em 2 min com "Ainda processando; acompanhe em Ordens" | teste de componente com relógio falso |
| U6-NFR4.7 | Carregando, vazio e erro com "Tentar de novo" são estados distintos e em texto em todas as telas (BR8.1) | teste de componente por tela |
