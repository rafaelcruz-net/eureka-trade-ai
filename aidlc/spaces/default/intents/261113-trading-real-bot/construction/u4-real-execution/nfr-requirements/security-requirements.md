# Requisitos de segurança e isolamento — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes:
- `construction/u4-real-execution/functional-design/rules.md` (BR4.2, BR4.3, BR10.1, BR10.2);
- `inception/requirements-analysis/requirements.md` (NFR1, NFR2, NFR6);
- `project.md` (Forbidden: sem SDK Solana, TxSigner só dentro da política, navegador só assina; Mandated: chave só no KMS);
- `team.md` (testes obrigatórios por risco);
- `nfr-requirements-questions.md` (Q2, Q8).

| ID | Requisito | Verificação |
|---|---|---|
| U4-NFR1.1 | Nenhum SDK Solana nem API de geração ou importação de chave no código de produção da API e do TxSigner. A mensagem v0 é montada e lida por código próprio (Base58, serialização compacta), e a assinatura da carteira principal é conferida com o Ed25519 nativo do Java | regras ArchUnit que já existem (sem SDK, sem chave) rodando sobre os pacotes novos; testes de ida e volta da mensagem com vetores gerados pelo SDK só no código de teste |
| U4-NFR1.2 | O TxSigner só assina swap dentro da política BR4.3 | testes de ataque escritos antes do código, um por motivo de recusa, mais transações reais da Jupiter gravadas que devem passar |
| U4-NFR1.5 | A mensagem do swap não usa tabelas de endereços (address lookup tables): o servidor monta a mensagem v0 só com chaves estáticas, a partir das instruções da Jupiter, e pede a rota com `maxAccounts` limitado (padrão 40) para caber em 1.232 bytes. Se não couber, cota de novo uma vez com `maxAccounts` menor; se ainda não couber, a ordem vira `FAILED` (`ROUTE_TOO_LARGE`). O TxSigner recusa qualquer mensagem com tabela de endereços (`LOOKUP_TABLE_NOT_ALLOWED`), porque sem rede ele não as resolve e sem elas não confere programa, dono nem destino | vetores de teste com mensagem sem tabela (passa), com tabela (recusa) e com tabela adulterada (recusa); teste de rota grande que vira `ROUTE_TOO_LARGE` |
| U4-NFR1.3 | Nenhuma transação assinada inteira, chave ou assinatura de login em log; a assinatura on-chain só aparece mascarada no log e inteira nos campos tipados da auditoria (M8) | teste do mascaramento e varredura de log do `verify.sh` |
| U4-NFR1.4 | A rede só é usada em `realexecution.infra.jupiter` e no adaptador RPC da U1 | regra ArchUnit com teste que comprova a falha |
| U4-NFR2.1 | Bot REAL nunca escreve no ledger, no saldo ou nas posições PAPER, e o P&L REAL nunca lê dado PAPER | teste de separação REAL/PAPER |
| U4-NFR6.1 | Tenant e usuário vêm da autenticação ou do bot; ordem, liquidação, sessão de assinatura e P&L de outro usuário respondem 404; o canal só leva eventos do dono | teste de isolamento por rota e no canal |
| U4-NFR6.2 | O navegador nunca monta nem envia transação: só lê a sessão e devolve a transação assinada, conferida byte a byte pelo servidor | teste de `SIGNED_MESSAGE_MISMATCH` |
| U4-NFR8.1 | Mainnet só com configuração explícita do perfil de produção; mainnet fora dele recusa a subida da API, do worker e do TxSigner | teste de subida |
| U4-NFR8.10 | A Jupiter só opera na mainnet. Nos testes e no `verify.sh`, um stub da Jupiter (WireMock) devolve cotação e instruções gravadas com as contas que o validador local clonou; o blockhash vem sempre do RPC do ambiente (o validador local), porque o servidor monta a mensagem. Na devnet o REAL testa só carteira, depósito e saque | spec de ponta a ponta do swap no validador local |
| U4-NFR8.2 | REAL desligado por padrão: com a configuração padrão, nenhuma ordem REAL nasce, nenhum swap é assinado e nada é pedido ao KMS | o teste obrigatório do `team.md`, estendido ao swap |
