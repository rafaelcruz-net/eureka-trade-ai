# Runbook — TxSigner: restauração, recuperação e giro da credencial

Intent `261113-trading-real-bot`, Unit U1. Fontes: `security-design.md` ("Modo de recuperação"), `reliability-design.md` ("Backup e restauração") e `infrastructure-specification.md` ("Âncora sem o endereço" e "mTLS e credencial de serviço").

Quem executa: o fundador. Cada ação administrativa no KMS gera alerta pelo CloudTrail.

## 1. O banco do TxSigner foi perdido ou restaurado

1. Restaure o RDS do TxSigner para o ponto no tempo desejado. O RPO é de 5 min.
2. Suba o TxSigner com `TXSIGNER_RECOVERY=true` (`txsigner.recovery.enabled`). Com isso:
   - nos primeiros 5 minutos de relógio, nada é assinado (as assinaturas respondem `503 RECOVERY_IN_PROGRESS`). Uma assinatura perdida na restauração tinha um blockhash que já venceu;
   - depois disso, só saques são assinados. Criação de carteira e aumento de teto ou de modo continuam recusados.
3. Para cada carteira do bot do banco da aplicação, a API chama:

   ```
   POST /internal/v1/recovery/wallets
   { "botWalletAddress": "...", "mainWalletAddress": "...", "currentCapLamports": "...", "currentMode": "..." }
   ```

   O TxSigner acha a chave pelas etiquetas e confere `network`, e então confere o `mainWalletMac` com `VerifyMac`.
   - **MAC e rede batem:** a política volta com teto 0 e modo Assinada, e o valor mais restrito entre ela e o enviado pela API é aplicado.
   - **Rede ou MAC diferentes, ou política existente com outra carteira principal:** a chave fica bloqueada (`422 RECOVERY_REFUSED`), com o evento `WALLET_BLOCKED` e um alerta. O saque continua permitido para as carteiras restauradas.
4. Ao terminar, chame `POST /internal/v1/recovery/complete`. Ele grava o `recoveryCompletedAt` na tabela `recovery_run` e devolve 409 se os 5 minutos ainda não passaram. Na próxima subida, tire o `TXSIGNER_RECOVERY`.
5. Os usuários das carteiras restauradas precisam de um consentimento novo para voltar ao teto e ao modo anteriores. A tela mostra isso pela marca `policyResetRequired`.

**Lacuna (Incident Response, 2026-11-20, IR-G7).** Hoje nenhum código da API chama `POST /internal/v1/recovery/wallets` nem `POST /internal/v1/recovery/complete`; só o controlador do TxSigner existe. Na nuvem, o fundador também não alcança o TxSigner (subrede sem internet, token de serviço, sem `ExecuteCommand`). Até essa lacuna fechar, os passos 3 e 4 não têm como ser cumpridos, e uma carteira cuja política se perdeu na restauração fica sem saque. A lacuna bloqueia a mainnet (`operation/incident-response/runbooks.md`).

Para desbloquear uma chave bloqueada por engano, use o papel de administração com MFA. O trigger da tabela `wallet_policy` recusa tirar o bloqueio pelo login da aplicação. O passo é desligar o trigger dentro de uma transação, atualizar a linha e religar o trigger, com o motivo registrado no incidente.

## 2. O banco da aplicação foi restaurado

1. O worker sobe em modo de reconciliação. Os saques novos ficam pendentes.
2. O worker lê `GET /internal/v1/signatures?since=<ponto restaurado>` e confere cada assinatura na rede, em finalized. Um saque que chegou à rede é recriado como CONFIRMED.
3. A varredura de depósitos recomeça do cursor restaurado. Os depósitos já lançados são ignorados, porque a assinatura é a chave.

**Atualização (Incident Response, 2026-11-20).** Os passos 1 a 3 acima são o desenho original da U1 e não foram implementados assim. A U5 implementou a conferência da restauração de outro jeito, e é ela que vale: o worker abre a pausa `RESTORE_CHECK_PENDING` na subida, procura na rede as assinaturas da carteira do bot que o banco não conhece e, se achar alguma, troca a pausa por `RESTORE` até a reconciliação `CLEAN` de todas as carteiras e a confirmação do administrador. Um saque que chegou à rede e sumiu do banco aparece como divergência e é corrigido por lançamento compensatório, nunca recriado à mão. O procedimento está em `docs/runbooks/real-operations.md`, seção 4, e a recuperação completa em `aidlc/spaces/default/intents/261113-trading-real-bot/operation/incident-response/incident-plan.md`, seção 6.

## 3. Giro da credencial de serviço (a cada 90 dias; alerta aos 80)

1. Gere o token novo e grave-o como nova versão `AWSCURRENT` do segredo. A versão anterior passa a `AWSPREVIOUS`.
2. O TxSigner relê o segredo a cada 5 min e aceita as duas versões durante 24 h.
3. Force uma nova implantação da API e do worker (`force-new-deployment`), para que eles leiam o token novo.
4. Depois de 24 h, retire a versão anterior.
5. Confira se a métrica `txsigner_credential_age_days` voltou a 0.

Localmente, use `TXSIGNER_SERVICE_TOKEN` com o token novo e `TXSIGNER_SERVICE_TOKEN_PREVIOUS` com o anterior.

## 4. Rollback do REAL

Desligue primeiro `memetrade.real.enabled`, ou acione o kill switch REAL, e também `txsigner.real.enabled`. Com o flag desligado, o TxSigner continua assinando os saques das carteiras já criadas, então o saque continua disponível durante todo o rollback.
