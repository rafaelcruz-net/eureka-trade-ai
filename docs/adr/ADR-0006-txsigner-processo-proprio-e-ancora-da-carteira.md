# ADR-0006 — TxSigner: processo próprio, política de conteúdo e âncora da carteira

**Data:** 2026-11-14

**Estado:** aceita (intent `261113-trading-real-bot`, Unit U1 `u1-bot-wallet`)

## Contexto

O REAL usa uma carteira do bot por usuário. A chave dessa carteira fica no AWS KMS (ADR-005, ADR-008 e ADR-010 do intent). Uma API invadida não pode:

- sacar para outro destino;
- assinar uma transação fora da forma permitida;
- trocar a carteira principal de uma carteira já criada.

O monólito (API e worker) não tem credencial do KMS.

## Decisão

1. **Processo próprio.** O TxSigner fica em `apps/txsigner`, com Maven, banco (esquema `txsigner`), logins, papel IAM e credencial de serviço próprios. É o único processo com permissão de `Sign` no KMS. Ele confere de novo todo consentimento (BR5.3) e aplica a política de conteúdo do saque (BR5.1) antes de chamar o KMS. Ele falha fechado: um erro de decodificação vira recusa, nunca assinatura.
2. **Sem SDK Solana em nenhum escopo.** O TxSigner tem codec, Base58 e verificação Ed25519 próprios. O enforcer proíbe o SDK Solana e as bibliotecas de chave também nos testes, e as transações de teste são montadas byte a byte.
3. **Chave Ed25519 do KMS.** A chave é `ECC_NIST_EDWARDS25519` e assina com `ED25519_SHA_512` sobre `RAW`, o que é Ed25519 pura (`docs/proofs/kms-ed25519.md`).
4. **Âncora sem o endereço.** A chave nasce com quatro etiquetas imutáveis:
   - `app`;
   - `mainWalletMac`, que é o HMAC do endereço da carteira principal feito com a chave HMAC do KMS;
   - `creationRequestId`;
   - `network`.

   A recuperação só reconstrói uma política quando o endereço enviado pela API passa no `VerifyMac` contra a etiqueta. A política volta com teto 0 e modo Assinada.
5. **A referência da chave não sai do TxSigner.** A rota `POST /internal/v1/wallets` devolve só o endereço público. A API não guarda `kmsKeyRef`, e a tabela `bot_wallet_account` não tem essa coluna (NFR1.7).
6. **Idempotência.**
   - A criação é idempotente por `requestId`.
   - As mudanças de política são idempotentes por `requestId` (tabela `policy_change`).
   - As assinaturas são idempotentes por `signingRequestId` mais o `messageDigest`. O `SigningRecord` guarda a transação assinada, para devolver a mesma resposta sem chamar o KMS de novo. O mesmo `signingRequestId` com outra mensagem é recusado com `REQUEST_MISMATCH`.
7. **Auditoria do TxSigner.** Cada decisão vira uma linha JSON no logger `audit` e uma contagem em `txsigner_decisions_total{kind,decision,reason}`, sem endereço nos rótulos. O TxSigner não tem acesso à saída de eventos do monólito.

## Consequências

- Uma API invadida só consegue criar uma carteira ligada a uma carteira principal da qual tenha a assinatura (risco residual aceito no ADR-010). Ela não consegue sacar para outro destino.
- Perder o banco do TxSigner obriga a usar o modo de recuperação (`docs/runbooks/txsigner-recovery.md`), com 5 minutos sem assinar nada.
- O contexto de `WITHDRAWAL` no R1 ganha `feeLamports`, e há códigos de recusa novos (adendo do contrato).

## Alternativas rejeitadas

- **Assinar no próprio monólito com o KMS:** daria à API e ao worker a credencial que assina. Fere o NFR1.2.
- **Guardar o endereço da carteira principal na etiqueta:** o endereço ficaria no CloudTrail e nas etiquetas, o que fere o NFR1.10 e a regra de anonimização.
- **SDK Solana só no TxSigner:** traria uma superfície grande de dependências para o único processo que assina. A forma do saque é pequena o bastante para um codec próprio e testado com vetores e entradas malformadas.
