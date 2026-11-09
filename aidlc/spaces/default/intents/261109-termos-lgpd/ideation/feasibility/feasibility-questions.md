# Perguntas de Viabilidade e Restrições — Termos de uso e LGPD

Conversa: português (Brasil). Base: `ideation/intent-capture/intent-statement.md`, `ideation/market-research/build-vs-buy.md` e as regras do projeto. O que já existe no produto (lido do código):
- Termos versionados em `apps/api/src/main/resources/terms/v1/` (`terms.txt`, `risk-notice.txt`, `version.properties`), servidos pelo `TermsCatalog` da U3. O aceite é gravado com versão e horário, e uma versão nova exige novo aceite (`TERMS_NOT_ACCEPTED`).
- App do administrador (U8), trilha de auditoria (U2) e tarefas de expurgo: auditoria, feedback, avaliações de risco e histórico de mercado.
- Ledger append-only: lançamentos não podem ser editados nem apagados (regra do projeto).

## Q1. Com o que este trabalho precisa se integrar?

A) Com cinco partes:
- o catálogo de termos da U3, que ganha a v2 com termos e política;
- os dois apps, com páginas públicas e links no rodapé e no convite;
- o app do administrador (U8), com o registro de pedidos de titular;
- a trilha de auditoria (U2), para registrar o atendimento;
- as tarefas de expurgo, para conferir a tabela de retenção.

Nenhum serviço externo novo (recomendado)

B) Integrar também um serviço externo de e-mail para os pedidos
C) Not yet defined
X) Other (please specify)

[Answer]: A

## Q2. Como atender a exclusão sem quebrar o ledger, que não aceita edição nem exclusão?

A) Exclusão por anonimização. No pedido atendido:
- a conta é desativada e as sessões são revogadas;
- os dados que identificam a pessoa são apagados ou trocados por um valor sem vínculo: endereço da carteira, textos de feedback e registros de aceite ligados à pessoa;
- os lançamentos do ledger e os eventos de auditoria ficam, ligados só a identificadores internos sem vínculo com a pessoa;
- a política explica isso e o prazo de guarda da trilha de auditoria (12 meses), com a base legal indicada no parecer (recomendado)

B) Só desativar a conta, sem apagar dados
C) Not yet defined
X) Other (please specify)

[Answer]: A

## Q3. Quais restrições regulatórias e de residência de dados se aplicam?

A) Só a LGPD. A política informa onde os dados ficam, mas ainda não há hospedagem escolhida. Por isso, o local de armazenamento e a eventual transferência internacional entram como campos da política preenchidos na implantação. A versão publicada no beta só sai com esses campos definidos (recomendado)
B) Definir já a região de hospedagem (diga qual)
C) Not yet defined
X) Other (please specify)

[Answer]: A

## Q4. Qual a equipe, o orçamento e o prazo?

A) Desenvolvedor solo, com a stack atual (Spring Boot, Next.js, PostgreSQL), sem orçamento extra. Prazo: antes da abertura do beta, sem data fixa (recomendado)
B) Prazo com data fixa (diga qual)
C) Not yet defined
X) Other (please specify)

[Answer]: A

## Q5. Qual o canal do titular e o contato do encarregado?

A) Dois canais:
- um formulário na tela Conta do app, que cria o pedido (acesso, correção, exclusão ou outro) e mostra o andamento;
- um endereço de e-mail do encarregado, publicado na política.

O endereço é fornecido pelo responsável do projeto e configurado na implantação, sem ficar fixo no código (recomendado)

B) Só e-mail
C) Not yet defined
X) Other (please specify)

[Answer]: A

## Q6. Há bloqueios organizacionais ou de nuvem?

A) Nenhum bloqueio organizacional. Não há conta de nuvem (C-10, C-12): tudo roda no ambiente local até a hospedagem existir. Este trabalho não depende da AWS, exceto pelos campos de armazenamento da Q3 (recomendado)
B) Not identified
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Integrações: catálogo de termos da U3 (v2 com termos e política), os dois apps (páginas públicas, rodapé e convite), app do administrador (pedidos de titular), trilha de auditoria e tarefas de expurgo; nenhum serviço externo novo.
- Exclusão por anonimização: conta desativada, sessões revogadas, carteira, feedback e aceites deixam de identificar a pessoa; ledger e auditoria ficam com identificadores internos; política explica e informa os 12 meses da trilha com a base legal do parecer.
- Regulação: só LGPD; local de armazenamento e transferência internacional como campos preenchidos na implantação; a versão do beta só é publicada com eles definidos.
- Equipe: desenvolvedor solo, stack atual, sem orçamento extra; antes da abertura do beta, sem data fixa.
- Canal do titular: formulário na tela Conta com andamento, e e-mail do encarregado configurado na implantação.
- Bloqueios: nenhum organizacional; sem nuvem, tudo local; só o campo de armazenamento depende da hospedagem.

- Looks correct
- Request changes

[Answer]: Looks correct
