# Perguntas da análise de requisitos — MemeTrade Analytics

## Contexto já decidido (não será perguntado de novo)

- Pedido original (`PromptInicial.txt`) e PRD v1.3: MVP SaaS multiusuário com scanner, IA, risco, PAPER, REAL, P&L e assinaturas, priorizando Sprint 0 e uma prova de conceito da delegação.
- Escopo aprovado (`../../ideation/scope-definition/scope-document.md`): primeiro beta por convite com descoberta → avaliação → PAPER → P&L reconciliável; REAL, cobrança pública e SaaS aberto fora do primeiro beta; filtros salvos, alertas e métricas avançadas como Should.
- Práticas da equipe (`../practices-discovery/team-practices.md`) e regras rígidas do projeto já afirmadas.
- Mockups (`../../ideation/rough-mockups/wireframes.md`): só computador, tema escuro, português, painel como primeira tela, assistente de bot em 4 etapas.

## Q1. [Produto] Como os requisitos devem tratar o trading REAL e a delegação?
O PRD e o seu pedido incluem REAL e pedem priorizar uma prova de conceito da delegação; o escopo aprovado tirou REAL do primeiro beta.

A. Documentar REAL e delegação como requisitos bloqueados pelos gates G2/G3, fora do primeiro beta; incluir uma prova de conceito de viabilidade da delegação na Sprint 0, só em rede de testes (devnet), sem fundos reais e sem implantar nada em mainnet (recomendado)
B. Só a prova de conceito na Sprint 0, sem escrever requisitos de REAL agora
C. Deixar REAL e delegação totalmente fora destes requisitos
X. Other (please specify)

[Answer]: A. REAL bloqueado por G2/G3 + PoC de delegação em devnet

## Q2. [Produto] O que decide as entradas e saídas do bot PAPER no beta?
O PRD prevê um modelo de sinal com IA; o escopo trata pesos e qualidade preditiva como hipóteses a validar.

A. Regras determinísticas configuráveis (filtros, indicador de risco e limites), com explicação de cada decisão a partir dos dados usados; modelo de sinal com IA adiado até haver backtest e avaliação fora da amostra (recomendado)
B. Já incluir um modelo de sinal com IA no beta, sempre passando pelo motor de risco e com NO_TRADE como padrão
C. Regras determinísticas no bot, mais uma explicação em linguagem natural gerada por IA apenas para leitura
X. Other (please specify)

[Answer]: A. Regras determinísticas

## Q3. [Produto] Como o convidado configura a estratégia do bot PAPER?
Influencia o tamanho do assistente e o volume de testes.

A. A partir de 1 ou 2 estratégias modelo com parâmetros ajustáveis (recomendado)
B. Regras livres montadas pelo usuário a partir dos filtros do scanner
X. Other (please specify)

[Answer]: A. Estratégias modelo

## Q4. [Produto] Como funciona o capital virtual do PAPER?
Define o saldo simulado com que o bot opera.

A. O usuário define o saldo virtual de cada bot ao criá-lo, dentro de um teto fixado pela plataforma; o saldo nunca migra para REAL (recomendado)
B. Saldo virtual fixo e igual para todos os convidados
X. Other (please specify)

[Answer]: A. Saldo por bot com teto

## Q5. [Produto] Quem emite convites e quem aciona o desligamento geral (kill switch)?
O PRD prevê administração de convites e incidentes (US14) e um kill switch global.

A. Você, por uma área administrativa mínima: emitir e revogar convites e acionar um kill switch global que pausa todos os bots PAPER; ações administrativas auditadas (recomendado)
B. Convites emitidos fora do produto (manualmente); kill switch só por configuração do sistema
X. Other (please specify)

[Answer]: A. Área administrativa mínima

## Q6. [Operações] Onde os convidados vão acessar o beta?
Hoje só existe o ambiente local, e não há conta de nuvem nem orçamento. Convidados não conseguem acessar um ambiente local.

A. Convites só depois que uma hospedagem for aprovada (com custo e residência de dados decididos após a Sprint 0); até lá, validação com demonstrações e tarefas acompanhadas no ambiente local (recomendado)
B. Já planejar uma hospedagem gratuita ou de baixo custo para o beta, a definir na Sprint 0
X. Other (please specify)

[Answer]: A. Convites após hospedagem aprovada

## Q7. [Operações] Para qual tamanho de beta os requisitos de capacidade devem ser escritos?
Sem um número não dá para definir metas testáveis de capacidade.

A. Hipótese inicial: até 20 convidados, até 3 bots PAPER ativos por convidado, revisada após o discovery (recomendado)
B. Até 50 convidados e 5 bots ativos cada
C. Não fixar agora; deixar as metas de capacidade para depois do discovery
X. Other (please specify)

[Answer]: A. 20 convidados x 3 bots

## Q8. [Operações] Qual meta de atualização dos dados do scanner usamos?
Cobertura e frescor dos dados são o critério principal do produto; o frescor real depende das fontes, que serão comparadas no G0.

A. Meta provisória: dados do scanner com no máximo 60 segundos de idade; acima de 5 minutos o dado aparece como desatualizado; metas finais confirmadas no G0 (recomendado)
B. Meta provisória mais agressiva: até 15 segundos
C. Não fixar meta antes do G0
X. Other (please specify)

[Answer]: A. 60 s / 5 min

## Q9. [Segurança] Como tratar endereços de carteira em logs e métricas?
Pela LGPD, um endereço de carteira ligado a uma conta pode ser dado pessoal.

A. Mascarar nos logs e métricas (só os primeiros e últimos caracteres); endereço completo apenas na trilha de auditoria, com acesso restrito (recomendado)
B. Registrar o endereço completo em todos os logs
X. Other (please specify)

[Answer]: A. Mascarar

## Consolidated Summary Confirmation

- REAL e delegação entram como requisitos bloqueados pelos gates G2/G3, fora do primeiro beta; prova de conceito da delegação na Sprint 0 só em devnet, sem fundos reais e sem mainnet.
- O bot PAPER decide por regras determinísticas configuráveis (filtros, indicador de risco, limites), explicando cada decisão; modelo de sinal com IA adiado até backtest e avaliação fora da amostra.
- Estratégia do bot a partir de 1 ou 2 estratégias modelo com parâmetros ajustáveis.
- Capital virtual definido por bot dentro de um teto da plataforma, sem nunca migrar para REAL.
- Área administrativa mínima para você emitir e revogar convites e acionar um kill switch global que pausa todos os bots PAPER, com ações auditadas.
- Convites ao beta só depois de hospedagem aprovada; até lá, demonstrações e tarefas acompanhadas no ambiente local.
- Capacidade escrita para até 20 convidados e 3 bots PAPER ativos por convidado, como hipótese a revisar após o discovery.
- Frescor provisório do scanner: até 60 segundos; acima de 5 minutos o dado aparece como desatualizado; meta final confirmada no G0.
- Endereços de carteira mascarados em logs e métricas; completos só na trilha de auditoria com acesso restrito.

Does this all look correct before I generate the requirements artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
