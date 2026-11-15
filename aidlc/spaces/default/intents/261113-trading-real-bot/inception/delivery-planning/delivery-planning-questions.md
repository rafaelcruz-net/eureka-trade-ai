# Perguntas de Delivery Planning — Trading REAL

Conversa: português (Brasil). Base: `inception/units-generation/unit-of-work.md`, `inception/units-generation/unit-of-work-dependency.md`, `inception/units-generation/unit-of-work-story-map.md`, `inception/contract-design/contract-summary.md`, `inception/domain-design/components.md`, `inception/requirements-analysis/requirements.md`, `inception/user-stories/stories.md`, `inception/refined-mockups/mockups.md`, `ideation/scope-definition/intent-backlog.md` (fases e WSJF aprovados) e as regras de `team.md` e `project.md`.

Um **Bolt** é uma passada de construção sobre uma parte do trabalho que termina em algo que roda. O **esqueleto** é a primeira fatia fina que atravessa o sistema de ponta a ponta. **WSJF** é uma pontuação (valor + urgência + redução de risco, dividido pelo tamanho) que ordena o trabalho.

## Q1. [Estratégia] O que construir primeiro?

A. O esqueleto primeiro (U1, carteira do bot no validador local), começando pelas duas provas técnicas da Fase 0 (assinatura Ed25519 do KMS aceita pela rede de teste e swap da Jupiter no validador clonado), porque se uma delas falhar o desenho muda; depois risco primeiro, seguindo as fases do backlog
B. Valor primeiro (telas antes)
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Pontuação] Como ordenar?

A. As fases do backlog definem a ordem e o WSJF ordena dentro de cada fase, sempre respeitando o grafo de Units (regra de `project.md`)
B. Só WSJF
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Tamanho] Qual o tamanho de um Bolt?

A. Uma Unit por Bolt, 7 Bolts, como nas intents anteriores
B. Juntar Units pequenas (U2 com U3)
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Paralelismo] Bolts em paralelo?

A. Não: um por vez, em série (unit-major), como já está registrado e como trabalhamos sozinhos
B. U1 e U2 em paralelo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Ordem] Qual a ordem dos 7 Bolts?

A. U1 carteira (esqueleto) → U2 dados reais → U3 barreira → U4 execução → U5 operação (liberação, reconciliação, kill switch) → U6 telas e modos → U7 saídas e fiscal. A U5 vem antes da U6 porque segurança e controle da liberação precisam existir antes de qualquer tela que abra o REAL a convidados; o fundador opera o piloto com as telas mínimas da U1 e da U4 até a U6
B. U6 antes da U5
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Marcos] Quando cada rede é liberada?

A. Devnet e validador local desde o Bolt 1; mainnet com a carteira do fundador só depois da U5 e da U6 prontas e da quarentena de 24 h; convidados (até 20) depois de pelo menos 7 dias do fundador sem divergência e com o parecer de custódia decidido; a U7 (Should) não bloqueia os convidados
B. Mainnet do fundador logo depois da U4
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Dependências externas] O que pode nos segurar?

A. Conta AWS nova com KMS (bloqueia a prova técnica da Fase 0 e o TxSigner fora do local; o local usa KMS simulado); contrato do plano pago da CoinGecko (bloqueia a U2 em produção, não no local); provedores de RPC (dois, bloqueiam a mainnet); mudanças de regra do ADR-008 registradas pelo AI-DLC (bloqueiam o início da Construction); parecer de custódia (bloqueia só os convidados). Se a conta AWS atrasar, o Bolt 1 segue com KMS simulado e a prova Ed25519 vira condição de saída da U1 para a devnet
B. Nada externo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Maior preocupação] O que atacar cedo?

A. A política de assinatura do TxSigner (nenhuma transação fora da política é assinada) e a ordem ambígua (nunca duas transações): testes antes do código já na U1 e na U4, com casos de ataque (programa fora da lista, destino estranho, saída mínima baixa, ALT desconhecida, repetição)
B. Desempenho
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q9. [Responsável] Quem executa os Bolts?

A. Todos pelo aidlc-developer-agent (IA), com o fundador aprovando cada ponto de controle; não houve Team Formation neste escopo
B. Outro arranjo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Esqueleto primeiro (U1), começando pelas duas provas técnicas; depois risco primeiro [Q1].
- Fases do backlog com WSJF dentro de cada fase, respeitando o grafo [Q2].
- Uma Unit por Bolt, 7 Bolts, em série [Q3, Q4].
- Ordem U1 → U2 → U3 → U4 → U5 → U6 → U7 [Q5].
- Devnet desde o Bolt 1; mainnet do fundador depois de U5 e U6 com quarentena; convidados depois de 7 dias sem divergência e com o parecer de custódia; U7 não bloqueia [Q6].
- Dependências externas: conta AWS e KMS, CoinGecko, RPC, ADR-008, parecer de custódia [Q7].
- Política de assinatura e ordem ambígua com testes antes do código e casos de ataque [Q8].
- Execução pelo aidlc-developer-agent com aprovação do fundador [Q9].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
