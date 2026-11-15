# Plano de Bolts — Trading REAL

Conversa: português (Brasil). Um **Bolt** é uma passada de construção sobre uma parte do trabalho que termina em algo que roda. Fontes: `inception/units-generation/unit-of-work.md`, `inception/units-generation/unit-of-work-dependency.md`, `inception/units-generation/unit-of-work-story-map.md`, `inception/contract-design/contract-summary.md`, `inception/domain-design/components.md`, `inception/requirements-analysis/requirements.md`, `inception/user-stories/stories.md`, `inception/refined-mockups/mockups.md` e `delivery-planning-questions.md` (Q1 a Q9).

## Forma de trabalho

- Uma Unit por Bolt, 7 Bolts, um por vez (Construction Iteration `unit-major`, execução `serial`) [Q3, Q4].
- Direto em `main`, commit quando o responsável pedir, CI verde (`team.md`).
- Cada Bolt passa por Functional Design, NFR Requirements, NFR Design, Infrastructure Design e Code Generation da sua Unit e termina num ponto de controle verificado.
- Antes do primeiro Bolt, as mudanças de regra do ADR-008 são registradas pelo AI-DLC em `project.md` e `team.md` (inclusive a exceção dos termos para leitura da carteira e saque, do Contract Design).

## Sequência

| # | Bolt | Unit | Fase do backlog | Esqueleto |
|---|---|---|---|---|
| B1 | Carteira do bot | U1 `u1-bot-wallet` | Fase 0 (provas) + Fase 1 | sim |
| B2 | Dados reais | U2 `u2-real-market-data` | Fase 1 | — |
| B3 | Barreira REAL | U3 `u3-real-risk` | Fase 1 | — |
| B4 | Execução REAL | U4 `u4-real-execution` | Fase 2 | — |
| B5 | Operação REAL | U5 `u5-real-operations` | Fase 3 | — |
| B6 | Telas e modos | U6 `u6-real-modes-ui` | Fase 3 | — |
| B7 | Saídas e fiscal (Should) | U7 `u7-exits-and-fiscal` | Fase 4 | — |

A ordem segue o grafo: U1 e U2 não dependem de nada; U3 depende de U1 e U2; U4 de U1, U2 e U3; U5 de U1, U3 e U4; U6 de U1 e U4; U7 de U1, U4 e U5.

## B1 — Carteira do bot (esqueleto)

- **Unit:** U1. **Esqueleto:** sim — a primeira fatia fina de ponta a ponta: navegador (tela mínima) → API → BotWallet → TxSigner (processo próprio, KMS simulado) → ChainWatcher → validador local → ledger REAL.
- **Começo do Bolt — provas técnicas (Fase 0):** (1) uma assinatura Ed25519 feita pelo KMS é aceita pela rede de teste; (2) uma transação de swap da Jupiter é assinada e serializada sem SDK Solana na produção e roda no validador com contas clonadas. As provas ficam em código de teste. Se uma falhar, o Bolt para e o desenho volta ao Domain Design.
- **Definição de pronto:**
  - REAL desligado por padrão: nenhuma carteira é criada e nada é assinado com a configuração padrão (teste obrigatório);
  - ativação exige termos v3, liberação mínima (só o fundador, perfil de teste) e consentimento no formato canônico;
  - a chave nunca sai do KMS; o TxSigner recusa toda transação fora da política, com testes de ataque escritos antes do código;
  - depósito detectado e lançado no ledger REAL; saque só para a carteira principal;
  - auditoria com campos REAL tipados; ledger REAL append-only;
  - piso de 80% de linhas no backend e no frontend; `bash scripts/verify.sh` verde com o spec de ponta a ponta novo.
- **Hipótese de confiança:** a assinatura isolada, a rede e o ledger REAL funcionam juntos antes de qualquer ordem, e o desenho do TxSigner aguenta os casos de ataque.
- **Demonstração:** no validador local, ativar a carteira do bot, depositar 1 SOL da carteira principal de teste, ver o saldo e sacar de volta; tentar sacar para outro endereço e ver a recusa.

## B2 — Dados reais

- **Unit:** U2.
- **Definição de pronto:** adaptador da CoinGecko atrás da porta existente, com respostas gravadas; atribuição na tela; `sourceKind` `SIMULATED` exposto; `unknown` nunca vira zero.
- **Hipótese de confiança:** o plano pago entrega os campos de que a barreira precisa, com frescor de até 60 s.
- **Demonstração:** o scanner local com dados gravados da CoinGecko e a atribuição; a troca para a fonte simulada aparece como "fonte simulada ativa".

## B3 — Barreira REAL

- **Unit:** U3.
- **Definição de pronto:** limites só nas compras (10% do teto por ordem, perda do dia 20%, 20 ordens por bot por dia), teto sobre saldo livre mais posições, posição sem preço bloqueia compra, frescor de 60 s, recusa com fonte simulada, lista de bloqueio; testes antes do código.
- **Hipótese de confiança:** nenhuma ordem passa acima dos limites, e as vendas de saída sempre passam.
- **Demonstração:** cenários determinísticos de recusa e aprovação rodando nos testes.

## B4 — Execução REAL

- **Unit:** U4.
- **Definição de pronto:** fila de decisões gravada no ciclo e consumida fora dele; estados gravados; uma única transação mesmo com repetição; ordem ambígua conferida na rede antes de nova tentativa; modo Assinada com prazo de 8 s e conferência da mensagem; troca de modo com consentimento; P&L REAL com taxas; vender tudo e sacar; testes antes do código para estados e idempotência.
- **Hipótese de confiança:** da decisão ao envio em p95 de até 3 s, e nunca duas transações para a mesma ordem.
- **Demonstração:** no validador clonado, o bot do fundador compra e vende um token nos modos Automática total e Assinada; uma ordem repetida não gera segunda transação.

## B5 — Operação REAL

- **Unit:** U5.
- **Definição de pronto:** reconciliação a cada 15 min e depois de cada ordem; divergência pausa o usuário; compensação pelo administrador; liberação com quarentena de 24 h; desligar o REAL por usuário; kill switch REAL; anonimização com a carteira do bot; área REAL do administrador.
- **Hipótese de confiança:** o fundador consegue parar tudo e enxergar qualquer divergência antes que ela cresça.
- **Demonstração:** forçar uma divergência no validador e ver a pausa e o alerta; acionar o kill switch e ver o saque continuar.

## B6 — Telas e modos

- **Unit:** U6.
- **Definição de pronto:** novo aceite dos termos v3, ativação em três passos, escolha do modo, faixa e diálogo de assinatura, ordens, depósito e saque, cartão REAL no painel; corrige os pontos de tela em aberto (campo do teto, estados, confirmações, cor do REAL); acessibilidade básica do beta.
- **Hipótese de confiança:** um convidado ativa, deposita, opera e saca sem ajuda.
- **Demonstração:** o fluxo completo de ponta a ponta na devnet pelo navegador.
- **Marco:** depois do B5 e do B6, com a quarentena de 24 h, a carteira do fundador entra na mainnet [Q6].

## B7 — Saídas automáticas e exportação fiscal (Should)

- **Unit:** U7.
- **Definição de pronto:** stop e alvo como condicionais da Jupiter assinadas pela carteira principal; preenchimento detectado e lançado; aviso de cancelamento calculado na leitura; exportação fiscal CSV.
- **Hipótese de confiança:** as saídas disparam sem o usuário online, e o histórico fecha com o ledger.
- **Demonstração:** uma condicional criada e preenchida em teste com respostas gravadas da Jupiter; o CSV exportado bate com o ledger.
- **Marco:** o B7 não bloqueia a entrada dos convidados [Q6].

## Marcos de rede

| Marco | Condição |
|---|---|
| Validador local e devnet | desde o B1 |
| Mainnet — carteira do fundador | B1 a B6 prontos, liberação com quarentena de 24 h, conta AWS e KMS reais, dois provedores de RPC |
| Mainnet — até 20 convidados | pelo menos 7 dias do fundador sem divergência e parecer de custódia decidido; B7 não é condição |
