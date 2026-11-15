# Projeto de segurança e isolamento — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes: `construction/u4-real-execution/nfr-requirements/security-requirements.md` (U4-NFR1.1 a U4-NFR8.10) e `nfr-design-questions.md` (Q2, Q4).

| Requisito | Como o projeto atende |
|---|---|
| U4-NFR1.1 (sem SDK) | A API monta (`realexecution.domain.solana`, com o `Base58Codec` da U1) e o TxSigner lê (o leitor que já existe), os dois sem dependência de terceiros. As regras ArchUnit que já proíbem o SDK e as APIs de chave valem nos dois apps. Os dois apps leem o mesmo conjunto de vetores gerado pelo SDK só no escopo de teste, e um teste confere que os arquivos de vetores são iguais (ver o desvio de Q2 em `logical-components.md`). |
| U4-NFR1.2 (política) | `SwapPolicy` no TxSigner. Ela lê a mensagem pelo leitor do TxSigner, confere cada item de BR4.3 e devolve o motivo. Há um teste de ataque por motivo, escrito antes do código, e transações gravadas da Jupiter que devem passar. |
| U4-NFR1.3 (logs) | Os logs usam o `LogMasking` que já existe para assinatura e endereço. A transação assinada nunca é registrada em log; ela fica só na coluna da ordem. |
| U4-NFR1.4 (rede) | Regra ArchUnit: dentro de `realexecution`, só `infra.jupiter` pode depender de `java.net` ou dos clientes HTTP do Spring. |
| U4-NFR1.5 (sem tabela) | `MessagePlan` monta só com chaves estáticas e confere o tamanho antes de pedir a assinatura. A `SwapPolicy` do TxSigner recusa mensagem de swap com tabela (`LOOKUP_TABLE_NOT_ALLOWED`); o leitor continua aceitando tabela no saque e no `JupiterTxProofTest`. |
| U4-NFR2.1 (REAL/PAPER) | Regra ArchUnit: `realexecution` não depende do ledger PAPER nem do `PaperBalance`. O bot REAL segue um caminho próprio no ciclo. |
| U4-NFR6.1 (tenant) | Toda consulta filtra por tenant e usuário vindos da autenticação. O consumidor confere de novo a posse do bot. O canal filtra pelo dono. |
| U4-NFR6.2 (navegador) | O `SigningSessionService` compara o resumo da mensagem recebida com o gravado e confere o Ed25519 antes de qualquer outra coisa (Q4). |
| U4-NFR8.1, U4-NFR8.10 (rede) | As guardas que já existem são ampliadas: `RealProperties` na API e no worker (`MEMETRADE_REAL_NETWORK`) e `ProductionChecks` no TxSigner (`TXSIGNER_NETWORK`). Mainnet só no perfil de produção, com configuração explícita, e a API e o TxSigner precisam concordar na rede, conferido na subida pela rota de saúde do TxSigner. |
| U4-NFR8.2 (REAL desligado) | Com `memetrade.real.enabled=false`, o ciclo nunca chama `recordDecision`, os trabalhos não pegam ordens e o TxSigner recusa `SWAP` com `REAL_DISABLED`. |
