# Perguntas de NFR Requirements — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Base:
- `construction/u6-real-modes-ui/functional-design/` (BR1 a BR9, S1 a S44, adendo de contrato);
- `inception/refined-mockups/accessibility-checklist.md` e `interaction-spec.md`;
- `team.md`, em Testing Posture e Code Style:
  - Jest com piso de 80% por app;
  - Playwright;
  - `strict`;
  - tipos gerados do contrato;
  - carregando, erro e `unknown` como estados distintos;
- `project.md`:
  - acessibilidade básica do beta;
  - o navegador não monta nem envia transação;
  - sem SDK Solana em produção;
  - sem segredo em `NEXT_PUBLIC_*`;
- o código atual:
  - `apps/web`: Next.js 15, Jest 30, a CSP com nonce no `middleware`, o canal ao vivo em `features/dashboard`, `@solana/wallet-standard-features` e a carteira de teste do e2e em `e2e/support/test-wallet.ts`;
  - `apps/admin`, que segue o mesmo padrão.

## Q1. [Tempo de resposta] Que metas a tela precisa cumprir?

A. Metas medidas em testes de componente com relógio falso e conferidas no e2e:
- a faixa aparece em até 1 s depois do evento `real.signing.requested`;
- a contagem do diálogo erra no máximo 1 s em relação ao `deadline` do servidor;
- toda ação com dinheiro mostra "enviando" em até 100 ms depois do clique.

Não há meta numérica de carga da página; o portão de desempenho continua desligado, como no `team.md`.
B. Sem metas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Segurança no navegador] O que é obrigatório?

A. Cinco obrigações:
1. **A transação.** É lida em base64, passada como bytes a `signTransaction` e devolvida sem nenhuma alteração. O teste compara byte a byte a mensagem enviada e a recebida da carteira.
2. **Sem rastro do REAL no navegador.** Nada do REAL vai para `localStorage`, `sessionStorage`, cookie, URL ou log do console: transação, assinatura, endereço completo ou valor.
3. **Sem bibliotecas novas.** Nenhum SDK Solana nem biblioteca de transação no código de produção; só o `@solana/wallet-standard-features`, que já existe.
4. **A CSP com nonce que já existe continua valendo.** Nenhum script de terceiros entra.
5. **Sem variável nova `NEXT_PUBLIC_*` com segredo.** A rede vem de `GET /api/v1/real/release`, não de variável.

Essas obrigações viram regras de lint ou testes que falham.
B. Só boas práticas, sem teste
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Dinheiro na tela] Como garantir que nenhum valor passa por ponto flutuante?

A. Todo valor em lamports ou em unidades de token é `bigint` desde a resposta da API. O contrato manda os valores como `string` ou número inteiro, e o cliente gerado converte com uma função única.

Um analisador decimal e um formatador próprios, com testes de casos de borda, fazem toda conversão entre texto e valor. Os casos de borda são: 0, 9 casas, 10 casas, vírgula e ponto, espaços, sinal, o valor máximo e números grandes.

Uma regra de lint proíbe `Number(`, `parseFloat`, `toFixed` e operadores aritméticos sobre variáveis de dinheiro nos módulos do REAL.
B. Usar `Number` com cuidado
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Acessibilidade] Qual é o nível exigido?

A. O básico do beta (`project.md`) e o `accessibility-checklist.md` dos mockups:
- teclado em tudo;
- foco visível;
- rótulos visíveis;
- diálogos com foco preso, Esc e volta do foco;
- `aria-live` só nos marcos de 10 s e 5 s;
- contraste de pelo menos 4,5:1 nos dois temas, com o selo âmbar medido;
- cor sempre com texto ou ícone;
- sem rolagem lateral a partir de 320 px.

Isso é verificado com `jest-axe` nos testes de componente e com a navegação por teclado no e2e. Ainda não há portão de acessibilidade na CI (`team.md`); o WCAG 2.1 AA completo fica para antes de abrir ao público.
B. Só teclado
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Confiabilidade] Como a tela se comporta com falhas?

A. Seis comportamentos:
- **Canal ao vivo:** reconecta com a renovação que já existe, e a faixa relê a sessão ao reconectar e a cada troca de rota (BR3.5). Um evento perdido nunca deixa a pessoa sem a faixa por mais de uma troca de rota ou uma reconexão.
- **Envios:** as ações com dinheiro reenviam só o mesmo corpo com a mesma chave (BR6.2) e nunca reenviam sozinhas. O reenvio é sempre pelo botão "Tentar de novo".
- **Acompanhamento:** o estado do saque e da venda total é acompanhado por leitura a cada 2 s, até o estado final ou até 2 min. Depois disso, a tela mostra "Ainda processando; acompanhe em Ordens" e para de ler.
- **Erro de uma parte:** um erro numa parte da tela (por exemplo o P&L) não derruba as outras partes.
- **Saque:** nunca fica bloqueado por erro de outra leitura.
- **Sem o novo aceite:** a faixa não provoca o redirecionamento global (BR3.5).
B. Reenviar automaticamente
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Testes] O que cada camada prova?

A. A cobertura vem em três camadas:
- **Testes de componente** (Jest com React Testing Library e `jest-axe`): cobrem cada regra BR e cada cenário de S1 a S44 que não precisa de rede, com piso de 80% de linhas em `apps/web` e em `apps/admin`.
- **Testes do cliente da API:** cobrem a conversão para `bigint` e o tratamento de cada código de erro de BR8.8.
- **E2E (Playwright no `verify.sh`, validador local):**
  - um spec novo `e2e/real-ui` cobre a ativação pela tela, a subida para Automática total com consentimento, a volta para Assinada e a assinatura de uma ordem no modo Assinada pela faixa e pelo diálogo;
  - a carteira de teste (`e2e/support/test-wallet.ts`) ganha `signTransaction` só no código de teste;
  - a área REAL do administrador é coberta com liberar, o kill switch REAL e a compensação.

Nenhum teste usa devnet, mainnet nem carteira real.
B. Só testes de componente
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Observabilidade] O que a tela registra?

A. Nenhuma telemetria nova no navegador. As falhas mostram o `correlationId` do ProblemDetail para o suporte, e o servidor já registra e audita cada ação.

As mudanças de servidor do adendo seguem os padrões que já existem:
- a rota `/real/release` entra no registro de rotas e na regra de tenant;
- `serverTime` é preenchido pelo relógio injetável.
B. Telemetria de cliques
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Faixa em até 1 s, contagem com erro máximo de 1 s, "enviando" em até 100 ms; sem portão de desempenho [Q1].
- Transação devolvida sem alteração (teste byte a byte); nada do REAL guardado no navegador; sem biblioteca nova; CSP mantida; rede lida da API [Q2].
- Todo valor em `bigint`, com analisador e formatador próprios testados e lint contra ponto flutuante [Q3].
- Acessibilidade básica do beta, verificada com `jest-axe` e teclado no e2e; WCAG AA completo antes do público [Q4].
- Reconexão do canal com releitura, reenvio só manual com o mesmo corpo, acompanhamento por 2 min e saque nunca bloqueado [Q5].
- Componente e cliente com 80% por app, e e2e novo com ativação, troca de modo, assinatura e área do administrador no validador local [Q6].
- Sem telemetria nova; `correlationId` nos erros; a rota nova segue os padrões de rota e de tenant [Q7].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
