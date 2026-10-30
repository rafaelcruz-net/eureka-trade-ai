# Pergunta de definição de escopo — MemeTrade Analytics

## Contexto já confirmado

- Primeira disponibilização a convidados no Brasil: descoberta e avaliação, bots PAPER e P&L.
- A prioridade de construção começa por discovery e avaliação; automação vem depois.
- Trading REAL automatizado é uma evolução posterior, sujeita a validação de delegação/revogação, auditoria e avaliação jurídica.
- Cobrança pública fica para depois; exclusões do PRD permanecem fora.
- Não há data externa; estimativas serão revistas após Sprint 0.
- As estimativas regulatórias, de fornecedores e de capacidade permanecem condicionadas às pendências registradas em `../feasibility/feasibility-assessment.md` e `../feasibility/constraint-register.md`.

## Priorização para o primeiro beta convidado

### 1. Qual divisão entre Must Have e entregas posteriores devemos usar para o primeiro beta convidado?
O PRD marca como P0 tanto capacidades de PAPER quanto capacidades de REAL. A intenção aprovada separa o conteúdo do primeiro beta (PAPER e P&L) da evolução REAL posterior. Esta pergunta define a prioridade dentro do primeiro beta, sem alterar os gates já confirmados para REAL ou cobrança pública.

- [ ] **Must:** capacidades P0 compatíveis com o beta PAPER: acesso por convite e carteira vinculada, scanner/avaliação com origem e frescor dos dados, sinais explicáveis, bots PAPER com limites e pausa, P&L reconciliável; isolamento de contas, permissões e auditoria como base. Alertas avançados podem vir depois. Manter REAL e cobrança pública para etapas posteriores. (recomendado)
- [ ] **Must mais enxuto:** scanner e avaliação com dados rastreáveis, um fluxo utilizável de PAPER e P&L reconciliável; manter segurança de conta, isolamento e auditoria como base, e adiar filtros salvos, alertas e métricas avançadas.
- [ ] **Outra divisão:** indicar quais capacidades do primeiro beta devem ser Must, Should ou adiadas.
- [ ] X. Other (please specify)

[Answer]: Must mais enxuto.

## Consolidated Summary Confirmation

Confirma este resumo antes de eu gerar os artefatos?

- Looks correct
- Request changes

[Answer]: Looks correct

