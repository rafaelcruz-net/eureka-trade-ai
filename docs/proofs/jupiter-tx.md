# Prova técnica 2: transação de swap no formato da Jupiter, sem SDK

Intent `261113-trading-real-bot`, Unit U1 `u1-bot-wallet`, Bolt B1 (Step 4 do plano de Code Generation).

**Data:** 2026-11-14

**Estado:** aprovada no decodificador. A execução no validador local com contas clonadas não foi feita.

## O que a prova precisa mostrar

Vem de `bolt-plan.md` (P0), TR-C03 e TR-C05. Uma transação de swap no formato que a API da Jupiter devolve precisa:

1. ser lida pelo decodificador próprio do TxSigner, sem SDK Solana (`project.md`);
2. receber a assinatura do KMS no lugar da carteira do bot;
3. rodar no validador local com as contas clonadas.

## Como a prova está escrita

- **Decodificador:** `apps/txsigner/src/main/java/com/memetrade/txsigner/codec/` (`SolanaMessage`, `SolanaTransaction`, `CompactU16`, `ByteReader`, `Base58`). Lê mensagens legadas e v0, inclusive as tabelas de endereço (ALT). A leitura é estrita: recusa versão desconhecida, cabeçalho incoerente, índice fora da lista, codificação compact-u16 não canônica, truncamento e bytes sobrando. Uma mensagem lida é serializada de volta nos mesmos bytes.
- **Fixture:** `JupiterSwapFixture`, no código de teste. É uma mensagem v0 com o formato de uma resposta da Jupiter:
  - limite e preço de compute;
  - criação idempotente da conta de token associada;
  - a instrução de rota do programa agregador (`JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4`);
  - duas tabelas de endereço.
- **Testes:** `JupiterTxProofTest`, que roda em toda execução do Maven e da CI.

### Desvio: a fixture é sintética

A fixture não foi baixada da API da Jupiter. Ela foi montada byte a byte no código de teste, com o mesmo formato. Há dois motivos:

1. `team.md` proíbe usar carteira ou mint reais em fixtures. Uma resposta real da Jupiter carrega mints reais e o endereço de quem pede.
2. Os testes não acessam a rede.

Só os identificadores públicos de programa são reais: System, Compute Budget, Token, Associated Token e o agregador. Todas as outras contas são sintéticas. Quando a U4 gravar respostas reais da Jupiter (com o validador e as contas clonadas), a mesma prova roda sobre elas.

## Resultado

| Item | Estado |
|---|---|
| Leitura da v0 com duas ALT (cabeçalho, 8 chaves fixas, 7 contas das tabelas, 4 instruções, programa da rota) | aprovado |
| A mensagem lida volta aos mesmos bytes | aprovado |
| A assinatura do KMS (simulado) entra no lugar 0, a mensagem não muda (mesmo SHA-256) e a assinatura confere com o Ed25519 nativo | aprovado |
| A transação assinada cabe em um pacote (1.232 bytes) e é lida de volta igual | aprovado |
| Execução no validador local com as contas clonadas | não executado |

Comando:

```
sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml test -Dtest='JupiterTxProofTest,SolanaCodecTest'
```

Saída obtida: `JupiterTxProofTest` com 4 testes e `SolanaCodecTest` com 29 testes, sem falhas.

## O que ficou pendente e por quê

- **Validador local:** a máquina de desenvolvimento não tem a imagem do `solana-test-validator`. A execução de um swap real com contas clonadas também depende da montagem do swap, que é da U4 (bolt-plan). Na U1, o validador entra pelo spec de ponta a ponta da carteira (Step 23), que envia uma transferência assinada pelo TxSigner. O swap no validador fica como critério de entrada da U4.

## Consequência para o Bolt

A decodificação e a assinatura funcionaram, então o B1 segue.
