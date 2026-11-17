# Projeto de desempenho e escala — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes: `construction/u6-real-modes-ui/nfr-requirements/performance-requirements.md` e `scalability-requirements.md` (U6-NFR3.x) e `nfr-design-questions.md` (Q2, Q3).

| Requisito | Como o projeto atende |
|---|---|
| U6-NFR3.1 | O `SigningBanner` assina `real.signing.requested` no `LiveChannelProvider` e muda o estado no próprio handler, sem esperar leitura. |
| U6-NFR3.2 | O `countdown.ts` calcula `restante = deadline - (agora + skew)`, com `skew = serverTime - recebidoEm`, e atualiza a cada 250 ms; sem `serverTime`, `skew = 0`. |
| U6-NFR3.3 | O `useMoneyAction` marca "enviando" de forma síncrona no clique, antes do `fetch`. |
| U6-NFR3.4, U6-NFR3.5b | O `useTracking` agenda a próxima leitura só depois da anterior terminar, a cada 3 s, e para em 2 min. |
| U6-NFR3.5 | A releitura da faixa faz no máximo `GET /orders?state=QUOTED&limit=1` mais a sessão, e só no modo Assinada. |
| U6-NFR3.6 | As listas usam `limit=20` e `nextCursor`. |
