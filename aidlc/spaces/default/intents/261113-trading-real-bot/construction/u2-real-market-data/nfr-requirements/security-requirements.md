# Requisitos de segurança — U2 `u2-real-market-data`

Conversa: português (Brasil). Fontes:
- `construction/u2-real-market-data/functional-design/rules.md` (BR1.6, BR1.10);
- `inception/requirements-analysis/requirements.md` (NFR1, NFR2, NFR6, NFR10);
- `inception/contract-design/contract-summary.md` (X2);
- as regras de `project.md` e `team.md`;
- `nfr-requirements-questions.md` (Q1, Q3).

| ID | Requisito | Verificação |
|---|---|---|
| NFR1.1 | A chave `COINGECKO_API_KEY` vai só no cabeçalho de autenticação. Nunca aparece em log, resposta, métrica, navegador ou `NEXT_PUBLIC_*`. Fica no `.env` local e no gerenciador de segredos na nuvem [Q3] | teste que procura a chave nos logs; o `.env.example` traz só o nome |
| NFR1.2 | Só HTTPS com o certificado conferido. O endereço base vem de uma lista fixa de domínios da CoinGecko; outro domínio impede a subida. Redirecionamentos são recusados, para que o cabeçalho com a chave nunca vá a outro endereço. Todo mint é validado como Base58 de 32 a 44 caracteres antes de entrar na URL [Q3] | teste de configuração, de redirecionamento e de mint inválido |
| NFR1.3 | A resposta tem no máximo 2 MB, imposto na leitura do corpo em fluxo: a leitura para no limite, sem carregar o resto. O JSON é lido com limites de profundidade e de tamanho de texto. Uma resposta acima do limite vira falha da rodada, sem gravar nada [Q3] | teste com resposta grande e profunda |
| NFR1.4 | Rede só no pacote `com.memetrade.marketdata.infra.coingecko`. Fora dele, a regra ArchUnit proíbe no módulo `marketdata` dependências de `java.net..`, `java.net.http..`, `org.springframework.web.client..` e `org.springframework.http.client..`, com o motivo registrado [Q1] | ArchUnit, com um teste que confirma que a regra falha numa classe fora do pacote |
| NFR1.5 | Com o REAL ligado, a API e o worker não sobem com fonte simulada fora dos perfis `test` e `local` (BR1.6) | teste de subida |
| NFR1.6 | Na nuvem, só o worker sai para a CoinGecko. A API não chama o fornecedor [Q3] | o adaptador só existe no papel de processo `worker` (`@ProcessRole`), com teste de contexto; o grupo de segurança é do Infrastructure Design da U2 |
| NFR10.1 | Valores lidos direto como decimal exato, sem ponto flutuante. Instantes em UTC | ArchUnit (proibição de `double`/`float`) e testes |
| NFR2.1 | Os dados de mercado servem PAPER e REAL sem mistura de fundos. A U2 não toca em ledger nem em saldo | revisão de código |
| NFR6.1 | Os dados de mercado são públicos e não têm tenant. Nenhum dado de cliente entra no pedido ao fornecedor | revisão de código |
