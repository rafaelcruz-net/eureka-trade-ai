# Perguntas do Incident Response — intent `261121-paper-real-unico`

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele.

Fontes:
- `261113-trading-real-bot/operation/incident-response/`: plano, matriz de escalonamento e runbooks.
- `team.md`, seção Deployment: o fundador é o único on-call.
- `docs/runbooks/real-operations.md`: seções 8 e 10 a 13 (U2 e U4).
- `operation/observability-setup/alarms.md`, `slo-config.md` e `log-queries.md`: 3 alarmes novos, 2 propostos e as lacunas OBS-G5 e OBS-G7.
- `operation/deployment-pipeline/rollback-runbook.md`.
- `operation/environment-provisioning/security-posture-review.md`: IR-G3 e D-INF2.
- `build-and-test/test-results.md`: TGT-E2E-FLAKY.

## Q1. Modos de falha deste intent

Quais modos de falha deste intent entram nos runbooks?

A. Entram estes:
   1. **Divergência na reconciliação do P&L REAL (U2), P1:** o bloco REAL fica `UNAVAILABLE` e o ledger continua sendo a verdade.
   2. **Leitura da quantidade própria indisponível acima de 5% ou bot preso (U4), P2:** com a flag desligada isso não acontece. Com a flag ligada, o primeiro passo é desligar a flag (worker → API); o saque e "Vender tudo e sacar" continuam funcionando.
   3. **Bloco PAPER ou REAL indisponível nas telas (U1/U3), P3:** a outra metade continua aparecendo e os modos nunca são somados.
   4. **Total geral PAPER + REAL na tela, P1:** é violação de regra; volta pela imagem.
   5. **Migração 0023 a 0027 travada por `lock_timeout`:** a implantação para e se repete pelo mesmo SHA.
   6. **Falha intermitente do `e2e` (TGT-E2E-FLAKY):** é um defeito de CI, não um incidente de produção.

   Cada runbook aponta para o runbook do repositório quando ele já existe, sem repetir o texto. (recomendado)
B. Só os alarmados
X. Other (please specify)

[Answer]: A

## Q2. Escalonamento e plantão

Quem atende e para quem se escala?

A. A matriz do 261113 continua sem mudança: o fundador é o único on-call, e o segundo contato continua "a definir" (pendência do 261113). Os 3 alarmes novos entram na matriz com severidade e rota. Uma implantação na mainnet só acontece quando o fundador pode acompanhar a janela mais 30 minutos. (recomendado)
B. Definir um segundo contato agora
X. Other (please specify)

[Answer]: A

## Q3. Remediação automática

Que remediação acontece sem intervenção humana?

A. Nada novo automático:
   - O circuit breaker do ECS e a falha fechada da U4 já existem. A falha fechada significa que, se a leitura não vem, o bot não vende naquele ciclo.
   - Desligar a flag e acionar o kill switch são ações manuais do fundador, com MFA.
   - O IR-G3 limita o diagnóstico na nuvem: não há como rodar SQL. A alternativa A, uma tarefa avulsa só de leitura, fica como decisão do fundador antes de ligar a flag.

   (recomendado)
B. Automatizar o desligamento da flag
X. Other (please specify)

[Answer]: A

## Q4. Comunicação, RTO e RPO

Como se comunica um incidente e quais são as metas de recuperação?

A. Seguem o 261113: comunicação aos usuários pelo aviso no app e no canal dos convidados, e as mesmas metas de RTO e RPO. Este intent não muda dados de forma irreversível: as migrações só acrescentam, e o ledger só recebe lançamentos novos. A revisão pós-incidente é obrigatória em P1 e P2, com linha do tempo, causa e prevenção. (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Modos de falha:** seis runbooks:
  - divergência na reconciliação (P1);
  - leitura da U4 indisponível ou bot preso (P2): desligar a flag, e o saque continua funcionando;
  - bloco indisponível nas telas (P3);
  - total geral PAPER + REAL na tela (P1);
  - migração travada;
  - `e2e` instável, tratado como defeito de CI.

  Cada um aponta para o runbook que já existe [Q1].
- **Escalonamento:** a matriz do 261113; você é o único on-call, e os 3 alarmes novos entram nela [Q2].
- **Remediação:** nada novo automático. Desligar a flag e o kill switch são ações manuais com MFA. O IR-G3 limita o diagnóstico na nuvem [Q3].
- **Comunicação, RTO e RPO:** os do 261113, sem dado irreversível. A revisão pós-incidente é obrigatória em P1 e P2 [Q4].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
