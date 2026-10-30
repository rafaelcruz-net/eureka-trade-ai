# Especificação de interação — MemeTrade Analytics

Componentes recorrentes das telas de [mockups.md](mockups.md), no formato do modelo de especificação de componentes. Origem: [wireframes](../../ideation/rough-mockups/wireframes.md), [fluxos](../../ideation/rough-mockups/user-flow.md), [stories.md](../user-stories/stories.md) (critérios CT-UX e DP) e [requirements.md](../requirements-analysis/requirements.md). Responsividade: só computador, largura mínima de 1280 px (Q2); não há pontos de quebra para tablet ou celular.

## Padrões gerais

| Padrão | Regra |
|---|---|
| Atualização ao vivo | Valores atualizam no lugar; nada muda de posição sozinho; mudanças anunciadas a leitores de tela sem mover o foco (CT-UX-8). |
| Confirmação | Só para ações destrutivas ou de efeito amplo: encerrar bot, revogar convite, acionar kill switch. Pausar não pede confirmação (AC4.8.1). |
| Validação de formulário | Ao sair do campo e ao tentar avançar; erro em texto ligado ao campo; o servidor valida de novo. |
| Duplo envio | Botões de envio ficam ocupados ("Iniciando...", "Enviando...") e a requisição leva chave de idempotência. |
| Dados ausentes | `unknown` é um estado visual próprio, diferente de carregando, erro e zero. |
| Horários | Exibidos no fuso do usuário, com o UTC disponível ao passar o mouse ou focar onde a regra usa UTC (perda diária). |

## Tabela de dados (scanner, operações, ledger, convites)

| Campo | Valor |
|---|---|
| Component | Tabela de dados |
| Description | Lista densa e navegável por teclado com ordenação e paginação |
| Category | display |

| State | Description | Trigger |
|---|---|---|
| default | Linhas com números formatados (Q4) | dados carregados |
| loading | Linhas marcadoras na área da tabela | busca em andamento |
| empty | Mensagem com motivo e próxima ação | sem resultados |
| error | Aviso com "Tentar de novo"; nunca valores antigos sem marca | falha na busca |
| partial | Aviso por fonte com problema; linhas afetadas com marcador | uma fonte falhou ou atrasou |
| pending-updates | Botão "Atualizar lista (N)" visível | novidades em espera (Q5) |

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| columns | object | yes | — | Colunas, alinhamento e formato numérico |
| rows | object | yes | — | Linhas com identificador estável (chain + mint ou ID) |
| sort | object | no | critério padrão da tela | Ordem com desempate estável |
| pendingCount | number | no | 0 | Quantidade de novidades em espera |

| Requirement | Implementation |
|---|---|
| ARIA role | tabela com cabeçalhos de coluna; ordenação anunciada |
| Keyboard interaction | setas movem entre linhas; Enter abre detalhe; Tab sai da tabela |
| Label / aria-label | título da tela como rótulo da tabela |
| Contrast ratio | WCAG AA (4,5:1 texto, 3:1 componentes) |
| Screen reader | "Atualizado às HH:MM" e "N novidades" anunciados educadamente |
| Focus management | após "Atualizar lista", o foco fica no mesmo token |

## Painel lateral de avaliação

| Campo | Valor |
|---|---|
| Component | Painel lateral |
| Description | Detalhe e avaliação do token sem sair da lista |
| Category | layout |

| State | Description | Trigger |
|---|---|---|
| default | Indicador, faixa, motivo, fatores com fonte e horário | token selecionado |
| loading | "Carregando avaliação", sem indicador provisório | avaliação em busca |
| unavailable | "Indisponível" com motivo e NO_TRADE | cobertura insuficiente |
| error | Mensagem e "Tentar de novo" | falha |

| Requirement | Implementation |
|---|---|
| ARIA role | região complementar com título do token |
| Keyboard interaction | Esc fecha; foco volta à linha |
| Focus management | ao abrir, foco no título do painel |

## Indicador de risco

| Campo | Valor |
|---|---|
| Component | Indicador de risco |
| Description | Número 0–100 com faixa escrita (Q6) e rótulo "em validação" |
| Category | feedback |

| State | Description | Trigger |
|---|---|---|
| low | "NN Baixo" | 0–33 |
| medium | "NN Médio" | 34–66 |
| high | "NN Alto" | 67–100 |
| unavailable | "-- Indisponível" com motivo | cobertura insuficiente |

As faixas são configuráveis na calibração; o texto sempre acompanha a cor.

## Assistente em etapas

| Campo | Valor |
|---|---|
| Component | Assistente |
| Description | Quatro etapas com indicador, rascunho salvo a cada etapa e revisão final |
| Category | navigation |

| State | Description | Trigger |
|---|---|---|
| default | Etapa atual com título próprio | navegação |
| invalid | "Continuar" desabilitado com o motivo escrito | campo inválido ou regra ausente |
| saving | "Rascunho salvo às HH:MM" | etapa aceita |
| submitting | "Iniciando bot PAPER" | início |
| blocked | "3 de 3 bots ativos" e "Salvar rascunho" | limite de ativos |

| Requirement | Implementation |
|---|---|
| Keyboard interaction | Tab percorre campos; Enter em "Continuar" |
| Screen reader | "Etapa N de 4: nome" anunciado ao trocar de etapa |
| Focus management | foco no primeiro campo da nova etapa |

## Estado do bot (selo)

| Campo | Valor |
|---|---|
| Component | Selo de estado |
| Description | Estado do bot em texto, com motivos acumulados de pausa |
| Category | display |

| State | Description | Trigger |
|---|---|---|
| active | "ATIVO" | bot operando |
| paused | "PAUSADO — motivos" e horário de reinício quando for perda diária | pausa |
| closing | "ENCERRAMENTO PENDENTE (N)" | encerramento sem preço confiável |
| stopped | "ENCERRADO dd/mm" | encerrado |
| draft | "RASCUNHO — etapa N de 4" | rascunho |

## Barra de uso de limite

| Campo | Valor |
|---|---|
| Component | Barra de uso |
| Description | Uso do dia de um limite (exposição, perda, drawdown) |
| Category | feedback |

O valor e o percentual aparecem sempre em texto; acima de 80% acrescenta "perto do limite"; no limite, "limite atingido". A barra é decorativa para leitores de tela, que leem o texto.

## Diálogo de confirmação

| Campo | Valor |
|---|---|
| Component | Diálogo de confirmação |
| Description | Confirma ações destrutivas ou de efeito amplo, dizendo o que será afetado |
| Category | feedback |

| Requirement | Implementation |
|---|---|
| ARIA role | diálogo modal com título e descrição |
| Keyboard interaction | Esc ou "Cancelar" fecham sem efeito; foco preso no diálogo |
| Focus management | ao abrir, foco no botão menos destrutivo; ao fechar, volta ao botão de origem |

## Aviso e anúncio

| Campo | Valor |
|---|---|
| Component | Faixa de aviso |
| Description | Avisos de dados (fonte atrasada, divergência), sistema (kill switch) e atualização falha |
| Category | feedback |

Sempre com ícone e texto; anunciada educadamente; avisos críticos (kill switch acionado) usam anúncio assertivo uma única vez.

## Número formatado

| Campo | Valor |
|---|---|
| Component | Número |
| Description | Exibição de preços, quantidades e P&L (Q4) |
| Category | display |

Alinhado à direita em tabelas; algarismos de largura fixa; vírgula decimal e ponto de milhar; abreviações k e M com o valor exato ao passar o mouse ou focar; sinal explícito (+/-) em resultados; `unknown` nunca vira 0.
