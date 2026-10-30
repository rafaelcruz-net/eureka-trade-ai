# Registro de restrições — MemeTrade Analytics

## Restrições, decisões e pendências

| ID | Categoria | Restrição ou condição | Estado | Efeito no trabalho | Responsável/validação |
|---|---|---|---|---|---|
| C-01 | Escopo | Beta inicial para convidados no Brasil; PAPER, descoberta, avaliação e P&L. Trading REAL é posterior e segue bloqueado até seus gates. | Confirmada | Planejar o beta PAPER sem depender de transações reais. | Responsável do projeto; gate futuro explícito |
| C-02 | Segurança financeira | Nenhuma seed phrase ou chave privada do usuário pode ser solicitada; login por assinatura não autoriza trading. Não habilitar execução fora de uma autorização limitada e revogável. | Mandatória no PRD | Limita autenticação, autorização e execução; qualquer desvio impede avanço de REAL. | Segurança e revisão independente, responsáveis a nomear |
| C-03 | Segregação de modos | REAL e PAPER precisam manter fundos, ordens, ledger e P&L separados. | Mandatória no PRD | Não compartilhar saldos fictícios ou contabilidade entre modos. | Desenvolvimento e QA |
| C-04 | Idempotência e limites | Operações devem ser deduplicadas; limites autorizados e controles de risco são mandatórios antes de cada ordem. | Mandatória no PRD | Necessário para qualquer automação; teste de violação de limites é bloqueador de REAL. | Desenvolvimento, QA e segurança |
| C-05 | Dados de mercado | Campos ausentes permanecem `unknown`, nunca zero; interface informa fonte, timestamp, frescor e cobertura. | Mandatória no PRD | Afeta scanner, filtros, sinais e critérios de aceitação. | Produto e desenvolvimento |
| C-06 | Fornecedores de dados | Nenhum fornecedor foi escolhido; comparar duas fontes em G0 e validar cobertura, quotas, preço e licença para SaaS/redistribuição. | Pendente | Não contratar nem depender de endpoint até aprovação técnica/comercial e revisão dos termos. | Responsável do projeto; jurídico quando definido |
| C-07 | Residência e privacidade | Residência de dados permanece indefinida. Inventário, finalidades, retenção e eventual transferência internacional precisam de avaliação. | Pendente | Região e armazenamento não podem ser tratados como decisão fechada. | Jurídico/privacidade, responsáveis a nomear |
| C-08 | Retenção de dados | Prazo e justificativas de retenção para dados de conta, carteira e auditoria não foram definidos. | Pendente | Propor retenção mínima após inventário e validar hipóteses legais antes do beta. | Jurídico/privacidade e operações |
| C-09 | Regulação | Aplicabilidade de regras CVM, BCB/PSAV, LGPD, prevenção à lavagem de dinheiro, tributação e oferta a terceiros depende das atividades reais e de parecer profissional. | Pendente, bloqueadora para SaaS/REAL | Não lançar cobrança pública nem ativar REAL antes de análise jurídica documentada. | Assessoria jurídica brasileira, ainda sem responsável/orçamento |
| C-10 | Infraestrutura | O PRD indica AWS para implantação futura, mas não há conta/organização AWS nem região selecionada. | Pendente | Nenhum provisionamento pode ser estimado como disponível. | Responsável do projeto/plataforma |
| C-11 | Stack e capacidade | Stack candidata: Next.js/React/TypeScript, Java 21/Spring Boot, Docker Compose em Windows. Experiência ainda não mapeada. | Stack prescrita; capacidade pendente | Mapear lacunas e dependências humanas na Sprint 0 antes de estimar. | Responsável do projeto |
| C-12 | Orçamento | Sem teto aprovado para provedores, nuvem, assessoria ou auditoria; nenhuma contratação nesta etapa. | Pendente | Produzir estimativas após discovery e obter aprovação específica antes de compromissos. | Responsável do projeto |
| C-13 | Cronograma | Sem data externa obrigatória; estimativas serão revisadas após Sprint 0. | Confirmada | Não prometer data de entrega a partir do roadmap estimado do PRD. | Responsável do projeto |
| C-14 | Governança operacional | Responsáveis humanos por operações, segurança, jurídico e auditoria externa ainda serão definidos. | Pendente | Nomear funções e disponibilidade antes de beta e antes de REAL. | Responsável do projeto |
| C-15 | Público e mercado | Convidados brasileiros são o público inicial; evidência sobre dor, ferramentas atuais e diferenciação ainda depende de entrevistas/tarefas. | Confirmada como público; necessidade como hipótese | Validar problema antes de congelar cobertura e requisitos do scanner. | Produto/responsável do projeto |
| C-16 | Postura de produto | Não prometer lucro nem garantia de detecção de fraude; score indisponível quando cobertura insuficiente. | Mandatória no PRD | Comunicações e sinais devem expressar limitações e incerteza. | Produto, jurídico e QA |

## Referências regulatórias para validação

- A [LGPD, arts. 15–16](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm) estabelece hipóteses de término e de conservação dos dados pessoais.
- A [Resolução CD/ANPD nº 19/2024](https://www.gov.br/anpd/pt-br/acesso-a-informacao/institucional/atos-normativos/regulamentacoes_anpd/resolucao-cd-anpd-no-19-de-23-de-agosto-de-2024) regula transferências internacionais de dados pessoais e mecanismos aplicáveis.
- A [CVM](https://www.gov.br/cvm/pt-br/acesso-a-informacao-cvm/perguntas-frequentes-da-cvm/criptoativos-quando-se-aplicam-as) delimita sua competência a criptoativos que se caracterizem como valores mobiliários; sua orientação sobre [robôs de investimentos](https://www.gov.br/investidor/pt-br/investir/como-investir/profissionais-do-mercado/robos-de-investimentos) descreve atividades automatizadas que podem exigir credenciamento/registro.
- As regras do Banco Central para prestadores de serviços de ativos virtuais incluem a [Resolução BCB 520](https://www.bcb.gov.br/estabilidadefinanceira/exibenormativo?numero=520&tipo=resolu%C3%A7%C3%A3o+bcb), vigente desde fevereiro de 2026 conforme [comunicado do BCB](https://www.bcb.gov.br/detalhenoticia/20918/nota?s=08). A aplicação ao produto precisa ser classificada por assessoria jurídica.

Consulta realizada em 26/10/2026. O registro identifica pontos para análise; não é parecer jurídico nem declara enquadramento regulatório.
