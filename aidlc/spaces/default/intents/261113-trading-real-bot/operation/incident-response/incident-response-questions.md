# Perguntas da Incident Response — intent 261113-trading-real-bot

Conversa: português (Brasil). As respostas seguem a instrução do responsável "siga sem me perguntar": em cada pergunta foi escolhida a opção recomendada.

Fontes:
- `operation/observability-setup/` (alarmes, SLOs, painéis e consultas);
- `operation/deployment-pipeline/rollback-runbook.md`;
- `operation/environment-provisioning/validation-report.md`;
- os runbooks existentes em `docs/runbooks/`: `txsigner-recovery.md`, `real-risk.md`, `real-exits.md` e os demais;
- `construction/u*/nfr-design/reliability-design.md`;
- `team.md`, seção Deployment: o fundador é o revisor único dos gates do REAL, e o rollback do REAL começa pelo kill switch;
- `project.md`: o saque fica sempre disponível.

## Pergunta 1 — Modos de falha prioritários

Quais modos de falha os runbooks cobrem primeiro?

A. Os que tocam dinheiro, depois os de plataforma. A lista:
   - ordem REAL ambígua;
   - divergência entre o ledger e a rede;
   - TxSigner indisponível ou recusando;
   - KMS com erro ou throttling;
   - RPC fora do ar (os dois provedores);
   - Jupiter fora do ar;
   - fonte de preços parada;
   - saque parado ou recusado;
   - perda máxima do dia em massa;
   - suspeita de comprometimento (credencial, papel do TxSigner, achado do GuardDuty);
   - banco indisponível ou restauração;
   - caminho das métricas quebrado (`metrics-absent`);
   - implantação com falha.

   Cada alarme de `alarms.md` aponta para um runbook. (recomendado)
B. Só os de plataforma (ECS, RDS, balanceador)
X. Other (please specify)

[Answer]: A

## Pergunta 2 — Escalonamento e plantão

Como é o escalonamento e o plantão?

A. O fundador é o único plantonista, 24x7 no piloto, porque é solo.
   - Os alarmes de severidade alta (dinheiro e segurança) vão por e-mail e SMS pelo SNS. Os de média vão por e-mail.
   - A matriz de escalonamento registra o segundo contato como "a definir", que é condição antes dos convidados.
   - Entram também os contatos externos: o suporte da AWS, os provedores de RPC, a Jupiter e a CoinGecko, com o canal de cada um.
   - Nenhum serviço de plantão pago no piloto.

   (recomendado)
B. Contratar um serviço de plantão agora
X. Other (please specify)

[Answer]: A

## Pergunta 3 — Remediação automática

Que remediação pode ser automática?

A. Só o que é seguro e reversível:
   - o circuit breaker do ECS;
   - o reinício de tarefa não saudável;
   - a pausa global automática que o código já faz (divergência, restauração e kill switch por regra);
   - os trabalhos de repetição idempotentes que já existem.

   Nada que mova dinheiro, desligue o saque ou acione o kill switch por conta própria. O kill switch manual é sempre o primeiro passo humano. Os runbooks ficam em Markdown com comandos exatos, e não como documento de automação SSM, porque não há conta ainda. A versão SSM fica como candidata depois da devnet. (recomendado)
B. Automação SSM completa com acionamento pelos alarmes
X. Other (please specify)

[Answer]: A

## Pergunta 4 — Comunicação durante incidentes

Como é a comunicação durante um incidente?

A. No piloto, o fundador avisa a si mesmo e, quando houver convidados, os convidados afetados. Os modelos de mensagem são em português, em texto simples:
   - a mensagem diz o que aconteceu, se o dinheiro está seguro, se o saque continua disponível e qual é o próximo aviso;
   - nunca promete prazo de lucro nem recuperação;
   - um aviso no app (faixa) é candidato para depois.

   Todo incidente P1 ou P2 tem revisão pós-incidente com linha do tempo, causa raiz e prevenção, conforme as regras da fase de Operação. (recomendado)
B. Página de status pública
X. Other (please specify)

[Answer]: A

## Pergunta 5 — Metas de RTO e RPO

Quais são as metas de RTO e de RPO?

A. As do NFR Design. Os bancos têm PITR, com RPO de até 5 minutos, e backup diário com 30 dias de guarda.
   - RTO de 4 horas para a API e o TxSigner no piloto.
   - O saque é o primeiro serviço a voltar.
   - A recuperação do TxSigner segue o `docs/runbooks/txsigner-recovery.md`, que restaura e reconcilia antes de qualquer assinatura nova.
   - Depois de uma restauração, o REAL fica pausado até a reconciliação limpa (RESTORE e CHECK_PENDING).

   O ensaio de restauração acontece na devnet antes da mainnet. (recomendado)
B. RTO de 1 hora com réplica em outra zona
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Runbooks por modo de falha**, começando pelos que tocam dinheiro: ordem ambígua, divergência, TxSigner, KMS, RPC, Jupiter, preço parado, saque, perda do dia em massa e suspeita de comprometimento. Depois vêm os de plataforma: banco, métricas e implantação. Cada alarme aponta para um runbook [Q1].
- **Plantão do fundador**, único plantonista, 24x7. A severidade alta vai por e-mail e SMS, e a média só por e-mail. O segundo contato fica "a definir", como condição antes dos convidados. Os contatos externos dos fornecedores ficam registrados. Não há serviço de plantão pago [Q2].
- **Remediação automática só no que é seguro e reversível**: o circuit breaker, o reinício de tarefa, as pausas automáticas do código e as repetições idempotentes. Nada move dinheiro, desliga o saque ou aciona o kill switch sozinho. Os runbooks ficam em Markdown com comandos exatos, e o SSM fica como candidato depois da devnet [Q3].
- **Comunicação**: modelos em português que dizem se o dinheiro está seguro e se o saque continua, sem promessa. Revisão pós-incidente em todo P1 ou P2 [Q4].
- **Recuperação**: RPO de 5 min e RTO de 4 h, conforme o NFR Design. O saque é o primeiro serviço a voltar, e o REAL fica pausado até a reconciliação ficar limpa. O ensaio de restauração é feito na devnet antes da mainnet [Q5].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
