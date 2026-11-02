# ADR-0005 — Indicador de risco em validação, com pesos versionados

- **Status:** aceito (U4 `u4-discovery`, decisões Q1 a Q4, Q8 e Q9 do design funcional)
- **Regras:** BR3.1 a BR3.9 e BR4.1 a BR4.3 de [rules.md](../../aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/functional-design/rules.md)

## Contexto

O beta precisa de um indicador de risco de 0 a 100 por token para a decisão PAPER da U5. Não há dados históricos para calibrar o modelo antes do beta, e o PRD (§9) trata os fatores e pesos como hipótese. Ao mesmo tempo, o projeto proíbe classificar como seguro um token com dado `unknown` e exibir indicador quando a cobertura é insuficiente (`project.md`).

## Decisão

- O indicador é a soma ponderada de quatro fatores: liquidez, holders, volume em 24 h e variação de preço em 1 hora. Cada fator converte a entrada em risco de 0 a 100 por uma curva com interpolação linear, arredondada meio para cima.
- A versão 1 dos pesos é criada pela migração `0006` como hipótese: liquidez 35, holders 25, volume 20, variação de preço 20; cobertura mínima 80%; faixas Baixo até 39, Médio até 69, Alto a partir de 70. Os valores serão recalibrados com o uso.
- Toda resposta traz `inValidation = true` e a tela mostra "Indicador em validação" e "maior significa mais risco".
- Fator `unknown` entra com risco 100 e contribuição `UNKNOWN`; com algum fator `unknown`, a faixa nunca é Baixo (passa a Médio com `bandRaisedByUnknown`). Abaixo da cobertura mínima, ou com todas as fontes do token fora, o indicador fica indisponível e a avaliação é `NO_TRADE`.
- Toda a aritmética é em decimal exato; o resultado depende só das entradas, da versão e do relógio injetável, e a avaliação guarda versão, entradas, fontes e horários para ser refeita.
- As versões de pesos são imutáveis depois de ativadas. Só o administrador cria e ativa (interface `RiskWeightAdministration`, usada pela tela da U8), e cada ativação publica `RISK_WEIGHTS_ACTIVATED` na trilha da U2 e dispara o recálculo.

## Consequências

- O indicador nunca é vendido como certeza: o rótulo, a explicação dos fatores e o catálogo de termos proibidos acompanham a tela.
- Mudar a calibração não exige código: basta uma nova versão de pesos ativada pelo administrador, com auditoria.
- Um token com pouca informação aparece com `--` e o motivo, em vez de um número enganoso.
- A U5 deve tratar `NO_TRADE` e indisponibilidade como impedimento, nunca como risco baixo.

## Alternativas rejeitadas

| Alternativa | Por que foi rejeitada |
|---|---|
| Modelo estatístico ou de aprendizado de máquina | Não há dados históricos rotulados no beta; seria menos explicável e violaria a exigência de mostrar como o indicador foi feito. |
| Tratar `unknown` como risco médio ou ignorar o fator | Permitiria classificar como Baixo um token com dado ausente, o que o projeto proíbe. |
| Pesos em arquivo de configuração | Mudança sem auditoria nem versão; a avaliação guardada não diria com quais pesos foi feita. |
