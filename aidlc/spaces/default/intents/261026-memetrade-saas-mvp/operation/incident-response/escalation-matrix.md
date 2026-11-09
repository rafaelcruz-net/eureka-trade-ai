# Matriz de escalonamento

Conversa: português (Brasil). Resposta 2A. Os alarmes estão em `operation/observability-setup/alarms.md` e os painéis em `operation/observability-setup/dashboards.md`. As obrigações de segurança vêm dos `security-design.md` e a recuperação dos `reliability-design.md` das Units. Os pontos de contato da infraestrutura vêm dos `infrastructure-specification.md`.

## Plantão

| Papel | Quem | Disponibilidade | Contato |
|---|---|---|---|
| Plantão principal | responsável do projeto | horário combinado com os convidados do beta | e-mail e notificação no celular (SNS "Alta" na AWS) |
| Plantão secundário | **lacuna**: não há segunda pessoa | — | — |
| Fora do horário | ninguém; a contenção é o kill switch, ligado pelo responsável assim que vir o alarme | — | — |

## Escalonamento por tipo

| Tipo de incidente | Nível 1 | Nível 2 | Nível 3 |
|---|---|---|---|
| Infraestrutura (API, banco, contêiner) | responsável do projeto | suporte da AWS (plano a definir com a conta) | — |
| Dados financeiros simulados (ledger, risco, kill switch) | responsável do projeto | revisor independente (C-14), **quando nomeado** | — |
| Segurança sem dado pessoal (segredo, sessão) | responsável do projeto | suporte da AWS, se envolver a conta | — |
| Segurança com dado pessoal (acesso entre tenants, vazamento) | responsável do projeto | avaliação jurídica de LGPD registrada no PRD | ANPD e titulares, conforme a avaliação |
| Fornecedor externo (futuro: dados de mercado, pagamentos) | responsável do projeto | suporte do fornecedor | — |

## Prazos

| Severidade | Reconhecer | Primeira atualização aos convidados | Escalar para o nível 2 se não resolver em |
|---|---|---|---|
| P1 | 15 min | 30 min | 1 h |
| P2 | 15 min | 30 min | 4 h |
| P3 | mesmo dia | não se aplica | não se aplica |

## Lacunas registradas

- Não há plantão secundário. Um incidente fora do horário espera o responsável; o kill switch limita o efeito nos bots PAPER.
- O revisor independente (C-14) ainda não foi nomeado. É obrigatório antes de G2/G3 e de qualquer trading REAL.
- O plano de suporte da AWS e o canal com os convidados ainda não existem. Os dois são definidos antes do G1.
- Os contatos reais (e-mail, telefone) não ficam neste repositório. Ficam na configuração do SNS e do Incident Manager, quando existirem.
