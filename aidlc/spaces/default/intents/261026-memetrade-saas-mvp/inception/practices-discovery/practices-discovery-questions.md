# Perguntas de práticas da equipe — MemeTrade Analytics

## Contexto

- Projeto novo, sem código, sem commits e sem CI. Você desenvolve sozinho com apoio de IA, sem orçamento por enquanto (C-12).
- Rascunho das práticas: `team-practices.md` e `discovered-rules.md` nesta pasta. Revisões do engenheiro de qualidade, do desenvolvedor e do engenheiro de segurança em `contributions/`.
- As respostas definem como vamos trabalhar daqui em diante e são gravadas como práticas da equipe e do projeto.

## Q1. [Release] Qual nome o ramo principal deve ter?
O repositório está em `master` e o padrão que usamos é `main`. Como ainda não há commits nem repositório remoto, trocar agora não custa nada.

A. Renomear para `main` antes do primeiro commit (recomendado)
B. Manter `master` como ramo principal
X. Other (please specify)

[Answer]: A. Renomear para main

## Q2. [Release] Onde ficam o repositório remoto e a integração contínua (CI)?
Integração contínua é a verificação automática que roda a cada mudança. A escolha precisa caber no orçamento zero; a cota gratuita de minutos depende do provedor e da visibilidade do repositório.

A. GitHub privado com GitHub Actions, confirmando a cota gratuita na Sprint 0 (recomendado)
B. GitHub público com GitHub Actions (mais recursos gratuitos de segurança, código aberto a todos)
C. Decidir na Sprint 0
X. Other (please specify)

[Answer]: A. GitHub privado

## Q3. [Release] Como funcionam ambientes e aprovação de implantação por enquanto?
Hoje não há conta de nuvem nem ambiente de testes compartilhado; o único ambiente é o local com Docker Compose.

A. Só ambiente local por agora; quando houver staging, implantar nele a cada merge; você aprova produção do beta PAPER, mas os gates de REAL (G2/G3) exigem um revisor independente ainda a nomear (recomendado)
B. Só ambiente local; decidir as regras de implantação quando houver nuvem
X. Other (please specify)

[Answer]: A. Local + revisor p/ REAL

## Q4. [Segurança] Ativamos a proteção contra segredos antes do primeiro commit?
Um segredo (senha, chave de API) que entra no primeiro commit fica para sempre no histórico.

A. Sim: `.env` fora do git, `.env.example` sem valores, verificação automática de segredos ao commitar e uma varredura dos arquivos atuais antes do primeiro commit (recomendado)
B. Só quando a CI existir
X. Other (please specify)

[Answer]: A. Antes do 1º commit

## Q5. [Segurança] Qual política de segurança a CI deve aplicar?
Inclui varredura de segredos, de dependências e análise estática de código, todas com ferramentas gratuitas.

A. Bloquear o merge em achados Críticos e Altos e só avisar nos Médios; exceções versionadas com justificativa e validade de até 90 dias, aprovadas por você; atualização automática de dependências esperando alguns dias antes de adotar versões novas de pacotes do frontend, salvo correções de segurança (recomendado)
B. No beta, só avisar, sem bloquear; endurecer antes de abrir ao público
X. Other (please specify)

[Answer]: A. Bloquear Crítico/Alto

## Q6. [Desenvolvimento] Construímos primeiro uma fatia fina de ponta a ponta? Um walking skeleton é uma versão mínima que atravessa o sistema inteiro, construída primeiro para provar que as peças se conectam antes de entrarem as funções reais.
Fatia sugerida: navegador → tela web → API → banco de dados, tudo subindo localmente, com dados de mercado simulados aparecendo numa lista mínima do scanner com fonte, horário e `unknown`. Verificação: um único script que sobe o ambiente, roda um teste de ponta a ponta e devolve erro em qualquer falha.

A. Sim, com essa fatia e esse tipo de comando de verificação (recomendado)
B. Sim, mas a fatia e o comando são definidos no design
C. Não; começar direto pelas funções
X. Other (please specify)

[Answer]: A. Sim, essa fatia

## Q7. [Desenvolvimento] Em que idioma ficam código, tabelas e endpoints, e como frontend e backend combinam os dados?
Os textos de tela já foram decididos em português.

A. Código, tabelas, endpoints e logs em inglês; erros da API no formato padrão RFC 7807 previsto no PRD; tipos do frontend gerados a partir do contrato da API, nunca escritos à mão (recomendado)
B. Tudo em português, inclusive o código
X. Other (please specify)

[Answer]: A. Inglês + contrato

## Q8. [Desenvolvimento] As fronteiras entre módulos do backend devem ser verificadas automaticamente?
O backend será dividido em módulos (Ledger, Risk, Scanner etc.). A regra é cada módulo falar com outro só pela "porta da frente" (interface pública ou evento), nunca lendo as tabelas do outro, e PAPER nunca compartilhar dados com REAL.

A. Sim, com um teste automático na CI (recomendado)
B. Sim, só como convenção por enquanto
C. Não agora
X. Other (please specify)

[Answer]: B. Convenção

## Q9. [Desenvolvimento] Quando escolher as ferramentas de build e de pacotes?
Por exemplo, Gradle ou Maven no backend e npm ou pnpm no frontend. Sua experiência com a stack ainda vai ser mapeada na Sprint 0.

A. Decidir no design da aplicação, depois do mapeamento da Sprint 0 (recomendado)
B. Escolher agora (informar quais)
X. Other (please specify)

[Answer]: B. Escolher agora (ferramentas definidas em Q15 e Q16)

## Q10. [Qualidade] Em que ordem escrevemos código e testes?
"Test-after" é implementar e depois testar; "TDD" é escrever o teste antes do código.

A. Teste antes do código no núcleo financeiro (limites de risco, ledger, P&L, deduplicação) e teste depois no restante (recomendado: código gerado com IA tende a passar em testes escritos sobre ele mesmo)
B. Teste depois de cada camada em todo o código (padrão)
C. Cenários Given/When/Then das histórias automatizados antes da implementação, e testes unitários depois
X. Other (please specify)

[Answer]: A. TDD no núcleo

## Q11. [Qualidade] Como funciona o mínimo de 80% de cobertura de testes?
Cobertura é a parte do código exercitada pelos testes. O mínimo de 80% vem do padrão do escopo enterprise e não pode ser reduzido para uma etapa passar.

A. 80% separadamente no backend e no frontend, com exclusões listadas na configuração e a ferramenta falhando o build abaixo do mínimo (recomendado)
B. Igual à A, mais 90% com cobertura de ramos nos módulos críticos (risco, ledger/P&L, deduplicação, isolamento entre contas)
C. 80% no total do projeto
X. Other (please specify)

[Answer]: A. 80% por parte

## Q12. [Qualidade] Quais testes são obrigatórios sempre que uma mudança tocar a área de risco?
Lista: isolamento entre contas (inclusive acesso por ID de outra conta e tarefas em segundo plano), separação REAL/PAPER, deduplicação, limites, `unknown` nunca zero, pausa impede novas operações, ledger sem edição, P&L com cenários de resultado conhecido, REAL desligado por padrão, login sem reutilização de assinatura, e um teste para cada defeito corrigido.

A. Todos obrigatórios, mais checagem automática de acessibilidade básica nas telas do fluxo principal bloqueando o merge (recomendado)
B. Todos obrigatórios, sem portão de acessibilidade por enquanto
X. Other (please specify)

[Answer]: B. Todos, sem acessibilidade

## Q13. [Qualidade] Onde e com que frequência rodam os testes mais pesados?
A CI gratuita roda em Linux; o desenvolvimento local é no Windows. Os testes de ponta a ponta consomem a cota gratuita.

A. CI em Linux e desenvolvimento no Windows, com fim de linha padronizado; teste rápido de ponta a ponta em toda mudança e a suíte completa no merge para o ramo principal; teste instável é tratado como defeito (recomendado)
B. Rodar tudo em toda mudança
X. Other (please specify)

[Answer]: A. Suíte completa no merge

## Q14. [Responsável do projeto] Quais regras devem valer como restrição rígida do projeto?
Candidatas em `discovered-rules.md` (do PRD e do registro de restrições), mais as sugeridas na revisão: nunca usar ponto flutuante para dinheiro, quantidades de token ou P&L; sempre registrar instantes em UTC; bloquear commits com segredos; nunca registrar em log segredos, tokens, nonces ou assinaturas; nunca expor segredo no código do navegador; nunca incluir no código de produção APIs de importação de chave secreta ou de frase mnemônica; versionar lockfiles; fixar por SHA as ações de terceiros da CI.

A. Confirmar todas como regras rígidas (recomendado)
B. Confirmar só as regras vindas do PRD e do registro de restrições; as de ferramenta ficam como prática, não regra rígida
C. Quero revisar uma a uma
X. Other (please specify)

[Answer]: A. Confirmar todas

## Q15. [Desenvolvimento] Qual ferramenta de build usamos no backend Java?
Você escolheu definir as ferramentas agora (Q9). Gradle tem build incremental e declara a versão do Java 21 no próprio projeto; Maven é mais simples de ler.

A. Gradle (com Kotlin DSL)
B. Maven
X. Other (please specify)

[Answer]: B. Maven

## Q16. [Desenvolvimento] Qual gerenciador de pacotes usamos no frontend?
Também ligado à sua escolha em Q9. A versão do Node LTS fica fixada no projeto em qualquer caso.

A. npm (vem com o Node, nada a instalar)
B. pnpm (instalação mais rápida e econômica em disco)
X. Other (please specify)

[Answer]: B. pnpm

## Consolidated Summary Confirmation

- Ramo principal renomeado para `main` antes do primeiro commit; trunk-based com squash-merge por Bolt.
- Repositório no GitHub privado com GitHub Actions, confirmando a cota gratuita na Sprint 0.
- Implantação: só ambiente local por agora; staging a cada merge quando existir; você aprova produção do beta PAPER; G2/G3 de REAL exigem revisor independente.
- Proteção de segredos ativa antes do primeiro commit (`.env` fora do git, `.env.example`, verificação ao commitar, varredura inicial).
- Segurança na CI: bloqueia Crítico e Alto, avisa em Médio; exceções versionadas com validade de até 90 dias aprovadas por você; versões novas de pacotes do frontend adotadas após alguns dias, salvo correções.
- Fatia fina primeiro: navegador → tela → API → banco com dados simulados no scanner, verificada por um script único de ponta a ponta.
- Código, tabelas, endpoints e logs em inglês; erros RFC 7807; tipos do frontend gerados do contrato da API.
- Fronteiras entre módulos do backend (e separação REAL/PAPER) como convenção, sem verificação automática por enquanto.
- Ferramentas: Maven no backend e pnpm no frontend.
- Testes: TDD no núcleo financeiro (risco, ledger, P&L, deduplicação) e teste depois no restante.
- Cobertura mínima de 80% separadamente no backend e no frontend, exclusões na configuração, build falha abaixo do mínimo.
- Testes obrigatórios na área de risco: isolamento entre contas, REAL/PAPER, deduplicação, limites, `unknown`, pausa, ledger sem edição, P&L com resultados conhecidos, REAL desligado por padrão, login sem reutilização de assinatura, um teste por defeito corrigido; sem portão automático de acessibilidade por enquanto.
- CI em Linux e local no Windows com fim de linha padronizado; teste rápido em toda mudança e suíte completa no merge; teste instável é defeito.
- Todas as regras candidatas confirmadas como restrições rígidas do projeto.

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
