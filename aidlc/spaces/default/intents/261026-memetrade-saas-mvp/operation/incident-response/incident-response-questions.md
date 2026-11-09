# Perguntas do Incident Response

Conversa: português (Brasil). Alvo: a release local `v2026.11.08-1`. Não há conta na AWS. Por isso, SSM Automation, Incident Manager e AWS Backup só podem ser desenhados. Fontes: `operation/observability-setup/alarms.md` e `dashboards.md`, e os `reliability-design.md`, `security-design.md` e `infrastructure-specification.md` das Units.

O que já existe (lido dos desenhos):
- RTO e RPO locais: subir de novo com o `verify.sh` ou o Compose. O volume `pgdata` persiste entre reinícios, sem backup. A meta de produção é "definida com a hospedagem", com backups criptografados, restauração testada e retenção alinhada aos 12 meses da trilha (NFR7.6).
- Contenções no código: kill switch da U8 (pausa todos os bots PAPER, idempotente, com reconciliação), pausa por bot (U5), REAL desligado por padrão, tarefas atrasadas rodam ao subir (NFR4.5), eventos confirmados da auditoria nunca se perdem (NFR4.1), coleta de mercado se recupera na próxima rodada.
- Rollback por imagem com expand-contract (`rollback-runbook.md`), já ensaiado.
- Time: uma pessoa, o responsável do projeto. Os gates de REAL exigem um revisor independente ainda a nomear (C-14).

## Pergunta 1
Quais modos de falha os runbooks cobrem?

A) Um runbook por alarme Alto de `alarms.md`, mais os cenários que não têm alarme próprio:
- API fora do ar ou migração falhou;
- banco indisponível ou disco cheio;
- auditoria atrasada;
- fonte de mercado parada;
- erro na avaliação de risco;
- divergência no ledger;
- kill switch preso ou divergente;
- reuso de token de renovação (possível roubo de sessão);
- suspeita de vazamento de segredo;
- acesso entre tenants;
- release ruim (rollback).

Os alarmes Médios ganham uma linha de triagem cada (recomendado)

B) Só os alarmes Altos
X) Other (please specify)

[Answer]: A

## Pergunta 2
Quais caminhos de escalonamento e plantão?

A) Plantão de uma pessoa só: o responsável do projeto, em horário combinado com os convidados do beta. Fora do horário, a contenção automática é o kill switch, acionado pelo próprio responsável. O escalonamento externo vai para o suporte da AWS (plano a definir com a conta) e, em incidente de segurança com dados pessoais, para a avaliação jurídica (LGPD) registrada no PRD. O revisor independente (C-14) entra quando for nomeado. A matriz deixa as lacunas visíveis (recomendado)
B) Definir já uma segunda pessoa de plantão (diga quem)
X) Other (please specify)

[Answer]: A

## Pergunta 3
Que remediação automática é possível?

A) Só remediação segura e reversível:
- reinício do contêiner pelo healthcheck (hoje, pelo Compose; na AWS, pelo ECS);
- retomada das tarefas atrasadas ao subir;
- rollback automático do blue/green quando o smoke falha.

A pausa geral dos bots (kill switch) fica manual, mas com um passo único documentado. Nada automático mexe no ledger ou no banco. Na AWS, documentos SSM Automation para "reiniciar serviço", "rollback para a imagem anterior" e "ligar o kill switch", sempre com aprovação do responsável (recomendado)

B) Ligar o kill switch automaticamente em `LEDGER_DIVERGENCE` e `RISK_EVALUATION_ERRORS`
X) Other (please specify)

[Answer]: A

## Pergunta 4
Como fica a comunicação durante incidentes?

A) Para os convidados do beta: um aviso curto no canal combinado com eles, no início, a cada 30 minutos e no fim. O aviso diz que os bots PAPER foram pausados, quando for o caso, e nunca promete resultado. Internamente: um registro do incidente com linha do tempo em `docs/incidents/` e um pós-incidente para todo P1 e P2. Incidente de segurança com dado pessoal segue o fluxo de avaliação da LGPD antes de qualquer comunicação externa (recomendado)
B) Sem comunicação aos convidados no beta
X) Other (please specify)

[Answer]: A

## Pergunta 5
Quais as metas de RTO e RPO?

A) Local: como está (RTO = subir de novo, em cerca de 5 minutos; RPO = sem backup, volume persistente). Proposta para a produção do beta PAPER, a confirmar com a hospedagem:
- RTO de 1 hora para a API;
- RPO de 5 minutos, com backup contínuo do banco gerenciado e recuperação a um ponto no tempo;
- retenção dos backups de 12 meses, alinhada à trilha;
- criptografia KMS;
- restauração testada todo mês no staging (AWS Backup).

Até existir backup, o modo de recuperação é recriar o ambiente local (recomendado)

B) Metas mais rígidas (diga quais)
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Runbooks: um por alarme Alto e pelos cenários sem alarme próprio; triagem de uma linha para cada alarme Médio.
- Escalonamento: plantão só do responsável do projeto, no horário combinado; kill switch como contenção; suporte da AWS e avaliação jurídica (LGPD) como escalonamento externo; revisor independente (C-14) quando for nomeado.
- Remediação automática só segura e reversível; kill switch manual com passo único; nada automático no ledger; SSM Automation na AWS sempre com aprovação.
- Comunicação: aviso aos convidados no início, a cada 30 min e no fim, sem promessa de resultado; registro em `docs/incidents/` e pós-incidente para P1 e P2; LGPD antes de comunicar incidente com dado pessoal.
- RTO e RPO: local como está; proposta para a produção com RTO 1 h, RPO 5 min (PITR), backups por 12 meses com KMS e restauração mensal testada no staging.

- Looks correct
- Request changes

[Answer]: Looks correct
