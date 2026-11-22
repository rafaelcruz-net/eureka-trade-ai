# Risco e justificativa da sequência — paper-real-unico

Conversa: português (Brasil). Data: 2026-11-21.

Um **Bolt** é uma passada de construção sobre uma parte do trabalho que termina em algo que roda. A sequência escolhida é B1 (U1) → B2 (U2) → B3 (U3) → B4 (U4). Este documento explica por quê.

## Sources

- `delivery-planning-questions.md`: Q1 (o que construir primeiro), Q2 (modelo de pontuação), Q6 (o que mais preocupa) e o resumo confirmado.
- `inception/units-generation/unit-of-work.md`: complexidade das Units e os riscos RK-1 a RK-8.
- `inception/units-generation/unit-of-work-dependency.md`: o grafo e as duas ordens topológicas válidas.
- `inception/requirements-analysis/requirements.md`: a restrição "ordem de entrega, com o risco primeiro" e o FR6 depois da Performance Validation do intent 261113; FR2.3, FR3.6, FR4.3, FR7, FR8 e FR6.
- `inception/domain-design/decisions.md`: ADR-006.
- `aidlc/spaces/default/memory/team.md`: Walking Skeleton e Testing Posture.
- `aidlc/spaces/default/memory/project.md` (Corrections de 2026-10-27 sobre o Delivery Planning: estratégia para as fases e WSJF dentro da fase; desempate próximo pela Unit que destrava o núcleo financeiro).

## Heurísticas usadas

A ordem combina três argumentos, cada um num trecho da sequência [Q1, Q2]:

1. **Esqueleto andante primeiro (Cockburn).** O esqueleto andante (walking skeleton) é a menor fatia de ponta a ponta que funciona de verdade. A U1 é essa fatia (`team.md`, "Fatia do intent `261121-paper-real-unico`"). Ela vem primeiro porque não depende de nenhuma Unit e prova, antes de o cálculo financeiro mudar, que um bot PAPER e um bot REAL convivem na mesma tela sem nenhum caminho que some os modos.
2. **Risco primeiro.** A U2 é o núcleo financeiro do REAL (rateio, custo médio único, conferência exata com o ledger) e a maior preocupação do responsável [Q6]. Ela vem logo depois do esqueleto, com os testes escritos antes do código, e tem um ponto de controle interno que dá um sinal cedo de atraso (RK-8). Ela também é obrigatória pelo grafo: a U3 e a U4 dependem dela.
3. **WSJF só para U3 contra U4.** O **WSJF** (Weighted Shortest Job First) ordena o trabalho pelo custo do atraso dividido pelo tamanho. A heurística é a do SAFe WSJF, que deriva do CD3 (Cost of Delay Divided by Duration) de Reinertsen. Ela é usada só para ordenar a U3 e a U4, as duas Units que o grafo deixa em qualquer ordem depois da U2. A ordem U1 → U2 é fixada pelo esqueleto e pelo grafo, não pela pontuação.

Isso segue a correção do `project.md`: a estratégia define as fases (esqueleto, depois risco) e o WSJF ordena dentro da fase que sobra.

## Pontuação WSJF de U3 e U4

Fórmula: WSJF = (valor para o usuário e o negócio + urgência + redução de risco ou abertura de oportunidade) ÷ tamanho. Pesos iguais nos três termos do numerador [Q2]. Escala de Fibonacci modificada (1, 2, 3, 5, 8, 13, 20), relativa entre as duas Units.

| Unit | Valor | Urgência | Redução de risco | Custo do atraso | Tamanho | WSJF |
|---|---|---|---|---|---|---|
| U3 `u3-pnl-views` | 8 | 5 | 3 | 16 | 5 (M) | **3,2** |
| U4 `u4-own-quantity-cycle` | 5 | 1 | 8 | 14 | 5 (M) | **2,8** |

Justificativa de cada nota:

- **U3, valor 8.** São as telas que a pessoa usa para entender o P&L: filtro (FR2.3), detalhe com a origem (FR3.6), painel (FR4.3), períodos (FR7) e comparação (FR8). Elas entregam ao usuário o rateio que a U2 calcula.
- **U3, urgência 5.** A U1 e a U2 podem sair para um ambiente com usuários depois do B2; sem a U3, o usuário vê os blocos, mas não vê a origem das parcelas rateadas nem escolhe o período. Cada semana sem a U3 é uma semana de P&L REAL difícil de explicar.
- **U3, redução de risco 3.** Exercita de ponta a ponta o contrato do período e das linhas da U2, mas o risco dela é baixo (telas, test-after).
- **U4, valor 5.** Corrige o comportamento do ciclo REAL (FR6), mas sai com a configuração desligada; o valor só chega ao usuário quando a configuração for ligada numa implantação separada.
- **U4, urgência 1.** A U4 não pode começar antes de a Performance Validation do intent 261113 terminar (ADR-006). Enquanto esse bloqueio existe, esperar não custa nada a mais: o atraso não tem custo próprio. O rateio do excedente da U2 (FR3.4) mantém o P&L correto enquanto a U4 não entra.
- **U4, redução de risco 8.** Tira o risco de um bot REAL vender a parte de outro, uma das três preocupações do responsável [Q6].
- **Tamanho.** As duas são M no `unit-of-work.md`. A U4 é pequena em código, mas tem risco alto; o risco entra no numerador, não no tamanho.

**Resultado:** U3 (3,2) antes de U4 (2,8). B3 = U3 e B4 = U4.

**Sensibilidade.** Se o bloqueio externo não existisse e a urgência da U4 subisse para 3, as duas empatariam em 3,2. O desempate continuaria a favor da U3, porque a U4 depende de uma liberação externa e só entrega valor depois de uma implantação separada. Nenhuma das duas destrava o núcleo financeiro (isso já é a U2), então a regra de desempate do `project.md` não muda a ordem. Além da pontuação, a U4 tem o portão externo: mesmo que a nota a pusesse primeiro, ela esperaria a Performance Validation.

## Conferência com o grafo

- Grafo (`unit-of-work-dependency.md`): U2 depende de U1; U3 depende de U2; U4 depende de U2; U3 e U4 não dependem uma da outra.
- Sequência escolhida: U1 → U2 → U3 → U4. É uma das duas ordens topológicas válidas (a outra é U1 → U2 → U4 → U3).
- **Desvio da ordem topológica: nenhum.**
- O bloco `yaml` do Units Generation, que o runtime usa, já tem a U1 como primeira Unit. Este plano não muda o bloco.

## Riscos que a sequência trata

| Risco | Como a sequência trata |
|---|---|
| RK-1: na U1, a soma dos bots REAL não fecha com o total REAL até a U2 | A U2 vem logo depois do esqueleto, e a U1 não é implantada sem a U2. O spec da U1 usa só compras simples. |
| RK-2 e RK-3: a semente de liberação ou o relógio de teste afetam a produção | Tratados no B1, antes de qualquer outra Unit, com o teste de guarda da configuração de produção. |
| RK-4: valores do spec da U1 mudam com o arredondamento da U2 | O spec da U1 não confere valores que dependam do arredondamento; a regra única é fixada no Functional Design da U2. |
| RK-5: conflito de arquivos com o intent 261113 | Bolts em série e testes do 261113 verdes em cada commit; a U4, que mais toca o ciclo REAL, vem por último e depois da Performance Validation. |
| RK-6: U3 e U4 mudam a lista `/bots` | Em série, a U4 integra a mudança da U3 e mantém os testes da lista verdes. |
| RK-7: Performance Validation do 261113 atrasa | A U4 é o último Bolt, sai desligada, e a U3 não depende dela. |
| RK-8: a U2 (XL) atrasa e segura U3 e U4 | Ponto de controle interno e divisão da U2 no corte da revisão se ele atrasar. |

## Assumptions & Open Questions

- [assumption] As notas do WSJF são relativas entre a U3 e a U4 e foram dadas por este estágio a partir dos requisitos e das respostas Q1, Q2 e Q6; o responsável pode ajustá-las no gate. A ordem só muda se a U4 passar a U3 com folga e a Performance Validation do 261113 já tiver terminado.
- [assumption] A urgência 1 da U4 vale enquanto a Performance Validation do 261113 não termina; se ela terminar antes do fim do B2, a pontuação é refeita com urgência 3 (empate, desempate a favor da U3, como acima).
