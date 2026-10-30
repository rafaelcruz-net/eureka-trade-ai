# Personas — MemeTrade Analytics (beta PAPER por convite)

Base: público confirmado no [Intent Statement](../../ideation/intent-capture/intent-statement.md) (traders convidados no Brasil, já familiarizados com Solana e carteiras), papéis de [requirements.md](../requirements-analysis/requirements.md) (FR2.2) e respostas de [user-stories-questions.md](user-stories-questions.md) (Q1). As dores e os objetivos abaixo são hipóteses a validar nas entrevistas do discovery (FR14.2), não fatos observados.

## P1 — Bruno, trader convidado experiente (principal)

| Campo | Descrição |
|---|---|
| Papel | Usuário beta |
| Contexto | Opera memecoins de Solana com frequência, usa carteira Phantom ou Solflare e mais de uma ferramenta de descoberta ao mesmo tempo. |
| Objetivos | Encontrar tokens novos com dados confiáveis antes de agir; testar regras de entrada e saída sem arriscar dinheiro; conferir se os resultados simulados batem com as operações. |
| Dores (hipótese) | Alternar entre várias ferramentas; não saber de onde vem um dado nem se está atrasado; resultados de simulação que não explicam custos e slippage. |
| Conforto com tecnologia | Alto |
| Frequência de uso | Diária, várias sessões curtas |
| O que o faz confiar | Fonte e horário visíveis em cada número, `unknown` em vez de zero, P&L reconciliável |
| Fluxos principais | Descobrir e avaliar (F2), acompanhar o bot (F4), conferir P&L (F5) |
| Ambiente de uso | Computador, navegador com extensão Phantom ou Solflare, interface em pt-BR, tema escuro |

## P2 — Camila, trader convidada iniciante em automação

| Campo | Descrição |
|---|---|
| Papel | Usuária beta |
| Contexto | Conhece Solana e carteiras e já negociou manualmente, mas nunca configurou um bot nem limites de risco. |
| Objetivos | Entender por que um token é considerado arriscado; experimentar uma estratégia pronta com limites seguros; aprender com resultados simulados sem perder dinheiro. |
| Dores (hipótese) | Termos técnicos sem explicação; medo de configurar algo errado; dificuldade em entender o motivo de uma decisão automática. |
| Conforto com tecnologia | Médio |
| Frequência de uso | Algumas vezes por semana |
| O que a faz confiar | Estratégias modelo com valores padrão, explicação de cada decisão em linguagem simples, rótulo claro de modo simulado e pausa sempre à mão |
| Fluxos principais | Criar bot (F3), controlar o bot (F4), entender o risco (US3.1, US3.2) |
| Ambiente de uso | Computador, navegador com extensão Phantom ou Solflare, interface em pt-BR, tema escuro |
| Dores ligadas a critérios | Termos sem explicação → CT-UX-1, AC4.2.1; medo de configurar errado → AC4.2.1, AC4.3.1; motivo de decisão → AC4.5.1 (frase simples) |

## P3 — Administrador do beta (o responsável do projeto)

| Campo | Descrição |
|---|---|
| Papel | Administrador |
| Contexto | Conduz o projeto sozinho, decide quem entra no beta e responde a incidentes. |
| Objetivos | Convidar e revogar participantes; parar todos os bots de uma vez em um incidente; ver o que aconteceu sem acessar dados além do necessário. |
| Dores (hipótese) | Falta de visibilidade sobre o estado do sistema; medo de um defeito multiplicar operações simuladas; risco de ver dados pessoais além do necessário. |
| Conforto com tecnologia | Alto |
| Frequência de uso | Sob demanda (convites e incidentes) |
| O que o faz confiar | Kill switch global imediato, trilha de auditoria completa, acesso restrito a metadados mínimos |
| Fluxos principais | Convites (US7.1), kill switch (US7.2), estado do sistema (US7.4); a área administrativa não tem wireframe e será esboçada no refinamento dos mockups |

## Prioridade e relações

| Prioridade | Persona | Relação |
|---|---|---|
| 1 | P1 Bruno | Valida a proposta de valor central (descoberta e avaliação com dados transparentes). |
| 2 | P2 Camila | Valida se estratégias modelo e explicações tornam a automação compreensível. |
| 3 | P3 Administrador | Habilita o beta (convites) e protege os convidados (kill switch, auditoria). |
