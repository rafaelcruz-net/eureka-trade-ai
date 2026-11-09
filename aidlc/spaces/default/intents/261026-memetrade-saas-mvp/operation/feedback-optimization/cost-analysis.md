# Análise de custos

Conversa: português (Brasil). Resposta 2A. Não há conta na AWS, então não há Cost Explorer nem Trusted Advisor. Nenhum preço foi inventado. A topologia vem de `operation/deployment-execution/deployment-log.md` e de `operation/environment-provisioning/environment-inventory.md`. Os itens de observabilidade vêm de `operation/observability-setup/dashboards.md`, `alarms.md` e `slo-config.md`. O desempenho que dimensiona os recursos vem de `operation/performance-validation/test-results.md`.

## Custo atual

| Item | Custo | Observação |
|---|---|---|
| Nuvem | zero | sem conta |
| GitHub Actions | dentro da cota do plano, **a confirmar** | pendência da Sprint 0: valor da cota e se cobre um smoke por mudança mais a suíte completa no merge |
| Máquina local | do responsável do projeto | o notebook limita as cargas (falta de memória no `perf-audit` e numa carga `u6`) |

## Vetores de custo da AWS desenhada

| Vetor | De onde vem | Dimensionamento conhecido |
|---|---|---|
| Computação da API e do worker | contêineres (`cd-config.md`, blue/green) | 1 GB por processo no local; 20 usuários e 60 bots atendidos com folga |
| Banco gerenciado com réplica de leitura | escala da U4 | o postgres usou de 825 a 990 MB nas cargas |
| Rede (balanceador, NAT) | sub-redes privadas para a API, o worker e o banco | — |
| CloudWatch Logs e métricas | um grupo por serviço, 7 dias; métricas Prometheus coletadas | volume pequeno no beta |
| X-Ray | amostragem de 5% mais erros | volume pequeno no beta |
| AWS Backup e PITR | 12 meses de retenção | cresce com o banco |
| Registro de imagens | imagens das últimas releases | pequeno |
| Secrets Manager | 5 segredos por ambiente | pequeno |

## Alavancas de economia

| Alavanca | Efeito | Risco |
|---|---|---|
| Staging em uma zona e desligado fora de uso | corta a maior parte do custo do staging | testes de resistência agendados |
| Worker em Fargate Spot | menor custo de computação | interrupção: só se o desligamento gracioso e a retomada das tarefas (NFR4.5) bastarem; validar no staging |
| Réplica de leitura só quando o volume pedir | adia custo | precisa da medição do `growth` |
| Retenção curta de logs (7 dias) e amostragem do X-Ray | controla o volume | já decidido |
| Endpoints de VPC para serviços AWS | reduz o tráfego por NAT | configuração inicial |
| Política de ciclo de vida no registro de imagens | evita acúmulo | manter as imagens do rollback |

## Próximo passo

Estimar com números no próximo Environment Provisioning, quando houver conta e região, com a calculadora de preços ou a API de preços da AWS. Definir também um alerta de orçamento (AWS Budgets) com o valor que o responsável do projeto aprovar (C-10, C-12).
