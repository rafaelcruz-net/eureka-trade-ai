# Plano de resposta a incidentes

Conversa: português (Brasil). Respostas 1A a 5A de `incident-response-questions.md`. Liga os alarmes de `operation/observability-setup/alarms.md` e os painéis de `operation/observability-setup/dashboards.md` aos runbooks de `runbooks.md`. As metas de recuperação vêm dos `reliability-design.md` e as obrigações de segurança dos `security-design.md` (`construction/<unit>/nfr-design/`). A topologia vem dos `infrastructure-specification.md`.

## Severidades

| Nível | Definição | Exemplos | Reconhecimento | Pós-incidente |
|---|---|---|---|---|
| P1 | Risco a dinheiro simulado ou dado de cliente, ou serviço todo fora do ar | divergência no ledger, acesso entre tenants, vazamento de segredo, ordem sem checagem de risco, API fora do ar | 15 min | obrigatório |
| P2 | Função importante degradada | fonte parada > 10 min com bots ativos, kill switch preso, consumo rápido do SLO | 15 min | obrigatório |
| P3 | Degradação sem efeito para os convidados | tarefa atrasada, latência acima da meta, expurgo falhou | mesmo dia | opcional |

## Fluxo

1. **Detectar**: alarme (`alarms.md`), saúde `DOWN` ou `DEGRADED`, falha do smoke ou relato de convidado.
2. **Registrar**: abrir `docs/incidents/AAAA-MM-DD-<slug>.md` com hora UTC de início, alarme, severidade e responsável.
3. **Conter**: em qualquer dúvida sobre os bots PAPER, ligar o kill switch (passo único em `runbooks.md`).
4. **Comunicar**: aviso inicial aos convidados (abaixo).
5. **Diagnosticar e corrigir** pelo runbook do alarme.
6. **Verificar**: saúde `UP`, alarme limpo e smoke verde.
7. **Encerrar**: aviso final, desligar o kill switch se foi ligado, fechar a linha do tempo.
8. **Pós-incidente** (P1 e P2) em até 5 dias úteis: linha do tempo, causa raiz, o que funcionou, ações de prevenção com dono e prazo, e teste de regressão para o defeito.

## Comunicação

| Público | Quando | Conteúdo |
|---|---|---|
| Convidados do beta | início, a cada 30 min e no fim, pelo canal combinado com eles | o que está afetado, se os bots PAPER foram pausados, próxima atualização. Nunca promete resultado, lucro ou prazo que não se controla |
| Registro interno | contínuo | linha do tempo em UTC, decisões, comandos usados, sem segredo nem dado pessoal |
| Incidente com dado pessoal | antes de qualquer comunicação externa | avaliação de LGPD: natureza do dado, titulares afetados, necessidade de comunicar à ANPD e aos titulares |

Modelo do aviso: "Estamos com instabilidade em <função> desde <hora>. Os bots de simulação (PAPER) <foram pausados / seguem ativos>. Nenhum dinheiro real é movimentado pela plataforma. Próxima atualização às <hora>."

## Remediação automática

| Ação | Onde | Gatilho | Reversível |
|---|---|---|---|
| Reinício do contêiner | Compose (local), ECS (AWS) | healthcheck falhando | sim |
| Retomada de tarefas atrasadas | aplicação | ao subir (NFR4.5) | sim |
| Rollback do blue/green | pipeline de CD (AWS) | smoke de ambiente falhando antes da troca de tráfego | sim |
| SSM `MemeTrade-RestartService`, `MemeTrade-RollbackImage`, `MemeTrade-KillSwitchOn` | AWS (desenho) | manual, com aprovação do responsável do projeto | sim |

Nada automático altera o ledger, apaga dados ou desliga o kill switch.

## Recuperação de desastre

| Item | Local (hoje) | Produção do beta PAPER (proposta, a confirmar com a hospedagem) |
|---|---|---|
| RTO | subir de novo com o Compose ou o `verify.sh`, cerca de 5 min | 1 h para a API |
| RPO | sem backup; o volume `pgdata` persiste entre reinícios; perda do volume perde os dados locais | 5 min, com backup contínuo e recuperação a um ponto no tempo (PITR) do banco gerenciado |
| Backups | nenhum | AWS Backup: plano diário mais PITR; retenção de 12 meses, alinhada à trilha (NFR7.6); criptografia KMS; cópia em outra região a avaliar com o custo |
| Teste de restauração | — | mensal no staging: restaurar o backup mais recente, subir a aplicação, rodar o smoke, conferir a reconciliação do ledger com 0 divergência; resultado registrado |
| Imagens | reconstruídas da tag | registro com as imagens das últimas releases (`cd-config.md`) |

Procedimento de restauração (AWS, desenho): (1) ligar o kill switch, se o banco ainda responder; (2) restaurar a instância a um ponto anterior ao incidente; (3) apontar a aplicação para a instância restaurada; (4) rodar `db-migrate` (só expand); (5) smoke e reconciliação do ledger; (6) avisar os convidados sobre o intervalo perdido.

## Integração futura (AWS)

Quando a conta existir, os alarmes Altos abrem um incidente no AWS Systems Manager Incident Manager com o plano de resposta "memetrade-beta" (contato: o responsável do projeto; runbook ligado ao alarme). Até lá, o fluxo acima é seguido à mão.
