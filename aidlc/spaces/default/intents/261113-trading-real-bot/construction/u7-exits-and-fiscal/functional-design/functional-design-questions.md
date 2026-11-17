# Perguntas de Functional Design — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Base:
- `inception/units-generation/unit-of-work.md` (U7) e `unit-of-work-story-map.md` (US3.4 e US4.3; aviso do kill switch, FR8.2);
- `inception/user-stories/stories.md` (AC3.4.1, AC3.4.2, AC4.3.1, AC8.1.3);
- `inception/requirements-analysis/requirements.md` (FR4.1, FR8.2, FR13.1, FR14.1);
- `inception/contract-design/contract-summary.md`:
  - R5: condicionais e exportação fiscal;
  - M1, M2, M6 e M7;
  - X1: a Jupiter;
  - a nota de que "o formato da API de condicionais da Jupiter continua em aberto e bloqueia só a U7";
- o código atual:
  - `OperationMode.AUTO_EXITS` já existe;
  - as ordens a mercado dos modos Assinada e Automática nas saídas já são cossinadas pela carteira principal e pelo TxSigner (`DecisionConsumer`);
  - a política de conteúdo do TxSigner (`SwapPolicy`, ADR-004);
  - a gravação de rotas da Jupiter em `infra/solana-fixtures/record.sh`;
- a documentação pública atual da Jupiter: a Trigger API v2 tem ordens `single` e `oco` (stop e alvo juntos), com preços em USD, e não garante a execução do stop;
- `team.md`, que manda escrever antes do código os testes da política do TxSigner, do ledger e do saque.

## Q1. [Quem é o dono da condicional] Em nome de qual carteira a condicional é criada?

A. Da carteira do bot, que é quem guarda a posição. A transação de criação é montada pelo servidor, como uma ordem a mercado do modo Assinada:
- a carteira principal cossina no navegador (sessão de assinatura como a R3, reaproveitando a faixa e o diálogo da U6);
- o TxSigner assina pela carteira do bot.

O token vendido sai da carteira do bot e o SOL recebido volta para ela. A carteira principal só autoriza; ela nunca recebe nem guarda a posição.
B. Mover a posição para a carteira principal e criar a condicional nela
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Política do TxSigner] O que a política aceita para condicionais?

A. Uma política nova, `TriggerPolicy`, ao lado da `SwapPolicy`, com os testes de ataque escritos antes do código. Ela aceita só:
- **Programa:** só o programa de condicionais da Jupiter (Trigger), além do System, Token, Token-2022, Associated Token e Compute Budget.
- **Criação:** com a carteira do bot como dona e pagadora, o mint de entrada igual à posição, a quantidade de no máximo o saldo da posição, a saída em SOL e o destino da saída igual à carteira do bot.
- **Cancelamento:** devolvendo o token só para a carteira do bot.

Qualquer outro destino, troca de autoridade, fechamento de conta para terceiros ou programa fora da lista é recusado, e cada recusa grava o motivo.
B. Reaproveitar a `SwapPolicy` sem mudança
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Tipo e preço] Que condicionais a pessoa cria e em que unidade é o preço?

A. Para cada posição aberta no modo Automática nas saídas, a pessoa pode ter uma condicional aberta, que pode ser só stop, só alvo, ou os dois juntos (OCO, um cancela o outro).
- **Preço:** em USD, que é a unidade da Jupiter, digitado como decimal exato e guardado como `DecimalString`. A tela mostra o preço atual em USD com fonte e horário; sem preço atual (`unknown`), a criação é recusada.
- **Regras:** o alvo precisa ficar acima do stop, e os dois precisam respeitar a distância mínima do preço atual que a Jupiter exige.
- **Derrapagem:** a do stop é a máxima do perfil da pessoa (U3), nunca o padrão de 20% da Jupiter.
- **Aviso:** a tela diz, em texto, que o stop não tem execução garantida.
B. Preço em SOL
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Preenchimento] Como a U7 detecta e lança o preenchimento?

A. Um trabalho a cada 30 s lê o estado das condicionais abertas na Jupiter (consulta de ordens da carteira do bot). Ele confere cada preenchimento na rede pela assinatura da transação, com commitment `finalized`.
- O preenchimento entra no ledger REAL pela M2, com os valores da rede (quantidade vendida, SOL recebido, taxa), uma única vez por assinatura (chave de idempotência).
- Ele atualiza a posição e o P&L REAL e publica `real.order.state` no canal ao vivo.
- Num OCO, a outra perna vira `CANCELLED`.
- Com a Jupiter fora, o trabalho tenta de novo. Uma condicional aberta há mais de 2 horas sem leitura gera alerta.
B. Só pela leitura da rede, sem a Jupiter
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Kill switch e troca de modo] O que acontece com as condicionais abertas?

A. Seguindo o FR8.2 e o AC8.1.3, o servidor cancela, porque a carteira do bot é dona:
- com o kill switch REAL ligado (M7), um trabalho de 1 minuto cancela todas as condicionais abertas;
- ao sair do modo Automática nas saídas, as condicionais da pessoa são canceladas.

Para cancelar, o servidor monta a transação, e o TxSigner assina sozinho pela carteira do bot, porque o cancelamento só devolve o token para ela. Por isso não precisa da carteira principal, e o cancelamento funciona com a pessoa offline.

Até o cancelamento confirmar na rede, a condicional mostra "Cancelando". O `needsUserCancellation` do contrato fica sempre `false`, porque o servidor cancela sozinho; o campo continua no contrato por compatibilidade.

O saque continua disponível. Antes de sacar tudo, a liquidação da U4 cancela as condicionais.
B. Só avisar a pessoa para cancelar
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Limites e risco] A barreira da U3 vale para as condicionais?

A. Sim, na criação. A barreira (M5) confere:
- liberação;
- pausas;
- kill switch;
- token bloqueado;
- o modo, que precisa ser Automática nas saídas.

A condicional é só uma venda futura de uma posição que já existe. Por isso ela não consome o limite de compra nem o teto. A venda executada entra na perda do dia quando o preenchimento é lançado.

Com uma condicional aberta sobre a posição, uma venda a mercado da mesma posição pelo bot ou pela pessoa cancela a condicional antes, para nunca vender duas vezes.
B. Sem barreira
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Exportação fiscal] O que a exportação traz?

A. CSV UTF-8 com BOM, separador `;` e vírgula decimal (padrão brasileiro). Ele traz uma linha por evento do ledger REAL da pessoa: compra, venda, preenchimento de condicional, depósito, saque e taxa. As colunas são:
- data e hora em UTC e no fuso da pessoa;
- tipo;
- mint e símbolo do token;
- quantidade;
- preço em SOL;
- valor em SOL;
- taxa da rede em SOL;
- assinatura da transação.

O período é `from` e `to`, de no máximo 1 ano por arquivo, e o arquivo é gerado sob demanda e não fica guardado.

Os valores são exatos (decimal), sem arredondar além das casas do mint. Campos que começam com `=`, `+`, `-` ou `@` recebem um apóstrofo, contra injeção de fórmula. Cada pessoa só exporta os próprios dados (tenant do token), e a rota tem limite de 10 exportações por hora.

O PAPER nunca entra.
B. Formato JSON
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Telas] Onde a pessoa cria e vê as condicionais e a exportação?

A. Na U7, nas telas da U6:
- **Carteira do bot:** o modo "Automática nas saídas" passa a ficar habilitado quando o servidor aceitar o modo.
- **Posições:** cada posição da visão geral ganha "Definir stop e alvo", que abre um diálogo com os preços em USD, o preço atual, a fonte e o aviso do stop. O diálogo cria a condicional e abre a mesma faixa e o mesmo diálogo de assinatura.
- **Ordens:** ganham a aba "Condicionais", com o estado (aberta, preenchida, cancelando, cancelada, expirada).
- **Exportar:** um botão "Exportar histórico (CSV)" com o período.
B. Uma tela separada só para condicionais
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q9. [Formato da Jupiter em aberto] Como lidar com a API de condicionais que o contrato deixou em aberto?

A. O adaptador fica atrás de uma porta (`TriggerPort`, X1), com duas implementações:
- **Simulada:** para a rede local e os testes;
- **Real:** para a Trigger API v2.

Os testes de unidade usam respostas gravadas. A gravação das respostas e do programa no validador local estende o `record.sh` da U4, com o mesmo cuidado de rodar uma vez, com rede, fora dos testes.

Até a gravação real ser feita e conferida, o modo Automática nas saídas fica desligado por configuração fora da rede local (`memetrade.real.exits.enabled=false`). A tela continua mostrando "Em breve" nesse caso. A chave de API da Jupiter, se for exigida, fica no Secrets Manager, só no worker.
B. Esperar o formato antes de começar a U7
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- A condicional é da carteira do bot, cossinada pela carteira principal no navegador e assinada pelo TxSigner; o dinheiro nunca sai da carteira do bot [Q1].
- Política nova do TxSigner só para o programa de condicionais da Jupiter, com testes de ataque antes do código [Q2].
- Stop, alvo ou os dois (OCO) por posição, com preço em USD exato, recusa sem preço atual, derrapagem do perfil e aviso de que o stop não é garantido [Q3].
- Preenchimento conferido na rede, lançado uma vez no ledger REAL e publicado no canal [Q4].
- O servidor cancela sozinho no kill switch e ao sair do modo; o saque sempre livre [Q5].
- Barreira da U3 na criação; uma venda a mercado cancela antes a condicional da mesma posição [Q6].
- Exportação CSV no padrão brasileiro, exata, protegida contra fórmula, por pessoa, até 1 ano e 10 por hora; sem PAPER [Q7].
- Telas: modo habilitado, "Definir stop e alvo" nas posições, aba "Condicionais" e botão de exportar [Q8].
- Jupiter atrás de uma porta, com simulada e gravações; o modo fica desligado fora da rede local até a gravação real ser conferida [Q9].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
