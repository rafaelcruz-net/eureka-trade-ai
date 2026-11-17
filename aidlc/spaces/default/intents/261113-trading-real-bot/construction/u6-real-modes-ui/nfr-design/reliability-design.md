# Projeto de confiabilidade — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes: `construction/u6-real-modes-ui/nfr-requirements/reliability-requirements.md` (U6-NFR4.x) e `nfr-design-questions.md` (Q2, Q3).

| Requisito | Como o projeto atende |
|---|---|
| U6-NFR4.1 | O `LiveChannelProvider` avisa os assinantes a cada reconexão (`onReconnected`). O `SigningBanner` relê a sessão nesse momento, na carga e a cada troca de rota (pelo `usePathname`). |
| U6-NFR4.2 | O `useMoneyAction` não tem reenvio automático; o "Tentar de novo" chama o mesmo envio, com o mesmo corpo e a mesma chave. |
| U6-NFR4.3, U6-NFR4.7 | Cada parte da tela tem o próprio hook e o próprio estado (`loading`, `error`, `empty`, `data`), e um erro é mostrado só naquela parte. |
| U6-NFR4.4 | A tela de depósito e saque lê só a carteira e os saques. O formulário de saque não depende de P&L, de ordens nem da liberação. |
| U6-NFR4.5 | O `SigningBanner` usa uma segunda instância do `guestApi`, sem o tratador do novo aceite (`setTermsReacceptanceHandler`), e não assina nem relê com o novo aceite pendente. |
| U6-NFR4.6 | O `useTracking` termina em 2 min com o texto definido. |
| Canal no layout | Se o `LiveChannelProvider` falhar, o painel mostra o aviso de dado desatualizado que já existe, e a faixa segue pela releitura nas trocas de rota. |
