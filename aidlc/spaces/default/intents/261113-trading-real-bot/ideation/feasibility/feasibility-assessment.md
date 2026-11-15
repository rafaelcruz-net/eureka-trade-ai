# Avaliação de viabilidade — Trading REAL

Conversa: português (Brasil). Base: `ideation/intent-capture/intent-statement.md` (problema, modos, fronteira e liberação) e `feasibility-questions.md` (Q1 a Q13, confirmadas em 2026-11-13).

## Veredito

**Viável, com condições.** O Trading REAL com a carteira do bot com teto pode ser construído sobre o produto atual sem programa on-chain próprio, porque a Jupiter monta a transação de swap e o sistema só precisa assinar pelo KMS e acompanhar o resultado (Q5). As condições que decidem a viabilidade de fato estão abaixo: suporte do KMS à assinatura que a Solana usa, um caminho de teste para os swaps fora da mainnet, uma fonte real de dados de mercado e a mudança de três regras do projeto pelo caminho registrado.

## Viabilidade técnica

| Tema | Avaliação | Condição | Fonte |
|---|---|---|---|
| Integração | Os módulos atuais (bots e decisão, barreira de risco, ledger e P&L, identidade, administração e painel) já têm fronteiras por interface; o REAL entra como novos adaptadores e um ledger REAL separado | REAL e PAPER estritamente separados (regra do projeto) | Q1 |
| Assinatura | A chave nasce e fica no KMS, sem exportação; o KMS assina e a produção nunca vê a chave | **Confirmar que o KMS escolhido assina Ed25519**, a curva das carteiras Solana; se não assinar, o desenho volta a esta etapa | Q3, Q4 |
| Montagem da transação | A Jupiter devolve a transação pronta; a produção assina e serializa com código próprio mínimo | A regra que proíbe o SDK Solana na produção continua; o SDK fica só nos testes | Q5 |
| Testes sem fundos | Devnet para carteira, KMS, assinatura, saque e reconciliação; validador local com contas da mainnet clonadas para os swaps | **A API de swap da Jupiter só opera na mainnet**; o validador clonado é o substituto, e a primeira execução real é na carteira do fundador com valores mínimos | Q2 |
| Dados de mercado | Hoje só existe o adaptador simulado | Uma fonte real mínima (CoinGecko paga, com atribuição) entra nesta intent; o preço de execução vem da cotação da Jupiter na hora; o REAL não liga com a fonte simulada | Q8 |
| RPC | Provedor gerenciado num plano de entrada, com reserva | Duas origens independentes; o RPC público só como último recurso | Q10 |
| Habilidades | Java/Spring e TypeScript/Next.js, já dominados; sem Rust | Conhecimento novo: formato de transação Solana e operação do KMS | Q13 |

## Exposição financeira

| Item | Valor | Fonte |
|---|---|---|
| Teto máximo por carteira do bot | 10 SOL, para o fundador e para cada convidado | Q6 |
| Teto de cada convidado | configurável no perfil do convidado, até o máximo | Q6 |
| Limite por ordem | até 10% do teto | Q7 |
| Perda máxima no dia | 20% do teto; o bot pausa até o dia seguinte | Q7 |
| Ordens por dia por bot | no máximo 20 | Q7 |
| Exposição agregada máxima no piloto | até 210 SOL (fundador + 20 convidados × 10 SOL), se todos usarem o teto máximo | Q6 e liberação da intenção |

A exposição agregada é a soma dos tetos; ela é o pior caso de um comprometimento das chaves das carteiras do bot, e por isso a guarda no KMS e o saque só para a carteira principal do usuário são as proteções centrais.

## Viabilidade organizacional e de custo

- **Orçamento:** até US$ 100 por mês para KMS, RPC e dados de mercado no piloto, revisado antes de abrir para convidados (Q11).
- **Prazo:** sem data fixa; 3 a 6 semanas de trabalho técnico (Q12).
- **Revisão:** o fundador é o único revisor (intent-statement); não há segunda pessoa para conferir o caminho que move dinheiro.

## Viabilidade regulatória

- **Custódia:** posição do fundador de que não é custódia, pela responsabilidade compartilhada; o parecer escrito do advogado fica recomendado antes de liberar convidados na mainnet (intent-statement, Q9).
- **Termos:** termos de uso atualizados para o REAL, com risco de perda total do valor depositado e sem promessa de lucro, e novo aceite antes de ativar a carteira do bot (Q9). O produto já tem o mecanismo de novo aceite (intent `261109-termos-lgpd`).
- **Fiscal:** registros fiscais exportáveis pelo usuário (Q9).

## Regras do projeto que esta intent muda

A intenção aprovada pede o REAL automático no beta, o que contradiz regras vigentes. A mudança é registrada pelo caminho do AI-DLC, com aprovação do fundador, antes da Construction:

| Regra hoje | Mudança proposta |
|---|---|
| `project.md`: REAL automático desligado até G2 e G3 e aprovações jurídicas | REAL liberado por decisão do fundador nos três modos; nenhum programa on-chain próprio; o vault do G2 continua bloqueado até G2/G3 |
| `team.md`: a autoaprovação do fundador não satisfaz G2/G3 | O fundador é o revisor dos gates do REAL nesta fase, com controles compensatórios (testes obrigatórios na CI, quarentena de 24 horas, tetos e limites) |
| `project.md`: nunca armazenar chave privada do usuário | Continua valendo para a carteira principal; a chave da carteira do bot é do sistema, gerada e mantida só dentro do KMS |
| `project.md`: nunca gerar chave secreta no código de produção | Continua valendo; a geração acontece dentro do KMS |
| `project.md`: nunca aprovação genérica irrestrita do usuário | Continua valendo; o modo Automática total é uma autorização limitada por teto, limites, tokens e revogável a qualquer hora |

O G2 do PRD (arquitetura de delegação e modelo de ameaças) **não é dispensado**: o modelo de ameaças da carteira do bot é feito no Domain Design desta intent. O que fica fora é o vault on-chain próprio.

## Recomendação

Seguir para o escopo, com três verificações antes do desenho: (1) suporte do KMS a Ed25519; (2) o validador local com contas clonadas executando um swap da Jupiter; (3) o plano da CoinGecko com licença comercial contratado.
