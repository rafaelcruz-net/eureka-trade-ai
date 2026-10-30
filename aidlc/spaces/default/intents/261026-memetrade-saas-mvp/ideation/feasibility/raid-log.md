# RAID Log — MemeTrade Analytics

**Status date:** 26/10/2026  
**Escala:** Alto / Médio / Baixo. A avaliação é qualitativa e provisória; não há métricas operacionais ou orçamento para quantificação.

## Risks

| ID | Risco | Probabilidade / impacto | Tratamento | Responsável / gatilho |
|---|---|---|---|---|
| R-01 | O serviço de sinais, recomendações ou execução pode se enquadrar em obrigações regulatórias brasileiras conforme os ativos e a atividade efetivamente prestada. | Média / Alto | Parecer jurídico sobre CVM, regras BCB para serviços de ativos virtuais, PLD/FT, tributação e oferta a terceiros antes de SaaS e antes de REAL. Não presumir enquadramento ou dispensa. | Jurídico a nomear; antes de disponibilização pública e gate REAL. |
| R-02 | Um fornecedor pode restringir redistribuição/exibição comercial ou alterar quotas/preço, inviabilizando a fonte escolhida. | Média / Alto | Comparar duas fontes em G0; revisar termos, licenças, quotas e custo total; manter fornecedor como não aprovado até validação. | Responsável do projeto + jurídico; antes de integrar dado de terceiros ao SaaS. |
| R-03 | Não ser possível demonstrar delegação compatível com os swaps pretendidos, limites agregados e revogação segura. | Alta / Alto | Manter REAL desabilitado; validar o mecanismo e o threat model; auditoria independente e testes de revogação/reconciliação antes de qualquer rollout. | Segurança + especialista Solana + auditor externo a nomear; gate G2/G3. |
| R-04 | PAPER pode superestimar desempenho se fills, liquidez, taxas, impacto ou slippage não representarem condições observáveis. | Média / Alto | Registrar modelo de fill e fontes; testar cenários determinísticos; comparar cotação observável; apresentar resultado como simulado. | Produto/QA; antes de beta PAPER. |
| R-05 | Cobertura incompleta, dados atrasados/manipulados ou provedores divergentes podem produzir filtros ou sinais enganosos. | Alta / Alto | Medir cobertura e frescor; preservar `unknown`; mostrar fonte e timestamp; usar NO_TRADE quando dados forem insuficientes; monitorar divergência. | Produto/dados; G0 e validação do scanner. |
| R-06 | Falha de isolamento entre tenants, dados de carteira ou ferramentas de suporte pode expor informações de usuários. | Média / Alto | Definir classificação e finalidade dos dados; validar acesso server-side e testes negativos multi-tenant; limitar suporte a metadados mínimos como prescreve o PRD. | Segurança/privacidade + QA; antes de beta multiusuário. |
| R-07 | Falta de orçamento e de responsáveis humanos pode atrasar ou impedir validação jurídica, auditoria e suporte operacional. | Alta / Alto | Mapear capacidade e estimativas na Sprint 0; nomear donos; obter orçamento aprovado antes de contratar. | Responsável do projeto; antes de fechar roadmap e antes dos gates dependentes. |
| R-08 | A adoção por convidados pode ser baixa se a dificuldade do scanner ou a diferenciação proposta não for real. | Média / Médio | Entrevistar convidados e testar tarefas atuais antes de congelar cobertura/score; medir hipóteses sem pressupor ferramentas ou dores. | Produto/responsável do projeto; discovery. |

## Assumptions

| ID | Premissa de trabalho | Como validar / condição |
|---|---|---|
| A-01 | O primeiro grupo beta é de traders convidados no Brasil. | Confirmado na intenção; rever se o público se ampliar. |
| A-02 | A primeira disponibilização pode ser demonstrada com descoberta, avaliação, PAPER e P&L sem depender de trading REAL. | Confirmado; preservar a separação de modos e os gates. |
| A-03 | O PRD descreve stack candidata para desenvolvimento local Windows e AWS como destino futuro. | Confirmado no documento; mapear capacidade e ambiente na Sprint 0, sem tratar recursos como provisionados. |
| A-04 | Duas fontes candidatas bastam para uma primeira comparação de viabilidade. | Decisão desta etapa para a prova G0; a lista concreta e os termos ainda precisam de validação. |

## Issues

| ID | Situação atual | Efeito | Próxima ação |
|---|---|---|---|
| I-01 | Não há conta/organização AWS disponível. | Não é possível assumir região, serviços, cotação de nuvem ou implantação existentes. | Avaliar opções e custos na Sprint 0; nenhuma conta deve ser criada/provisionada sem decisão específica. |
| I-02 | Não há orçamento aprovado para fornecedores, nuvem, assessoria jurídica ou auditoria; contratação não integra esta etapa. | Estimativas e dependências financeiras estão abertas. | Apresentar estimativas após Sprint 0 para decisão do responsável. |
| I-03 | Experiência disponível na stack candidata não foi mapeada. | Capacidade e prazo ainda não podem ser estimados com confiança. | Mapear lacunas de Next.js/TypeScript, Java/Spring Boot e Docker Compose na Sprint 0. |
| I-04 | Política de retenção e residência de dados não estão definidas. | Não fechar região, prazos de retenção ou compartilhamento antes de inventário e revisão. | Inventariar e propor retenção mínima; decidir residência com revisão de privacidade/jurídica. |
| I-05 | Responsáveis de operações, segurança, jurídico e auditoria externa ainda serão definidos. | Não há accountability confirmado para controles, incidentes e gates. | Nomear responsáveis humanos antes do beta e de qualquer ativação REAL. |

## Dependencies

| ID | Dependência | Necessário para | Estado / responsável |
|---|---|---|---|
| D-01 | Acesso autorizado a duas fontes candidatas e documentação vigente de API/termos. | G0 e decisão de build/buy/integrate. | Fornecedores a selecionar para avaliação; contratação não autorizada. |
| D-02 | Entrevistas e tarefas com traders convidados. | Validar dor do scanner, critérios e diferenciação. | Participantes do beta; agenda a definir. |
| D-03 | Assessoria jurídica brasileira com escopo para produto cripto, serviços automatizados, privacidade e oferta. | Parecer de enquadramento, termos de beta e gates SaaS/REAL. | Não nomeada nem orçada. |
| D-04 | Especialista Solana e auditoria independente de delegação/execução. | Demonstrar limites, revogação, resistência a replay, fluxo de falhas e segurança on-chain. | Não nomeados nem orçados; requisito apenas para investigação posterior de REAL. |
| D-05 | Provedor RPC/market data e ambiente local de desenvolvimento. | Prova técnica de cobertura, disponibilidade, limites e custo. | Sem seleção; stack candidata prescrita no PRD, experiência pendente. |
| D-06 | Responsáveis de segurança, operações e suporte. | Controles de acesso, incidentes, kill switch, monitoramento e resposta. | Ainda não definidos. |

## Gate de encerramento desta etapa

Esta análise autoriza continuar planejamento e discovery dentro do fluxo AI-DLC. Não autoriza compra, lançamento público, execução com fundos, implantação de programa on-chain ou ativação de trading REAL. Essas decisões exigem suas aprovações e evidências próprias.
