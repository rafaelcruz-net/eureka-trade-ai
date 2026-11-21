# Perguntas de viabilidade — paper-real-unico

Conversa: português (Brasil).

Fontes:
- `ideation/intent-capture/intent-statement.md`;
- as regras de `org.md`, `team.md` e `project.md`;
- a leitura do código atual.

O que já existe no código hoje:
- o bot nasce PAPER ou REAL (`BotMarket`) e não muda;
- `GET /api/v1/pnl` dá o P&L PAPER por bot (`botId`) e o total;
- `GET /api/v1/real/pnl` dá o P&L REAL só por pessoa, porque as posições REAL ficam na carteira do bot, que é uma só por pessoa e é compartilhada pelos bots REAL dela;
- algumas vendas REAL não pertencem a nenhum bot: "vender tudo e sacar", as saídas condicionais e as ordens do modo Assinada.

## Q1. P&L REAL por bot

O P&L REAL por bot exige saber de qual bot é cada compra e cada venda. Isso porque as posições ficam numa carteira comum. Como atribuir?

A. Cada ordem REAL já guarda o bot que a decidiu. As posições passam a ser contadas por bot, ou seja, cada compra abre um lote daquele bot. As vendas fecham os lotes do mesmo bot, do mais antigo para o mais recente (PEPS). As vendas sem bot (vender tudo, saída condicional, ordem manual) entram numa linha "Sem bot / manual", proporcional aos lotes que fecharem. O total REAL continua igual ao de hoje e fecha com o ledger. (recomendado)
B. P&L REAL só por pessoa, como hoje, e por bot só no PAPER
C. Uma carteira do bot por bot REAL, o que muda o desenho da custódia
D. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. Integrações

Com o que a feature precisa se integrar?

A. Só com módulos que já existem no monólito:
   - `bots`, com o mercado do bot;
   - `pnl`, com os cálculos de PAPER e REAL;
   - `realexecution`, com as ordens e o bot de cada uma;
   - `ledger`, com o PAPER e o REAL separados;
   - o painel e a tela de bots do `apps/web`.

   Nada de sistema externo novo. (recomendado)
B. Também com um serviço externo de análise de carteiras
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. Regulação e conformidade

Há exigência regulatória nova?

A. Nada novo além do que já vale. Os termos v3 já descrevem o PAPER e o REAL, e a LGPD já cobre os dados. A tela precisa mostrar de forma clara o que é simulado e o que é dinheiro real, e nunca somar os dois num único valor, como manda a regra do projeto [memory]. (recomendado)
B. Precisa de novo parecer jurídico
C. Not identified
X. Other (please specify)

[Answer]: A

## Q4. Pilha e equipe

Que pilha e que equipe fazem o trabalho?

A. A mesma pilha do projeto: Spring Boot e Java 21, Next.js e TypeScript, PostgreSQL. O fundador, sozinho, com o assistente, seguindo a postura de testes do `team.md`, com testes antes do código no núcleo financeiro, P&L incluído. (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. Prazo e orçamento

Quais são o prazo e o orçamento?

A. Nenhum prazo fixo. O trabalho entra depois do REAL na mainnet do fundador, sem custo novo de infraestrutura: é o mesmo ambiente, e o orçamento de US$ 350 por conta continua. (recomendado)
B. Prazo fixo (diga qual)
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. Bloqueios da organização

Há algo que trave este trabalho?

A. O intent 261113-trading-real-bot ainda está na Performance Validation, esperando a carga. Esta feature depende do P&L REAL dele e não deve mudar nada que aquele intent esteja validando. As duas coisas andam em paralelo, com cuidado nos arquivos comuns: `pnl` e o painel. (recomendado)
B. Nenhum bloqueio
C. Not identified
X. Other (please specify)

[Answer]: A

## Q7. AWS

Que serviços e contas AWS entram?

A. Nenhum novo. A feature usa o ambiente único da mainnet, já desenhado no Terraform e ainda não aplicado. A devnet fica só para os testes do fundador. (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. Método de custo por bot

Contexto (seguimento): A leitura do código mostrou que parte da resposta Q1 não vale como foi escrita (`feasibility-assessment.md`): - não existe ordem manual: as ordens do modo Assinada já guardam o bot; - o bot REAL de hoje vende a posição inteira do token na carteira, inclusive o que outro bot comprou; - o P&L REAL e o PAPER usam custo médio, não PEPS.

Que método de custo usar para separar o P&L REAL por bot?

A. Custo médio por bot, o mesmo método que o PAPER, o REAL e o ledger já usam. O total de cada modo não muda (recomendado)
B. PEPS por bot, como estava na Q1. O total realizado mais não realizado não muda, mas a divisão entre realizado e não realizado fica diferente da que existe hoje
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q9. Resultado das vendas sem bot

Como tratar o resultado das vendas sem bot ("vender tudo e sacar" e saídas condicionais)?

A. Ratear entre os bots donos das quantidades vendidas, na proporção do que cada um tinha, e marcar no relatório "fechado por liquidação" ou "fechado por condicional" (recomendado)
B. Mandar tudo para a linha "Sem bot / manual", como estava na Q1
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q10. O bot REAL vende só o que ele comprou

Hoje, quando dois bots REAL compram o mesmo token, a venda de um leva também os tokens do outro. O que fazer?

A. Mudar o ciclo do bot REAL para que ele venda só a própria quantidade. A mudança entra depois que o intent 261113-trading-real-bot terminar a Performance Validation, porque o ciclo faz parte do que está sendo validado. Até lá, a feature calcula a separação só na leitura (recomendado)
B. Mudar o ciclo agora, junto com o intent do REAL
C. Não mudar o ciclo: limitar a um bot REAL por token por pessoa
D. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **P&L REAL por bot.** Toda ordem REAL de bot já guarda o bot que a decidiu, e o banco exige isso. A separação usa custo médio por bot, o mesmo método de hoje, e o total de cada modo não muda [Q1] [Q8].
- **Vendas sem bot.** "Vender tudo e sacar" e as saídas condicionais são as únicas vendas sem bot. O resultado delas é rateado entre os bots donos das quantidades vendidas, com a marca "fechado por liquidação" ou "fechado por condicional" [Q9].
- **O bot REAL passa a vender só a própria quantidade.** A mudança no ciclo entra depois que o intent do REAL terminar a Performance Validation. Até lá, a separação é calculada só na leitura [Q10].
- **Integrações.** Só módulos que já existem, sem nada externo [Q2].
- **Regulação.** Nada novo. A tela distingue com clareza o simulado do real e nunca soma os dois [Q3].
- **Pilha e equipe.** As mesmas de hoje, com testes antes do código no P&L [Q4].
- **Prazo e custo.** Sem prazo fixo. O trabalho entra depois do REAL na mainnet do fundador, sem custo novo de infraestrutura [Q5].
- **Dependência.** O trabalho anda em paralelo ao intent do REAL, que está na Performance Validation, com cuidado nos arquivos comuns [Q6].
- **AWS.** Nenhum serviço novo. Os usuários ficam no ambiente único da mainnet, e a devnet fica só para o fundador [Q7].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
