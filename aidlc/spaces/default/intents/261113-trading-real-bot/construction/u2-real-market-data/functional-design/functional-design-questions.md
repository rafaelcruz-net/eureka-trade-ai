# Perguntas de Functional Design — U2 `u2-real-market-data`

Conversa: português (Brasil). Base: `inception/units-generation/unit-of-work.md` (U2), `inception/units-generation/unit-of-work-story-map.md` (US6.3), `inception/user-stories/stories.md` (AC6.3.1 a AC6.3.3), `inception/requirements-analysis/requirements.md` (FR1.1 a FR1.4), `inception/domain-design/components.md` (MarketData), `inception/contract-design/contract-summary.md` (M4, X2) e o código atual do módulo `marketdata` (porta `MarketSourceAdapter`, fontes `sim-a` e `sim-b` por `MARKET_SOURCES`, `DivergencePolicy` entre duas fontes).

## Q1. [Fornecedor] Quais dados da CoinGecko e de onde?

A. A API on-chain do plano pago da CoinGecko (base GeckoTerminal), rede `solana`. O preço, a liquidez e o volume de 24 h vêm em lote (até 30 tokens por chamada). Os holders vêm da informação do token, e a criação vem da data do pool principal. Cada campo que a resposta não traz vira `unknown`, nunca zero.
B. A API comum de moedas da CoinGecko (sem dados on-chain de pools novos)
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Descoberta] Quais tokens entram no scanner?

A. A cada ciclo, os pools novos e os pools em alta da rede `solana`, até 50 tokens por ciclo (valor configurável), juntando os dois e tirando os repetidos. Os tokens que já estão no scanner continuam sendo atualizados enquanto aparecerem na descoberta ou tiverem posição.
B. Uma lista fixa de tokens
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Cota] Como respeitar o limite do plano?

A. Um limitador de chamadas por minuto, configurável e com padrão de 250, fica abaixo do limite do plano. Com o limite atingido, o ciclo pula os tokens restantes e os mantém com o último valor e o horário dele, para o frescor acusar a idade. Uma resposta 429 do fornecedor respeita o `Retry-After`.
B. Sem limitador
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Frescor] Que horário vale para o frescor de 60 s (FR1.4)?

A. O horário em que o dado foi buscado, guardado com cada observação. A idade é calculada na leitura (`agora - observedAt`), e um valor com mais de 60 s aparece como vencido para a barreira (U3). O horário que o fornecedor informa, quando existe, fica só como informação extra.
B. Só o horário do fornecedor
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Fonte simulada] Como sinalizar e impedir?

A. As fontes continuam vindo da configuração `MARKET_SOURCES`. Com o REAL ligado, o perfil de produção não sobe se uma fonte simulada estiver configurada. A interface de dados de mercado (M4) passa a informar se a fonte ativa é real ou simulada, e a barreira REAL (U3) usa essa informação para recusar a ordem. O scanner mostra o aviso "fonte simulada ativa".
B. Só o aviso na tela
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Divergência] Com uma fonte real só, como fica a checagem entre duas fontes?

A. A checagem de divergência continua para quando houver duas fontes reais. Com uma fonte só, cada valor fica marcado como de fonte única, o que entra na cobertura da avaliação, e nunca é tratado como divergente nem como confirmado por duas fontes.
B. Desligar a checagem
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Atribuição] O que aparece na tela (FR1.2)?

A. Junto dos dados do scanner e do detalhe do token aparece "Dados: CoinGecko", com o link exigido pelos termos do fornecedor, além da fonte e do horário de cada valor. O texto e o link ficam na configuração, para seguir o contrato.
B. Só no rodapé
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Falha] O que acontece quando a CoinGecko cai?

A. A fonte é marcada como FAILED, como hoje, e os valores ficam com o último dado e o horário dele, de modo que o frescor acusa o atraso e o REAL recusa as ordens por dado vencido. Em produção não há volta automática para a fonte simulada. A saúde do serviço mostra a fonte degradada.
B. Voltar para a fonte simulada
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q9. [Segredo] Onde fica a chave da API?

A. Na variável de ambiente `COINGECKO_API_KEY`: no `.env` local (só o nome no `.env.example`) e no gerenciador de segredos na nuvem. Ela vai só no cabeçalho de autenticação da chamada, nunca em log, nunca no navegador e nunca em `NEXT_PUBLIC_*`.
B. No arquivo de configuração
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q10. [Testes] Como testar sem rede?

A. Com respostas gravadas da CoinGecko (fixtures JSON sintéticas, sem mint nem carteira reais) servidas por um WireMock. Os casos cobrem: campo ausente vira `unknown`, 429 com `Retry-After`, 5xx vira FAILED, resposta malformada, token repetido e o limite de chamadas. O ambiente local e o `verify.sh` continuam com as fontes simuladas.
B. Chamar a API real em teste
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- API on-chain paga da CoinGecko, rede solana, em lote; campo ausente vira unknown [Q1].
- Descoberta por pools novos e em alta, até 50 tokens por ciclo [Q2].
- Limitador de 250 chamadas por minuto e respeito ao Retry-After [Q3].
- Frescor pelo horário da busca; mais de 60 s é vencido para a U3 [Q4].
- Fonte simulada sinalizada na M4, proibida em produção com REAL ligado, aviso no scanner [Q5].
- Fonte única marcada como tal, sem divergência nem confirmação dupla [Q6].
- Atribuição "Dados: CoinGecko" com link configurável [Q7].
- Falha mantém o último valor com horário; sem volta para a simulada em produção [Q8].
- Chave em COINGECKO_API_KEY, nunca em log nem no navegador [Q9].
- Testes com respostas gravadas e WireMock; local e verify.sh seguem simulados [Q10].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
