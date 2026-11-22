# Mapa de dependências externas — paper-real-unico

Conversa: português (Brasil). Data: 2026-11-21.

Um **Bolt** é uma passada de construção sobre uma parte do trabalho que termina em algo que roda; este intent tem quatro, B1 a B4, um por Unit (B1 = U1, o esqueleto andante, isto é, a primeira fatia de ponta a ponta que funciona de verdade; B2 = U2; B3 = U3; B4 = U4). Este mapa lista o que está fora do trabalho do assistente e pode segurar um Bolt: quem é o dono, quanto tempo leva, que Bolt bloqueia e o que fazer se atrasar. Nenhuma API de terceiros nova entra neste intent [Q5].

## Sources

- `delivery-planning-questions.md`: Q5 (dependências externas), Q7 (comando de verificação) e o resumo confirmado.
- `bolt-plan.md` deste estágio.
- `inception/requirements-analysis/requirements.md`: restrição de ordem de entrega (FR6 depois da Performance Validation do 261113) e NFR1 (p95 medido no cenário de carga da CI).
- `inception/units-generation/unit-of-work.md`: notas da U1 (spec novo), da U2 (NFR1) e da U4 (Performance Validation); RK-7.
- `inception/domain-design/decisions.md`: ADR-006.
- `aidlc/spaces/default/intents/261113-trading-real-bot/aidlc-state.md`: estágio atual `performance-validation`, em andamento.
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/performance-validation/test-results.md` ("Pendente: aguardando o responsável aplicar o texto do load-test.yml") e `load-test-plan.md` (seção 8.1, texto pronto; seção 8.2, disparo).
- `.github/workflows/load-test.yml`: as opções atuais do cenário (u1, u4, u3, growth, u7, u7-jobs, u6, u5, legal, reacceptance, data-requests); nenhuma do P&L REAL por bot.
- `scripts/verify.sh`: a lista de specs do Playwright que o script roda.
- `aidlc/spaces/default/memory/team.md`: Way of Working (só o responsável altera `.github/workflows/`; hospedagem privada, minutos do Actions na cota) e Walking Skeleton (spec novo acrescentado pelo responsável; `verify-checkpoint.sh` com a CI como prova).

## Resumo

| # | Dependência | Dono | Prazo esperado | Bloqueia | Plano se atrasar |
|---|---|---|---|---|---|
| D1 | Performance Validation do intent 261113 | fundador | aplicar o texto: uma sessão do fundador; depois, uma rodada manual do workflow e o gate do estágio | início do B4 | B4 espera; a U4 não é construída e o ciclo vende como hoje; o rateio do excedente da U2 mantém o P&L correto |
| D2 | Spec novo da fatia no `scripts/verify.sh` | fundador | uma sessão do fundador, depois que a Code Generation da U1 entregar o texto | ponto de controle do B1 | o ponto de controle do B1 espera; B2 não começa |
| D3 | Cota do GitHub Actions | fundador | imediato quando há saldo; um ciclo de cobrança quando a cota acaba | ponto de controle de qualquer Bolt quando o `verify.sh` local não aguenta | `verify.sh` local primeiro; rever a frequência dos jobs com dados de consumo |
| D4 | Possível opção de carga do NFR1 no `load-test.yml` | fundador | uma sessão do fundador, se o NFR Design da U2 pedir | evidência do NFR1 no ponto de controle do B2 | rodar o cenário pelo `scripts/load-test.sh` local e registrar como evidência provisória, com o responsável decidindo |

## D1 — Performance Validation do intent 261113

- **O que é.** O intent `261113-trading-real-bot` está no estágio `performance-validation`, em andamento. O resultado está pendente até o fundador aplicar o texto pronto da seção 8.1 do `load-test-plan.md` no `.github/workflows/load-test.yml` (opção de cenário `real`) e pedir o commit em `main`. Depois disso, o assistente dispara `gh workflow run load-test -f scenario=real`, analisa o relatório e o estágio passa pelo gate do fundador.
- **Dono.** O fundador: o `load-test.yml` é arquivo dele (`team.md`) e o gate do estágio é dele.
- **Prazo esperado.** Uma sessão do fundador para aplicar o texto; uma rodada manual do workflow; o gate do estágio no 261113.
- **Bloqueia.** O início do B4 (U4). A restrição de ordem de entrega do `requirements.md` e o ADR-006 pedem que o FR6 só entre depois da Performance Validation. B1, B2 e B3 não dependem dela.
- **Plano se atrasar.** O B4 espera, e o workflow para na U4 depois do ponto de controle do B3. Enquanto isso, o ciclo REAL continua vendendo a posição inteira do token, como hoje, e o excedente vendido por um bot é rateado entre os outros donos pela U2 (FR3.4), então o P&L continua fechando com o ledger. Mesmo depois da Performance Validation, a U4 sai com a configuração desligada; ligá-la é uma implantação auditada separada.

## D2 — Spec novo da fatia no `scripts/verify.sh`

- **O que é.** O spec de ponta a ponta do esqueleto (cria um bot PAPER e um bot REAL no validador local, faz uma compra em cada modo e confere que cada bloco mostra só o próprio modo e que os modos nunca são somados) precisa entrar na lista de specs do Playwright no `scripts/verify.sh`, que o `scripts/verify-checkpoint.sh` roda.
- **Dono.** O fundador acrescenta o spec ao `verify.sh` (`team.md`, Walking Skeleton). O assistente escreve o spec e entrega o texto pronto.
- **Pedido ao fundador.** Na Code Generation da U1, o assistente entrega: (1) o caminho do spec novo (sugestão: `e2e/paper-real-unico`); (2) a linha da lista de specs do `verify.sh` com o spec acrescentado no fim, depois de `e2e/real-v2-exits`; (3) as variáveis de ambiente novas que o spec precisar, se houver. O fundador aplica o texto e pede o commit em `main`. O texto pronto só vem na Code Generation, porque o nome e as variáveis dependem do código.
- **Prazo esperado.** Uma sessão do fundador, depois que a Code Generation da U1 entregar o texto.
- **Bloqueia.** O ponto de controle do B1 e, por consequência, o início do B2 (execução em série).
- **Plano se atrasar.** O ponto de controle do B1 espera. O spec pode ser rodado sozinho para revisão (`pnpm --dir apps/web exec playwright test <spec>`, com o ambiente do `verify.sh` de pé), mas isso não substitui o ponto de controle: o esqueleto só é aprovado com o `verify-checkpoint.sh` verde com o spec incluído.

## D3 — Cota do GitHub Actions

- **O que é.** O repositório é privado desde 2026-11-21, e os minutos do Actions contam na cota do plano (`team.md`). O `verify-checkpoint.sh` usa o run verde do `ci` do mesmo commit como prova quando o `verify.sh` local morre por falta de memória, e os pontos de controle só usam commit com a CI verde (`project.md`). Já houve, no intent 261113, um job `e2e` recusado pelo GitHub por cobrança.
- **Dono.** O fundador (plano e cobrança da conta do GitHub).
- **Prazo esperado.** Imediato enquanto há saldo; quando a cota ou o pagamento falham, até o fundador ajustar a cobrança ou até o próximo ciclo.
- **Bloqueia.** O ponto de controle de qualquer Bolt, quando a prova precisa vir da CI.
- **Plano se atrasar.** Rodar primeiro o `verify.sh` local (o `verify-checkpoint.sh` já faz isso); se a cota apertar, rever a frequência dos jobs com dados de consumo (`team.md`). O piso de qualidade e a exigência de CI verde não são reduzidos.

## D4 — Possível opção de carga do NFR1 no `load-test.yml`

- **O que é.** O NFR1 pede o p95 de até 1 s do P&L por bot (10 bots, 2 anos) medido no cenário de carga da CI. O `load-test.yml` hoje não tem uma opção para esse cenário. Se o NFR Design da U2 decidir que o cenário roda no workflow, a opção nova chega como texto pronto para o fundador, como no 261113.
- **Dono.** O fundador (`.github/workflows/` é arquivo dele).
- **Prazo esperado.** Uma sessão do fundador, se o NFR Design da U2 pedir.
- **Bloqueia.** A evidência do NFR1 no ponto de controle do B2.
- **Plano se atrasar.** Rodar o cenário pelo `scripts/load-test.sh` local e registrar o resultado como evidência provisória; o responsável decide se aceita ou se o ponto de controle espera.

## Fora do escopo dos Bolts

- A repetição do p95 do NFR1 na devnet (NFR1) depende de um ambiente na nuvem que ainda não existe (`team.md`, Deployment). É assunto dos estágios de operação, não bloqueia nenhum Bolt.

## Assumptions & Open Questions

- [assumption] D4 não estava nas respostas da Q5; ele vem do NFR1 e da lista atual de opções do `load-test.yml`, e só vira dependência real se o NFR Design da U2 pedir a opção nova no workflow.
- [assumption] Os prazos são estimativas qualitativas: as respostas confirmadas não dão números, e os passos dependem da agenda do fundador.
- [assumption] A recusa por cobrança no 261113 vem da nota de retomada da volta 1 do Build and Test (2026-11-19).
