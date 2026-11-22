# Personas — paper-real-unico

Fontes:
- `user-stories-questions.md` (Q1: duas personas);
- `ideation/intent-capture/stakeholder-map.md`;
- `ideation/rough-mockups/` (`wireframes.md` e `user-flow.md`);
- `inception/requirements-analysis/requirements.md`;
- o mapa do código (`business-overview.md`, atores `BETA_USER` e `ADMIN`).

As duas personas são as do Q1. Os nomes são ilustrativos.

## P1 — Bia, a convidada do beta (principal)

| Campo | Descrição |
|---|---|
| **Papel** | Convidada do beta (`BETA_USER`). Entra por convite, com a carteira dela (Phantom ou Solflare), e usa só a mainnet. |
| **Contexto** | Opera tokens de memes na Solana. Já criou bots simulados (PAPER) no beta. Com a carteira do bot ativa e a liberação do REAL aprovada pelo fundador, quer testar a mesma estratégia com valores pequenos de dinheiro real. Usa o computador e o celular. Pode usar leitor de tela ou só o teclado. |
| **Frequência** | Várias vezes por dia enquanto os bots estão ativos; olha o painel e a tela de P&L. |
| **Conforto técnico** | Médio. Entende SOL, lamports, taxa de rede e P&L, mas não lê o ledger. |

**Objetivos**
1. Criar bots simulados e reais lado a lado, no mesmo ambiente, sem trocar de app nem de rede [Q1] (FR1, FR2).
2. Ver o P&L de cada bot na moeda do próprio modo (US$ no simulado, SOL no real) e o total de cada modo, sem que os modos se misturem (FR3, FR4).
3. Entender de onde veio cada parte do resultado de um bot real, inclusive quando uma venda foi feita pela liquidação ou por uma saída condicional (FR3.3, FR3.6).
4. Comparar o simulado com o real da mesma estratégia sem confundir os dois (FR8).

**Dores de hoje** (do mapa do código e do intent)
- O P&L REAL existe só para a pessoa inteira, não por bot. Ela não sabe qual bot real ganhou ou perdeu.
- A tela de criação não deixa escolher o modo: o bot REAL só nasce pela API.
- As vendas das saídas condicionais nem entram no P&L REAL, então o número que ela vê está errado.
- Os totais PAPER e REAL ficam em cartões diferentes, sem uma visão única.
- Ela tem medo de achar que o simulado garante o real.

**O que ela nunca pode ver**: um total que some US$ e SOL, um P&L real zerado quando falta preço, ou o P&L do bot de outra pessoa.

## P2 — Rafael, o fundador (secundária)

| Campo | Descrição |
|---|---|
| **Papel** | Fundador e responsável do projeto. Decide sozinho o escopo e a prioridade [stakeholder-map Q6]. É o revisor único dos gates do REAL e o único on-call. |
| **Contexto** | Usa o produto como um convidado, com a própria carteira na mainnet. Testa as mudanças primeiro na devnet, que é só dele. Precisa confiar que o P&L mostrado bate com o ledger antes de abrir o REAL aos até 20 convidados. |
| **Frequência** | Diária durante o beta; em toda implantação, confere o P&L e a reconciliação. |
| **Conforto técnico** | Alto. Lê o ledger, as assinaturas na rede e os testes. |

**Objetivos**
1. Manter os usuários num ambiente só, a mainnet, sem serviço AWS novo e dentro do orçamento de US$ 350 por conta [stakeholder-map Q1] (NFR8).
2. Conferir, por igualdade exata em lamports, que a soma do P&L dos bots e da linha "Sem bot" fecha com o ledger de cada modo, em qualquer período (FR5.3).
3. Garantir que o contrato da API continue estável para os clientes atuais (`mode == market`) (FR2.4).
4. Ligar a venda só da própria quantidade (FR6) só depois da Performance Validation do intent 261113, com aviso aos convidados [stakeholder-map Q7].

**Dores de hoje**
- Não consegue dizer qual bot real gerou cada resultado.
- Dois cálculos de custo médio REAL (P&L e `RealPositions`) podem discordar.
- Uma venda sem bot ("vender tudo e sacar", condicional) não tem dono, e ele não tem como fechar o P&L por bot com o ledger.

## Relação entre as personas e prioridade

| Prioridade | Persona | Por quê |
|---|---|---|
| 1 | Bia, convidada do beta | É quem usa a visão única todos os dias e recebe o aviso quando o comportamento muda [stakeholder-map]. A maior parte das histórias é dela. |
| 2 | Rafael, fundador | Decide e confere. Também é usuário (com a carteira dele), mas as histórias que só ele pede são de conferência com o ledger, de contrato, de custo e de desempenho. |

- O fundador libera a convidada para o REAL (liberação com quarentena de 24 horas). Sem essa liberação, a opção "Real" fica desabilitada para ela (FR1.2).
- O fundador recebe o feedback dos convidados, que influencia a prioridade, mas não decide [stakeholder-map Q6].
- O fundador comunica aos convidados, na tela e nos termos, quando o comportamento muda (FR6.2) [stakeholder-map Q7].

## Assumptions & Open Questions

None.
