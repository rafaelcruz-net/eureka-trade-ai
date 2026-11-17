# Especificação de infraestrutura — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes:
- `construction/u6-real-modes-ui/nfr-design/` (componentes, canal no layout raiz, adendo do servidor);
- `infrastructure-design-questions.md` (Q1 a Q4);
- os arquivos atuais: `docker-compose.yml`, `infra/docker-compose.real.yml`, `scripts/verify.sh`, `.github/workflows/ci.yml` e `infra/terraform`.

## Compute, rede e segredos [Q1]

Nada muda:
- nenhum serviço, contêiner, porta, segredo ou variável nova;
- nenhuma mudança no Compose nem no Terraform;
- web e admin continuam com as imagens e a configuração atuais (`API_ORIGIN` do lado do servidor, sem `NEXT_PUBLIC_*` nova).

Na API, as mudanças são só de código:
- a rota `GET /api/v1/real/release`;
- `serverTime` na sessão de assinatura;
- `capSetBy` e `release` na leitura da carteira;
- `PATCH` no ramo com token do `GuestCorsPolicy`.

A origem do web já está na lista do CORS.

## Banco

Sem migração. A leitura da liberação usa as tabelas da U5 (`real_release`) pela M1.

## Local e ponta a ponta [Q2] (ajustado na revisão, iteração 1)

### O que o ambiente local permite

Conferido no código:
- **Conta:** o `RealTestSeeder` libera só a conta de fumaça (`smoke.beta-wallet-address`). Uma liberação feita pelo administrador cai na quarentena de 24 h.
- **Endereço da carteira do bot:** vem da primeira `CreateKey` do `kms-sim` com a seed fixa. Só esse primeiro endereço casa com a Jupiter e as contas gravadas.
- **Compra:** a `RecordedSwapStrategy` compra uma vez por bot, só o mint gravado, e o `real-swap` já gasta essa compra.

Por isso o e2e da U6 usa só a conta de fumaça e a carteira do bot que já existe. Nada muda no Compose, no seeder nem nas variáveis.

### Ativação pela tela nova

O spec `e2e/real-bot-wallet` (U1) ativava pela tela mínima `/real/carteira`, que agora redireciona. Ele passa a ativar pela `/real/ativacao`, com o teclado:
1. aceite da v3;
2. teto e consentimento;
3. endereço com "Copiar".

Esse é o e2e da ativação (R1). O resto do spec (depósito, saldo e saque) continua igual, agora pela `/real/deposito-saque`.

### Spec novo `apps/web/e2e/real-ui/`

Ele roda depois de `e2e/real-ops`. Usa a conta de fumaça, `workers: 1` e `installTestWallet`, que já existe, nas portas 3000 e 3001. A função `postWithinLoginLimit` e a espera de `LOGIN_WINDOW_MS`, hoje locais do `bot-wallet.spec.ts`, são extraídas para `e2e/support/login-limit.ts`, e os dois specs passam a usá-las. As variáveis são as que o `verify.sh` já passa: `SMOKE_WALLET_FILE`, `SMOKE_ADMIN_WALLET_FILE`, `ADMIN_WALLET_ADDRESSES`, `API_BASE_URL`, `ADMIN_BASE_URL` e `SOLANA_RPC_URL`.

**Estado de entrada.** Vem do `real-swap` e do `real-ops`: carteira ativa em AUTO_FULL, bot parado, divergência resolvida e kill switch desligado.

**`beforeAll`.** Confere pela API do administrador que o kill switch está desligado e que não há divergência aberta. Se não estiver assim, falha com a mensagem do estado encontrado; o spec nunca corrige esse estado sozinho.

**Cenários:**
1. **Visão geral e teto.**
   - A tela mostra o saldo, o P&L REAL separado e a última reconciliação.
   - Baixar o teto vale direto (`PATCH`).
   - Subir de volta pede o consentimento `RAISE_CAP`, assinado pela carteira de teste.
   - Fim: o mesmo teto da entrada.
2. **Troca de modo.**
   - Voltar para Assinada pede só a confirmação.
   - Subir para Automática total pede o consentimento assinado.
   - Fim: AUTO_FULL, como na entrada.
3. **Ordens.**
   - A lista mostra a ordem confirmada do `real-swap`, com o selo "Confirmada".
   - A rede local mostra a assinatura abreviada, sem link.
4. **Depósito e saque.** O destino aparece abreviado e sem edição, e o diálogo de confirmação mostra o valor e o destino. O spec só abre e cancela, sem sacar, porque o saque já é coberto pelo `real-bot-wallet`.
5. **Administrador** (porta 3001):
   - a lista mostra a conta de fumaça com a liberação "Ativa";
   - "Liberar" nessa conta, com motivo, mostra o texto de `RELEASE_EXISTS`;
   - o kill switch REAL liga e desliga com motivo, dentro de `try/finally`, que desliga pela API se o passo falhar;
   - as telas de divergências e de restauração abrem sem erro. A divergência resolvida pelo `real-ops` pode aparecer na lista, com o estado "Resolvida".
6. **Largura de 320 px.** As telas `/real`, `/real/ordens` e `/real/deposito-saque` não têm rolagem lateral.

**Estado de saída.** É igual ao de entrada: AUTO_FULL, o mesmo teto, kill switch desligado e nenhuma liberação nova.

### Assinatura no modo Assinada (limite do ambiente local)

Uma ordem proposta no modo Assinada e assinada pela faixa não dá para reproduzir no validador local:
- a única rota gravada já foi usada pelo `real-swap`;
- uma ordem nova exigiria outra gravação de rota.

A assinatura é provada em duas camadas:
- **Teste de componente:** cobre o fluxo completo da faixa e do diálogo com uma carteira falsa. A transação dada e a recebida são comparadas byte a byte, e o teste passa por todos os desfechos (aceita, expirada, recusada, transação que não confere, recusa da política).
- **Testes de integração da U4:** cobrem o servidor da sessão de assinatura, que já existem.

Isso muda o U6-NFR8.5, que dizia "assinatura pela faixa no e2e". O ajuste está registrado em `nfr-requirements/tech-stack-decisions.md`.

### Carteira de teste

A carteira de teste (`e2e/support/test-wallet.ts`) passa a declarar `solana:signTransaction` na lista de features da conta e devolve a transação serializada inteira. Isso fica só no código de teste. Um teste em `e2e/support/test-wallet.test.ts`, que roda no Jest do web, assina uma transação de exemplo com a carteira de teste e confere a assinatura Ed25519 sobre a mensagem e o resto dos bytes sem alteração.

## CI [Q3]

O `ci.yml` não muda:
- os jobs `frontend`, `admin` e `contract` já rodam lint, tipos, Jest com o piso de 80% e `check:api`;
- o `backend` roda os testes do adendo;
- o `smoke` roda o `verify.sh`.

O texto pronto para o responsável, no `verify.sh`, acrescenta `e2e/real-ui` depois de `e2e/real-ops`:

```bash
    e2e/reacceptance e2e/data-requests e2e/anonymization e2e/real-bot-wallet e2e/real-swap e2e/real-ops e2e/real-ui
```

## Implantação e rollback [Q4]

**Ordem:**
1. API (o contrato só ganha campos e uma rota);
2. web e admin.

**Depois de cada etapa:** o smoke (`verify.sh` no ambiente, ou o smoke de implantação quando houver ambiente na nuvem) precisa passar antes da próxima etapa (`team.md`).

**Rollback:** voltar as imagens na ordem inversa, primeiro web e admin, depois a API. Não há migração para desfazer. O saque continua disponível o tempo todo.

Se só a API voltar e o web novo ficar no ar:
- `/real/release` responde `404` e o web mostra "O REAL ainda não está disponível" (BR1.1), sem nenhuma ação;
- sem `serverTime`, a contagem usa o relógio do navegador (BR3.2);
- o saque funciona, porque usa rotas que já existiam.

Mesmo assim, a ordem recomendada é voltar o web primeiro.

## Custo de CI

O `real-ui` roda duas vezes no push em `main`, uma no job `smoke` e outra no job `e2e`, como os outros specs. A estimativa é de 2 a 3 minutos a mais em cada um. A frequência é revista com dados de consumo, como o `team.md` prevê.
