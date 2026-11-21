# Registro de restrições — paper-real-unico

Cada restrição tem um tipo, o efeito sobre esta intenção e a fonte.

## Técnicas

| ID | Restrição | Efeito nesta intenção | Fonte |
|---|---|---|---|
| CT-01 | REAL e PAPER ficam estritamente separados em fundos, ordens, ledger, posições e P&L. | A tela única mostra os dois lado a lado, mas cada número vem só do próprio modo. Não existe total que some os dois. | [memory] `project.md` Mandated; `team.md` (testes obrigatórios de separação); [Q3] |
| CT-02 | O bot nasce PAPER ou REAL e não muda. O banco recusa a troca. | Nenhuma conversão de bot entre modos. Isso já está fora do escopo. | `bots/domain/BotMarket.java`; `intent-statement.md` [Q8 do intent-capture] |
| CT-03 | Os valores monetários e as quantidades usam decimal exato ou inteiros. Nunca ponto flutuante. | O custo médio por bot e o rateio entre bots usam inteiros (lamports e unidades base), com regra fixa para o resto do arredondamento [Q8] [Q9]. | [memory] `project.md` Forbidden |
| CT-04 | Dado de mercado ausente é `unknown`, nunca zero. | Um token sem preço deixa o não realizado do bot dono como `unknown`. O total do modo também fica `unknown`. | [memory] `project.md` Mandated; `team.md` Code Style |
| CT-05 | O ledger é só de acréscimo. Uma correção só entra por lançamento compensatório. | A atribuição por bot é derivada na leitura e não altera lançamentos [Q10]. | [memory] `project.md` Mandated |
| CT-06 | Uma carteira do bot por pessoa, compartilhada pelos bots REAL dela. | As posições reais são da carteira, não do bot. A parte de cada bot é derivada das ordens. | [Q1] (opção C rejeitada); `0016-real-bot-wallet.sql` |
| CT-07 | Toda ordem com origem `BOT` tem o bot. A liquidação nunca tem. Não existe outra origem. | A atribuição depende só desse campo. As ordens do modo Assinada têm bot. As únicas vendas sem bot são "vender tudo e sacar" e as condicionais [Q9]. | `0019-real-execution.sql` (`real_order_origin_ck`, `real_order_origin_shape_ck`) |
| CT-08 | A saída condicional pertence à posição (carteira e token). Há no máximo uma aberta por token. A venda dela entra no ledger, mas não na tabela de ordens. | O cálculo por bot lê essas vendas e rateia o resultado entre os bots donos, com a marca "fechado por condicional" [Q9]. | `0022-real-exits.sql`; `realexits/application/FillPosting.java` |
| CT-09 | O ciclo do bot real hoje vê as posições, o saldo e o tempo de posse da carteira inteira, e vende a posição inteira do token. | O ciclo passa a vender só a quantidade do bot depois do fim da Performance Validation do intent do REAL. Até lá, a separação é feita na leitura [Q10]. | `bots/worker/RealCycle.java`; `realexecution/infra/DecisionConsumer.java` |
| CT-10 | O simulado está em dólar, com decimais. O real está em lamports de SOL. | Cada modo mostra os valores na própria unidade. Uma eventual conversão para comparar seria só visual e marcada como estimativa. | `pnl/domain/PnlCalculator.java`; `pnl/web/RealPnlController.java` |
| CT-11 | Os módulos falam entre si só por interface pública ou por evento, e as regras ArchUnit verificam isso. | O `pnl` lê o bot das ordens pela interface de `realexecution`, e as condicionais por uma interface de `realexits` ou do ledger. Nunca pelas tabelas do outro módulo. | `team.md` Code Style; `apps/api/src/test/java/com/memetrade/architecture/` |
| CT-12 | Os tipos do frontend são gerados do contrato da API. Uma quebra de contrato falha a CI. | Os campos novos do P&L REAL por bot entram no contrato. As rotas atuais continuam compatíveis. | `team.md` Code Style |
| CT-13 | As ordens reais finais são apagadas depois de 5 anos e 1 dia. | Hipótese: para posições abertas há mais de 5 anos, o recálculo na leitura perde o histórico. É um risco remoto no beta. | `realexecution/infra/RealExecutionPurgeJob.java` |
| CT-14 | Mesma pilha: Spring Boot e Java 21, Next.js e TypeScript, PostgreSQL. | Nenhuma tecnologia nova. | [Q4]; `team.md` Way of Working |
| CT-15 | Testes antes do código no núcleo financeiro, com o P&L incluído. Cobertura mínima de 80%. Cenários determinísticos de resultado conhecido. | Os cenários de custo médio por bot e de rateio vêm escritos antes do código: venda parcial, venda maior que a quantidade do bot, liquidação, condicional, taxa, preço `unknown` e ordem que falhou na rede. | [Q4]; [Q8]; [Q9]; `team.md` Testing Posture |

## Organizacionais

| ID | Restrição | Efeito nesta intenção | Fonte |
|---|---|---|---|
| CO-01 | O fundador trabalha sozinho, com o assistente. Ele decide o escopo e a prioridade. | As decisões vão para ele nos gates. Método, rateio e ciclo já foram decididos [Q8] [Q9] [Q10]. | [Q4]; `stakeholder-map.md` |
| CO-02 | Não há prazo fixo. O trabalho entra depois do REAL na mainnet do fundador. | A mudança no ciclo do bot pode esperar o intent do REAL, sem perda. | [Q5]; [Q10] |
| CO-03 | O intent `261113-trading-real-bot` está na Performance Validation. Esta feature não pode mudar o que ele está validando. | A mudança no ciclo do bot real só entra depois do fim da validação [Q10]. O cálculo na leitura usa uma rota ou um campo novo e não altera a rota em validação. | [Q6]; [Q10]; `aidlc/spaces/default/intents/261113-trading-real-bot/aidlc-state.md` |
| CO-04 | Trunk-based em `main`, com a CI verde. O `verify.sh` com todos os specs de ponta a ponta é a suíte completa. | Um spec de ponta a ponta novo, para a visão única com um bot PAPER e um bot REAL, entra no `verify.sh` pelo responsável. | `team.md` Way of Working e Walking Skeleton |
| CO-05 | O orçamento é de US$ 350 por conta, sem custo novo. | Nenhum recurso de nuvem novo. | [Q5]; [Q7]; `project.md` Corrections |

## Regulatórias e de produto

| ID | Restrição | Efeito nesta intenção | Fonte |
|---|---|---|---|
| CR-01 | Nada novo de regulação. Os termos v3 já cobrem o PAPER e o REAL. | Não precisa de novo parecer nem de novo aceite dos termos, desde que o comportamento descrito nos termos não mude. | [Q3] |
| CR-02 | A LGPD já cobre os dados. | Não entra dado pessoal novo. A anonimização continua valendo para ordens e bots. | [Q3]; `team.md` (testes de anonimização) |
| CR-03 | Nunca prometer lucro (C-16). | O resultado simulado traz o rótulo de simulado em texto e o aviso de que não garante o real. Não existe ranking que misture os modos. | [memory] `project.md` Forbidden |
| CR-04 | A tela mostra com clareza o que é simulado e o que é dinheiro real, e nunca soma os dois. | O modo aparece em texto ou ícone, nunca só em cor. Isso segue a acessibilidade básica do beta. | [Q3]; `project.md` Corrections (acessibilidade) |
| CR-05 | Validação do tenant no servidor em toda leitura. | O P&L por bot filtra pela conta e pela pessoa. Ler o bot de outra conta devolve 403 ou 404. | [memory] `project.md` Mandated; `team.md` Testing Posture |
| CR-06 | Aviso na tela e nos termos quando o comportamento muda. | Quando o bot real passar a vender só a própria quantidade [Q10], os convidados recebem um aviso na tela. Hipótese: os termos não mudam. | `stakeholder-map.md` [Q7 do intent-capture]; [Q10] |

## Sources

- [Q1] a [Q10]: `ideation/feasibility/feasibility-questions.md`.
- `ideation/intent-capture/intent-statement.md` e `stakeholder-map.md`.
- [memory] `aidlc/spaces/default/memory/project.md` e `team.md`.
- Os caminhos de código citados nas tabelas, sob `apps/api/src/main/`.

## Assumptions & Open Questions

- [assumption] A mudança no ciclo do bot real [Q10] não exige novo aceite dos termos v3, porque os termos descrevem o bot e não a forma de dividir a posição. Isso deve ser confirmado na leitura dos termos durante os requisitos.
- [assumption] A regra de apagar ordens depois de 5 anos (CT-13) não afeta o beta.
