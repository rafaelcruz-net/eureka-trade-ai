# Runbook — telas do REAL: estados, rollback e limite da assinatura no e2e

Intent `261113-trading-real-bot`, Unit U6 (`u6-real-modes-ui`). Fontes: `functional-design/rules.md` (BR1 a BR9 e o adendo de contrato), `nfr-design/logical-components.md`, `nfr-design/reliability-design.md` e `infrastructure-design/infrastructure-specification.md`.

As telas do REAL não guardam nada no navegador: nem no `localStorage`, nem no `sessionStorage`, nem em cookie, nem na URL, nem no console. Toda decisão sobre dinheiro, limite, modo, liberação e pausa continua no servidor (U1 a U5). A tela só mostra, recusa a forma errada antes de enviar e impede o envio duplo.

Regra de ouro: **o saque para a carteira principal registrada fica sempre visível e habilitado** em `/real/deposito-saque` — com o kill switch, com pausa, com o REAL desligado, sem liberação, com divergência e sem o novo aceite dos termos.

## 1. O que cada tela mostra em cada estado

| Situação | O que a pessoa vê | Onde |
|---|---|---|
| REAL desligado (`403 REAL_DISABLED` em `/real/release`) | o item "Carteira do bot" some do menu; `/real`, `/real/modo` e `/real/ordens` dizem "O REAL ainda não está disponível" | menu e telas do REAL |
| API antiga, sem `/real/release` (`404`) | o mesmo que o REAL desligado | menu e telas do REAL |
| Sem liberação (`NONE`), em quarentena ou bloqueada | a ativação mostra o estado da liberação em vez dos passos ("em quarentena até <hora>", "bloqueada: <motivo>") | `/real/ativacao` |
| Sem o aceite dos termos vigentes | a ativação começa no passo 1 (texto dos termos e a caixa); `/real`, `/real/modo` e `/real/ordens` levam ao novo aceite; o depósito e saque continua aberto com a faixa "Aceite os termos novos para voltar a operar; o saque continua disponível" | `/real/ativacao`, `/novo-aceite`, `/real/deposito-saque` |
| Sem carteira do bot ativa | `/real`, `/real/modo`, `/real/ordens` e `/real/deposito-saque` levam à ativação; `/real/carteira` (tela antiga da U1) leva a `/real` | redirecionamentos |
| REAL pausado | faixa com o motivo em texto (perda do dia, divergência, kill switch, restauração, administrador, serviço externo; um motivo novo aparece como "REAL pausado") e "O saque continua disponível." | `/real`, `/real/modo`, `/real/deposito-saque` |
| Kill switch REAL ligado | faixa "Kill switch acionado: novas ordens REAL estão bloqueadas; o saque continua disponível." | `/real/deposito-saque` |
| Divergência ou restauração | "Disponível para saque agora"; um saque acima do limite volta com "Agora o saque está limitado a <máximo> SOL…" | `/real/deposito-saque` |
| Modo Assinada com proposta | faixa "Ordem para assinar" com a contagem regressiva em todas as telas com sessão; "Ver" abre o diálogo | logo abaixo do cabeçalho |
| Carteira sem `solana:signTransaction` | "Sua carteira não assina transações; use Phantom, Solflare ou Backpack"; o diálogo não abre | faixa de assinatura |
| Qualquer erro do servidor | o texto do código (tabela BR8.8) ou o `detail` do ProblemDetail, com "Código para o suporte: <correlationId>" | onde o erro aconteceu |

Para o suporte: o `correlationId` mostrado na tela liga a reclamação ao log da API (a mesma regra de mascaramento de sempre). O navegador não registra nada do REAL.

### Administrador

A seção "REAL" do app administrativo (`/real`) tem Usuários (com o detalhe e as ações Liberar, Desligar REAL e Baixar teto), Tokens bloqueados, Kill switch REAL (tela própria, separada de "Kill switch", que é o do PAPER), Divergências (a compensação vem preenchida com a medida; a pessoa só confirma com o motivo) e Restauração. Toda ação pede um motivo de 1 a 500 caracteres e a confirmação, exceto o desbloqueio de token, cuja rota não tem corpo.

## 2. Rollback parcial

**Ordem de implantação:** primeiro a API (o contrato só ganha campos e uma rota), depois web e admin. O smoke precisa passar antes da próxima etapa.

**Rollback:** na ordem inversa — primeiro web e admin, depois a API. Não há migração para desfazer.

Se só a API voltar e o web novo ficar no ar:

| Efeito | Por quê |
|---|---|
| O item "Carteira do bot" some e as telas do REAL dizem "O REAL ainda não está disponível" | `/real/release` responde `404`, tratado como REAL indisponível (BR1.1) |
| A contagem da assinatura usa o relógio do navegador | a sessão não traz `serverTime` (BR3.2); o servidor continua decidindo a expiração |
| O saque continua funcionando | `/real/deposito-saque` usa só a leitura da carteira e as rotas de saque, que já existiam, e não lê a liberação |
| O app administrativo não consegue desligar o kill switch REAL nem revogar liberação pela tela | a API antiga não aceita `DELETE` no CORS do administrador; use a API diretamente (runbook `real-operations.md`, seção 3) até a API nova voltar |

Mesmo assim, a ordem recomendada é voltar o web e o admin primeiro. O rollback do REAL em si (desligar `memetrade.real.enabled` e `txsigner.real.enabled` ou ligar o kill switch REAL) segue o `real-operations.md`; o saque continua disponível o tempo todo.

## 3. Limite da assinatura no modo Assinada no e2e

Uma ordem proposta no modo Assinada e assinada pela faixa **não** é reproduzida no validador local:

- a única rota gravada da Jupiter já é usada pelo `real-swap`;
- uma ordem nova exigiria outra gravação de rota.

A assinatura é provada em duas camadas:

1. **Testes de componente** (`apps/web/src/features/real/components/SigningBanner.test.tsx`): a faixa e o diálogo com uma carteira falsa; a transação dada à carteira e a enviada ao servidor são comparadas byte a byte a partir do primeiro byte depois das assinaturas, e todos os desfechos são cobertos (aceita, expirada, recusada na carteira, recusa que falhou, transação que não confere, recusa da política, ordem fora de `QUOTED`, carteira sem `signTransaction`, falha de rede com "Tentar de novo").
2. **Testes de integração da U4** (`SigningSessionIT` e afins) no servidor da sessão de assinatura, mais o `SigningSessionServerTimeIT` da U6 para o `serverTime`.

O spec `apps/web/e2e/real-ui` cobre a visão geral e o teto, a troca de modo, as ordens, o depósito e saque, o administrador e a largura de 320 px. Ele confere no `beforeAll` o estado de entrada (carteira ativa em `AUTO_FULL`, kill switch desligado, nenhuma divergência aberta) e falha com o que encontrou, sem corrigir nada sozinho; no fim, o estado é o mesmo da entrada. O kill switch REAL é ligado e desligado dentro de `try/finally`, que o desliga pela API se o passo falhar.

## 4. Se algo der errado

| Sintoma | O que conferir |
|---|---|
| A faixa de assinatura não aparece no modo Assinada | o canal ao vivo pode estar fora: a faixa relê a sessão na próxima troca de rota e logo depois da reconexão; o aviso "atualização automática interrompida" aparece em "Avisos" |
| O menu não mostra "Carteira do bot" com o REAL ligado | a leitura do REAL é feita uma vez por sessão; sair e entrar de novo relê |
| O navegador recusa o `PATCH` do teto ou o `DELETE` do administrador (erro de CORS) | a API em execução é anterior à U6; implantar a API da U6 |
| O spec `real-ui` falha no `beforeAll` | o estado de entrada não é o esperado (kill switch ligado, divergência aberta, modo diferente de `AUTO_FULL`): corrija pelo runbook `real-operations.md` e rode de novo |
