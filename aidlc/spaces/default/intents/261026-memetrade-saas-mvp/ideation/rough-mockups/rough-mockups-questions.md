# Perguntas dos mockups iniciais — MemeTrade Analytics

## Contexto já confirmado

- Beta por convite no Brasil; núcleo do MVP: descoberta → avaliação → bot PAPER → P&L reconciliável (`../scope-definition/scope-document.md`).
- Onboarding do PRD: convite, termos e aviso de risco, login por assinatura de mensagem, associação de carteira pública. Nunca pedir seed phrase.
- Dados ausentes aparecem como `unknown`, com fonte, horário, frescor e cobertura visíveis; avaliação usa NO_TRADE quando faltam dados.
- REAL fica fora do beta; telas de PAPER e P&L levam rótulo persistente de modo simulado.
- Filtros salvos, watchlist, alertas e métricas avançadas ficam para depois (`../scope-definition/intent-backlog.md`).

## Q1. [Produto] Qual deve ser a primeira tela depois do login?
Define o ponto de entrada do convidado e o que ele vê primeiro todos os dias.

A. Scanner de tokens direto, com um aviso discreto quando o bot PAPER tiver novidades (recomendado: a descoberta é o núcleo do beta)
B. Painel inicial com resumo do bot PAPER e do P&L, e atalho para o scanner
C. Painel inicial no primeiro acesso (guia rápido) e scanner nos acessos seguintes
X. Other (please specify)

[Answer]: B. Painel com resumo

## Q2. [Produto] Como o convidado cria um bot PAPER?
Influencia quantas telas o fluxo de bot precisa e de onde ele parte.

A. Assistente próprio em poucas etapas: regras de seleção de tokens, limites, revisão e início (recomendado: os limites do PRD pedem revisão antes de ativar)
B. A partir do detalhe de um token: "simular com este token" pré-preenche o bot
C. As duas entradas, convergindo para a mesma revisão de limites antes de iniciar
X. Other (please specify)

[Answer]: A. Assistente próprio

## Q3. [Produto] Em que idioma fica a interface do beta?
O público inicial é brasileiro; termos de mercado como "market cap" ou "slippage" podem ficar em inglês.

A. Português, mantendo termos de mercado consagrados em inglês com explicação na própria tela (recomendado)
B. Português e inglês desde o beta, com troca de idioma
C. Inglês
X. Other (please specify)

[Answer]: A. Português

## Q4. [Design] Como o scanner deve organizar a informação?
O scanner mostra muitos tokens e muitos campos; a escolha define a hierarquia da tela principal.

A. Tabela densa, com detalhe do token em painel lateral sem sair da lista (recomendado: varredura rápida e comparação)
B. Tabela densa, com detalhe do token em página própria
C. Cartões por token, com menos campos visíveis e detalhe ao abrir
X. Other (please specify)

[Answer]: A. Tabela + painel lateral

## Q5. [Design] Quais dispositivos o beta precisa atender?
Define se os mockups detalham também a tela pequena.

A. Computador como principal, com versão de celular para consultar scanner, bot e P&L (recomendado)
B. Apenas computador no beta
C. Celular como principal
X. Other (please specify)

[Answer]: B. Só computador

## Q6. [Design] Existe identidade visual ou guia de interface a seguir?
Nenhum guia aparece nos documentos do projeto até agora.

A. Ainda não há; usar uma base neutra com tema escuro e claro, e definir a marca depois (recomendado)
B. Ainda não há; usar só tema escuro, comum em ferramentas de trading
C. Já existe um guia ou referência visual (indicar qual)
X. Other (please specify)

[Answer]: B. Só tema escuro

## Q7. [Design] Que nível de acessibilidade o beta deve cumprir?
Afeta contraste, navegação por teclado e o uso de cor para indicar risco, ganho e perda.

A. WCAG 2.1 AA: tudo operável por teclado, contraste mínimo e risco/ganho/perda nunca indicados só por cor (recomendado)
B. Boas práticas básicas no beta e AA completo antes de abrir para o público
X. Other (please specify)

[Answer]: B. Básico no beta

## Consolidated Summary Confirmation

- Primeira tela após o login: painel com resumo do bot PAPER e do P&L, com atalho para o scanner.
- Criação do bot PAPER: assistente próprio em poucas etapas (regras de seleção, limites, revisão, início).
- Idioma: português, mantendo termos de mercado em inglês com explicação na tela.
- Scanner: tabela densa com detalhe do token em painel lateral.
- Dispositivos: apenas computador no beta.
- Visual: sem guia ainda; só tema escuro.
- Acessibilidade: boas práticas básicas no beta; WCAG 2.1 AA completo antes de abrir ao público.

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
