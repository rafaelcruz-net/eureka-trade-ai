# Entrevista de práticas — Termos de uso e LGPD

Conversa: português (Brasil). As práticas aprovadas do time (`team.md`) continuam valendo. O rascunho do líder (`team-practices.md`, `evidence.md`) encontrou diferenças entre o que foi aprovado e o que o repositório mostra. Estas perguntas resolvem só essas diferenças.

## Q1. Como as mudanças entram em `main`?

O aprovado diz que cada Bolt roda numa cópia separada do repositório e entra em `main` por um único commit com o nome do Bolt (squash-merge). O histórico mostra 13 commits diretos em `main`, feitos quando você pediu, alguns juntando duas Units.

A) Registrar a prática real: as mudanças entram direto em `main`, num commit pedido por você, com a CI verde. Squash-merge só quando houver uma branch, ou seja, quando você pedir uma (recomendado)
B) Voltar ao aprovado: uma cópia separada e um squash-merge por Bolt
X) Other (please specify)

[Answer]: A

## Q2. E as lacunas da CI?

Hoje a CI não roda lint, verificação de tipos, testes nem a checagem do contrato do app administrativo. O web roda os testes só em parte das telas. Este trabalho mexe nos dois apps.

A) Corrigir dentro deste trabalho. Você aplica as mudanças no `ci.yml`, que só você altera, já descritas no `ci-config.md` do trabalho anterior, antes do primeiro commit deste trabalho (recomendado)
B) Registrar como dívida com prazo
X) Other (please specify)

[Answer]: A

## Q3. Que suíte roda no merge para `main`?

O aprovado diz "smoke em toda mudança, suíte completa no merge". Hoje o job de ponta a ponta no merge roda o mesmo `verify.sh` do smoke.

A) Ajustar o texto à realidade. Em toda mudança e no merge rodam os testes de unidade e integração de todas as partes, com o piso de cobertura, e mais o `verify.sh`. O `verify.sh` com todos os specs de ponta a ponta é a suíte completa (recomendado)
B) Criar uma suíte maior só para o merge
X) Other (please specify)

[Answer]: A

## Q4. Que volume de testes vale neste trabalho?

O estado deste trabalho registra `Standard`; o aprovado do time diz `Comprehensive`.

A) `Comprehensive`, como o time aprovou; o piso de 80% continua (recomendado)
B) `Standard`
X) Other (please specify)

[Answer]: A

## Q5. A lógica de dados pessoais segue testes antes do código?

A) Sim. Anonimização, exclusão, novo aceite dos termos e expurgos entram na ordem de testes escritos antes do código, como o núcleo financeiro. E a lista de testes obrigatórios por risco ganha: "a anonimização não deixa a carteira em nenhuma tabela nem log consultável" e "a versão nova dos termos exige novo aceite antes de qualquer outra rota" (recomendado)
B) Não; testes depois, como nas demais camadas
X) Other (please specify)

[Answer]: A

## Q6. Construir primeiro uma fatia fina que roda de ponta a ponta?

Uma fatia fina é uma versão mínima que atravessa o sistema inteiro, feita primeiro para provar que as peças se conectam antes das funções completas.

A) Sim. A fatia é a página `/privacidade` servida pela API, a partir do catálogo com a versão vigente, e mostrada no app sem login, com um spec de ponta a ponta novo. A verificação continua sendo `bash scripts/verify.sh`, com o spec novo que você acrescenta ao script (recomendado)
B) Não; sem fatia fina neste trabalho
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Way of Working: mudanças entram direto em `main` em commit pedido pelo responsável, com a CI verde; squash-merge só quando houver branch pedida.
- CI: as lacunas do app administrativo e do web são corrigidas neste trabalho; o responsável aplica no `ci.yml` as mudanças do `ci-config.md` antes do primeiro commit.
- Suíte: testes de unidade e integração de todas as partes com piso de cobertura e o `verify.sh` em toda mudança e no merge; o `verify.sh` com todos os specs é a suíte completa.
- Volume de testes: `Comprehensive`, com piso de 80%.
- Testes antes do código também para anonimização, exclusão, novo aceite e expurgos; dois testes obrigatórios novos (carteira sem resíduo após anonimização; versão nova exige novo aceite).
- Fatia fina primeiro: `/privacidade` servida pela API e mostrada sem login, com spec novo no `verify.sh` acrescentado pelo responsável.

- Looks correct
- Request changes

[Answer]: Looks correct
