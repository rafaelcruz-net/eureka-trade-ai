# Componentes de frontend — U5 Bots e decisão

São as telas do app do convidado para bots PAPER: S4 (assistente), a lista de bots e S5 (tela do bot). Os dados vêm das rotas C4 (bots, decisões e comandos), C5 (operações, uso do dia e posições, da U7) e C21 (recusas, da U6). Os tipos são gerados do OpenAPI e nunca escritos à mão.

Regras comuns a todas as telas:
- textos em pt-BR, com termos de mercado em inglês explicados ao passar o mouse e ao receber foco;
- "MODO PAPER (simulado)" no título e h1 igual ao item de menu;
- decimais sempre como texto (`DecimalString`), nunca convertidos para `number`;
- carregando, erro, vazio e `unknown` como estados distintos;
- `data-testid` em todo elemento interativo.

## Hierarquia

```
app/bots/page.tsx                    Lista de bots
└─ BotList
   ├─ ActiveCounter                 "N de 3 bots ativos"
   ├─ StateFilter                   filtro por estado
   └─ BotRow (por bot)              nome, estado em texto, motivos, "volta a operar às HH:MM", ações válidas

app/bots/new/[botId]/page.tsx        Assistente S4
└─ BotWizard
   ├─ StepIndicator                 "Etapa N de 4" com os nomes das etapas
   ├─ StrategyAndTokensStep         etapa 1
   │  ├─ StrategyPicker             as duas estratégias com descrição simples
   │  ├─ ParameterField (por parâmetro)  rótulo, unidade, padrão, faixa e passo
   │  ├─ TokenListEditor            até 10 tokens; prefill do scanner
   │  └─ TokenRuleEditor            até 5 filtros (campo, GTE/LTE, valor)
   ├─ LimitsAndBalanceStep          etapa 2
   │  ├─ VirtualBalanceField        100 a 10.000, teto exibido
   │  └─ LimitField (por limite)    rótulo, unidade, padrão proporcional, frase do que impede
   ├─ ReviewStep                    etapa 3: estratégia, parâmetros, limites, saldo, hipóteses da simulação
   └─ StartStep                     etapa 4: "Iniciar bot PAPER", estado "Iniciando bot PAPER"

app/bots/[botId]/page.tsx            Tela do bot S5
└─ BotDetail
   ├─ BotHeader                     nome, estado, motivos de pausa em texto, horário de reinício no fuso da pessoa
   ├─ BotControls                   Pausar, Retomar, Encerrar, Editar limites
   │  ├─ StopConfirmDialog          lista as posições e o que acontece com elas
   │  └─ LimitsEditDialog           nova versão dos limites
   ├─ DayUsagePanel                 exposição atual de total; perda do dia de máxima (C5)
   ├─ StaleDataNotice               aviso de dados desatualizados
   ├─ OpenPositionsPanel            posições marcadas a mercado; aviso quando pausado
   ├─ TradesTable                   operações (C5), cada uma uma vez
   ├─ RejectionsPanel               "ver motivos" (C21); 404 = "nenhuma recusa"
   └─ DecisionsList                 decisões explicadas (C4)
```

## Estado e props

| Componente | Estado ou props principais | Fonte |
|---|---|---|
| `BotWizard` | `botId`, `draftStep` e o rascunho do servidor; a etapa atual fica na URL | `GET /bots/{botId}` via `BotSummary` e o rascunho |
| `ParameterField` | `spec` (min, max, step, default), `value` em texto e erro do campo | `StrategyTemplate` |
| `LimitField` | `value`, `touched` (impede o recálculo do padrão) e erro | BR1.6 |
| `BotControls` | estado confirmado do servidor e o comando em andamento | Respostas de `commands/{command}` |
| `RejectionsPanel` | página e cursor; 404 vira lista vazia | C21 |
| `DecisionsList` | página e cursor; cada decisão com a frase, os fatores por token e as entradas | C4 |

## Fluxos de interação

1. **Assistente.**
   - "Continuar" envia `PUT /bots/{botId}/draft` da etapa e avança só com 200.
   - Um erro 422 aparece junto de cada campo.
   - "Voltar" não envia nada e mantém os dados.
   - A etapa 1 sem token nem regra deixa "Continuar" indisponível, com o motivo escrito.
2. **Iniciar.** O botão fica desabilitado durante o pedido e mostra "Iniciando bot PAPER".
   - 409 `ACTIVE_BOT_LIMIT` mostra "3 de 3 bots ativos".
   - 409 `PAUSED_BY_SYSTEM` mostra "pausado pelo sistema".
   - Uma falha mostra "Tentar de novo", e o rascunho continua.
3. **Pausar.** É um clique, sem confirmação.
4. **Encerrar.** Abre um diálogo com as posições. O foco entra no diálogo, Esc ou "Cancelar" fecham sem efeito, e o foco volta ao botão.
5. **Editar limites.** O diálogo valida no cliente o mesmo que o servidor valida. Um 422 aparece no campo.
6. **Retomar.** Um 409 mostra o motivo em texto, por exemplo "a perda do dia já é 50,00 USDC; aumente o limite para retomar".

A tela sempre mostra o último estado confirmado pelo servidor. A atualização vem do canal ao vivo (C7) ou de uma nova leitura depois de cada comando.

## Validação de formulário

| Campo | Regra no cliente (repetida no servidor) |
|---|---|
| Parâmetros | decimal; mín e máx aceitos; múltiplo do passo |
| Tokens | até 10, sem repetição, mint em Base58 |
| Filtros | até 5; valor decimal não negativo |
| Saldo virtual | de 100 a 10.000; acima do teto mostra o teto |
| Limites | positivos; ordem ≤ exposição por token ≤ exposição total; percentuais de 0 a 100 (com o 0 recusado); intervalo ≥ 1 s |

## Acessibilidade

- O fluxo inteiro funciona só com o teclado, com foco visível e sem armadilha de foco.
- Cada campo tem rótulo visível e unidade, e o erro aparece em texto junto dele.
- A cor nunca é o único indicador: o estado e o motivo aparecem em texto.
- Atualizações do canal são anunciadas sem mover o foco.
