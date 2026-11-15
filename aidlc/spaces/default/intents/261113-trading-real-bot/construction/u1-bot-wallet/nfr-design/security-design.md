# Desenho de segurança — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes: `construction/u1-bot-wallet/nfr-requirements/security-requirements.md` (NFR1.x, NFR2.1, NFR6.1, NFR10.1), `construction/u1-bot-wallet/functional-design/rules.md` (BR1.x, BR4.x, BR5.x, BR6.x), `inception/contract-design/contract-summary.md` (R1, mensagem de consentimento) e `nfr-design-questions.md` (Q4, Q6). Revisado depois da primeira revisão: a recuperação agora se ancora nas etiquetas imutáveis da chave e não usa mais consentimentos guardados.

## Camadas

1. **Navegador → API:** a autenticação existente, com o token de acesso em memória, e as rotas `/api/v1/real/...`. Tenant e usuário vêm do token (NFR6.1); os limites de uso usam o mecanismo existente (NFR1.9).
2. **API e worker → TxSigner:** só pela rede privada. No local, credencial de serviço por cabeçalho. Na nuvem, mTLS mais a credencial, e o certificado identifica o chamador (`api` ou `worker`). No giro, a credencial antiga e a nova valem juntas por 24 h (NFR1.5).
3. **TxSigner → KMS:** papel próprio, com menor privilégio (NFR1.2).
   - **Permitido:**
     - `CreateKey`, só com as etiquetas obrigatórias na própria criação (`aws:RequestTag/app`, `aws:TagKeys`);
     - `Sign`, `GetPublicKey`, `DescribeKey` e `ListResourceTags`, só em chaves com `aws:ResourceTag/app = eureka-botwallet`;
     - `ListKeys`, que não aceita condição de recurso e só lista identificadores.
   - **Negado explicitamente:** `TagResource`, `UntagResource`, `ScheduleKeyDeletion`, `DisableKey`, `PutKeyPolicy`, `CreateGrant` e `Decrypt`. Assim as etiquetas só existem na criação e nunca mudam.
   - **Política de cada chave:** só o papel do TxSigner pode assinar. O papel de administração exige MFA, não assina e serve só para emergência.
   - **CloudTrail:** registra tudo; qualquer ação administrativa gera alarme, e mais de 10 chaves criadas por hora na conta também.
   - **Limites de criação:** por usuário, 3 ativações por dia (NFR1.9); na aplicação, no máximo 30 chaves por hora, com alerta a partir de 10 por hora, para que uma rajada não esgote as ativações legítimas.
4. **Banco do TxSigner:** usuário e esquema próprios, sem acesso da API nem do worker.

## Etiquetas da chave (âncora da carteira)

Na criação (`CreateKey`), o TxSigner grava etiquetas que o papel nunca consegue alterar:
- `app = eureka-botwallet`;
- `mainWallet = <endereço da carteira principal>`, conferido contra a assinatura do consentimento de ativação antes da criação;
- `creationRequestId = <challengeId>`;
- `network = <rede>`.

O endereço da carteira principal é um dado público da rede e fica visível só a quem lê as etiquetas do KMS: o próprio papel e a administração com MFA. Ele não entra em log nem na auditoria, onde continua só como HMAC.

## Política de assinatura do TxSigner

- **`WithdrawalPolicy`:** componente puro que decodifica a mensagem com decodificador próprio, sem SDK, e confere a forma exata de BR5.1. A transação não pode ter ALT, tem um único assinante e pagador, não usa nonce durável, e contém só SetComputeUnitLimit, SetComputeUnitPrice e uma Transfer para a carteira principal da WalletPolicy, com o valor e a taxa calculados (NFR4.3).
- **`ConsentVerifier`:** confere a mensagem canônica, a assinatura Ed25519 com o Java nativo, `purpose`, `bot-wallet`, o teto de até 10 SOL, a rede e o nonce (BR5.3).
- **`SigningService`:** só chama o KMS depois que a política aprova. Cada pedido grava um SigningRecord, idempotente por `signingRequestId` e `messageDigest`, e um evento. Com o REAL desligado, só WITHDRAWAL é aceito (NFR1.11).
- **Falha fechada:** qualquer exceção do decodificador ou da política vira recusa, nunca assinatura.

## Configuração de produção

Ao subir no perfil de produção, o TxSigner confere e não sobe se alguma condição falhar (NFR1.6):
- o endereço do KMS é o padrão da AWS ou um dos endpoints de VPC da própria conta listados na configuração;
- a conta da credencial (consultada na AWS ao subir) é a conta configurada;
- o profile `kms-sim` não está ativo.

## Modo de recuperação (NFR4.13, Q4)

**Início:** depois de restaurar o banco do TxSigner, o operador sobe o processo com `txsigner.recovery=true`, como diz o runbook. Nesse modo, o TxSigner assina só saques, e só depois do passo 1.

1. Espera 150 blocos antes de assinar qualquer coisa. Uma assinatura perdida na restauração tinha blockhash que já venceu.
2. Lista as chaves com `app = eureka-botwallet` (`ListKeys` mais `ListResourceTags`). Para cada chave sem WalletPolicy, recria a política **só a partir das etiquetas**: carteira principal da etiqueta `mainWallet`, endereço calculado da chave pública, teto 0 e modo Assinada. Isso basta para o saque, que vai sempre para a carteira principal ancorada na chave. Teto e modo voltam só com um consentimento novo, dentro da validade, conferido pelo `ConsentVerifier`.
3. Para as políticas restauradas, a API envia os rebaixamentos vigentes, e o TxSigner aplica o valor mais restrito. Uma política restaurada cuja carteira principal difere da etiqueta da chave é bloqueada, com alerta.

**Fim:** com os passos 1 a 3 concluídos, o TxSigner grava `recoveryCompletedAt`, gera um evento e volta ao modo normal; o operador tira a opção na próxima subida. Nenhum consentimento vencido é aceito em momento algum. Uma API invadida não consegue trocar a carteira principal de uma chave existente, porque a etiqueta é imutável e o saque só vai para ela.

## Dados e logs

- O HMAC da carteira principal usa uma chave do gerenciador de segredos, separada da credencial do TxSigner (NFR1.12).
- Um filtro de log mascara endereço e assinatura, deixando os seis primeiros e os quatro últimos caracteres. O validador da auditoria aceita esses valores só nas chaves tipadas (BR6.2).
- O dono vê o endereço da carteira do bot por extenso nas próprias respostas. Nenhuma resposta mostra a referência da chave (NFR1.7).

## Validação de entrada

- Lamports: texto inteiro com padrão fixo, convertido para inteiro de 64 bits com verificação de estouro (NFR10.1).
- Mensagens e assinaturas: tamanho máximo e alfabeto Base58 conferidos antes de qualquer decodificação.

## Testes da política de acesso

O `kms-sim` não avalia permissões. A política do papel e a da chave são testadas de duas formas:
- um teste que lê o JSON versionado e confere as ações permitidas, as negadas e as condições;
- antes da mainnet, o simulador de políticas da AWS, rodado pelo fundador, com o resultado anexado ao checklist de liberação.

A recuperação é testada no `kms-sim`, que guarda as etiquetas e recusa alterá-las.
