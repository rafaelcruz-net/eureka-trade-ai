# Requisitos de escala — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes: `nfr-requirements-questions.md` (Q1, Q5) e o piloto de até 21 carteiras (o fundador e 20 convidados).

| ID | Requisito | Verificação |
|---|---|---|
| U6-NFR3.5 | A releitura da faixa custa no máximo duas chamadas por troca de rota (`GET /orders?state=QUOTED&limit=1` e, havendo ordem, a sessão). As rotas REAL não têm limite de taxa próprio, e a conta é pequena: 21 pessoas trocando de rota a cada 5 s dão no máximo 8,4 pedidos por segundo. | teste de componente que conta as chamadas |
| U6-NFR3.5b | O acompanhamento (U6-NFR3.4) soma no máximo 40 leituras por saque ou venda total, uma por vez | teste de componente |
| U6-NFR3.6 | As listas de ordens e de usuários do administrador são paginadas por cursor, com `limit=20` (o contrato aceita o padrão de 50) | teste de componente que confere o parâmetro |
