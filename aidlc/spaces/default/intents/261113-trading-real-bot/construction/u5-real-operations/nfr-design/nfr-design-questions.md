# Perguntas de NFR Design — U5 `u5-real-operations`

Conversa: português (Brasil). Base:
- `construction/u5-real-operations/nfr-requirements/` (U5-NFR1.1 a U5-NFR10.1);
- `construction/u5-real-operations/functional-design/` (BR1.1 a BR7.2);
- o código atual:
  - `botwallet`, com `RpcChainWatcher` de dois provedores, a liberação mínima e o saque;
  - `administration`, com o kill switch PAPER;
  - `privacy`, com a anonimização;
  - a migração 0015, com a função `SECURITY DEFINER` de expurgo;
  - `RuntimeSettingsGuard`.

## Q1. [Reconciliação] Como separar a regra da leitura?

A. A regra fica numa classe de domínio pura, `ReconciliationPolicy`. Ela recebe uma foto imutável e devolve `CLEAN`, `DIVERGENT`, `DEFERRED` ou `UNAVAILABLE`, com as diferenças por ativo. A foto tem:
- o slot finalizado;
- os saldos da rede;
- os saldos do ledger;
- as ordens e os saques em trânsito;
- as entradas não lidas.

A leitura fica em `ReconciliationReader`: uma chamada em lote `getMultipleAccounts` pela carteira e pelas contas de token, a assinatura mais recente e o slot. O trabalho roda as carteiras num executor de até 4. Os cenários da especificação viram testes de unidade da política, escritos antes do código.
B. Regra e leitura juntas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Pausas globais] Como a M1 entrega o kill switch e a restauração sem linha por usuário?

A. A M1 `readRealState` junta às pausas do usuário as pausas globais. Elas saem de duas leituras de uma linha só, sem cache:
- `real_kill_switch.active` dá `KILL_SWITCH`;
- o `RestoreMarker` aberto dá `RESTORE` ou `RESTORE_CHECK_PENDING`.

A M7 `current()` é a mesma leitura do kill switch. Assim a barreira, a reconferência antes de assinar e a de antes de enviar enxergam as pausas globais sem nenhuma mudança na U3 e na U4.
B. Gravar uma pausa por usuário
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Restauração] Onde fica a conferência na subida?

A. Num `RestoreDetector` no worker, que roda no `ApplicationReadyEvent` e no trabalho `restore-check` (1 min) enquanto não termina:
1. Abre o marcador `RESTORE_CHECK_PENDING`.
2. Para cada carteira, pagina `getSignaturesForAddress` com `until` igual à assinatura mais recente que o banco conhece.
3. Separa as assinaturas em que a carteira assina e procura cada uma nas ordens, nos envios e nos saques.
4. Fecha o marcador, ou o troca por `RESTORE`.
B. Conferência manual
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Anonimização] Como a redação é feita?

A. Numa função SQL `redact_real_wallet_audit(user_id)`, `SECURITY DEFINER`, no padrão da 0015:
- dona do esquema e com `search_path` fixo;
- `EXECUTE` só para o papel da aplicação;
- age só nas linhas do usuário: põe NULL em `audit_event.bot_wallet_address` e tira a chave `botWalletAddress` de `audit_event.details` e de `audit_outbox.payload`;
- devolve a contagem.

A M1 `closeForAnonymization` chama a função na mesma transação da limpeza das colunas. Um teste auxiliar percorre o esquema pelo `information_schema` e procura os endereços em toda coluna de texto e de JSON.
B. Desligar o gatilho da trilha durante a anonimização
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [CI] Como o adaptador acha a execução do commit implantado?

A. O SHA do commit entra na imagem no build (`MEMETRADE_BUILD_SHA`, lido pelo `BuildInfo`). O `GitHubCiStatus` chama `GET /repos/{owner}/{repo}/actions/workflows/ci.yml/runs?head_sha=<sha>&branch=main&event=push` com o token só de leitura e tempo limite de 5 s, e lê só `status` e `conclusion` da execução mais recente:
- erro de rede ou HTTP dá `CI_UNREACHABLE`;
- execução ausente, em andamento ou com conclusão diferente de `success` dá `CI_RED`.

No local e nos testes, o `ConfiguredCiStatus` devolve um valor da configuração e é recusado na mainnet.
B. Ler a última execução de main, qualquer commit
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Corrida da liberação] Como serializar a contagem dos 20 convidados?

A. A criação de liberação na mainnet pega uma trava de transação do PostgreSQL de chave constante (`pg_advisory_xact_lock(<constante da U5>)`) antes de contar e gravar. A unicidade "uma liberação não revogada por usuário e rede" é garantida por um índice único parcial. As mudanças de estado da liberação, do kill switch e da divergência são compare-and-set.
B. Contar sem trava
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Regra da reconciliação pura e testada antes do código; leitura em lote; executor de até 4 [Q1].
- Kill switch e restauração entram na M1 por leitura ao vivo de uma linha, sem pausa por usuário [Q2].
- Conferência de restauração no worker, na subida e a cada minuto enquanto pendente, com paginação completa [Q3].
- Redação da trilha por função `SECURITY DEFINER` no padrão da 0015, com teste que percorre o esquema inteiro [Q4].
- A CI é lida pelo SHA gravado na imagem, só `status` e `conclusion`; valor configurado só fora da mainnet [Q5].
- A contagem dos 20 convidados é serializada por trava do PostgreSQL; liberação única por índice; compare-and-set nas mudanças [Q6].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
