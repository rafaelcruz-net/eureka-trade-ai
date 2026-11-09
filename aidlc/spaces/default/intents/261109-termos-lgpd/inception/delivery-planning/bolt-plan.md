# Plano de Bolts — Termos de uso e LGPD

Conversa: português (Brasil). Um **Bolt** é uma passada de construção sobre uma parte do trabalho, do design ao código, que termina em algo que roda e é verificado.

Este plano ordena os Bolts que constroem as 5 Units de [unit-of-work.md](../units-generation/unit-of-work.md), respeitando o grafo de [unit-of-work-dependency.md](../units-generation/unit-of-work-dependency.md) e o mapa de [unit-of-work-story-map.md](../units-generation/unit-of-work-story-map.md). Também usa:
- os requisitos de [requirements.md](../requirements-analysis/requirements.md);
- as histórias de [stories.md](../user-stories/stories.md);
- os componentes de [components.md](../domain-design/components.md);
- os contratos de [contract-summary.md](../contract-design/contract-summary.md).

As escolhas vêm de [delivery-planning-questions.md](delivery-planning-questions.md).

## Regras comuns a todos os Bolts

- **Execução:** um Bolt de cada vez, com ponto de controle no fim de cada um (Q4). Commits direto em `main`, pedidos pelo responsável do projeto, com a CI verde.
- **Pronto comum:**
  - testes de unidade e integração (PostgreSQL em contêiner) e piso de 80% de linhas no backend e nos apps;
  - testes escritos antes do código nas regras de dados pessoais;
  - lint, verificação de tipos e contrato gerado verdes;
  - `bash scripts/verify.sh` com saída 0;
  - nenhum endereço completo, texto de pedido, mensagem ou assinatura em log.
- **Verificação de cada ponto de controle:** `bash scripts/verify.sh`, o comando já autorizado.

## B1 — U1 `u1-legal-documents` (fatia fina, ou *walking skeleton*: a versão mínima que atravessa o sistema inteiro)

- **Histórias:** US1.1, US1.2, US1.3, US2.1, US2.2, US2.4, US2.5, US2.6.
- **Prova de ponta a ponta:** `/privacidade` servida pela API (`GET /api/v1/legal/current`) a partir do catálogo v2 e mostrada sem login no app, com versão, resumo, aviso PAPER e rodapé.
- **Pronto:**
  - comum, mais um spec novo de ponta a ponta (`e2e/legal-documents`) no `verify.sh`;
  - ativação condicionada à revisão jurídica testada com três versões;
  - aprovação humana do esqueleto.
- **Hipótese:** a versão vigente sai do catálogo, chega ao navegador sem login e mostra "a definir" quando falta campo de implantação.
- **Demonstração:** abrir `/termos` e `/privacidade` sem sessão, no computador e no celular.
- **Pré-requisitos:** o responsável aplica no `ci.yml` as mudanças do `ci-config.md` (lacunas do app administrativo e do web) e acrescenta o spec novo ao `verify.sh`.

## B2 — U2 `u2-retention`

- **Histórias:** US1.4, US3.1, US3.2.
- **Pronto:**
  - comum;
  - teste de que login e criação de conta não deixam o endereço completo em `audit_event` nem em `audit_outbox`;
  - expurgos novos com relógio injetável e saúde `DEGRADED`;
  - teste de conformidade da tabela de retenção;
  - base do módulo `privacy` e a implementação da porta da tabela de retenção.
- **Hipótese:** a política passa a ler a tabela do catálogo de retenção, e qualquer prazo mudado sem mudar a política quebra a suíte.
- **Demonstração:** a seção de retenção de `/privacidade` vinda do catálogo; consulta à trilha sem a carteira completa.

## B3 — U3 `u3-reacceptance`

- **Histórias:** US4.1, US4.2, US2.3.
- **Pronto:**
  - comum;
  - teste obrigatório "versão nova exige novo aceite antes de qualquer outra rota";
  - `409 TERMS_VERSION_OUTDATED`;
  - lista de rotas liberadas testada;
  - convite com as duas caixas no smoke.
- **Hipótese:** publicar uma versão nova leva o convidado à T7, e os bots ativos continuam rodando.
- **Demonstração:** trocar a versão vigente em ambiente local e passar pelo novo aceite.

## B4 — U4 `u4-data-requests`

- **Histórias:** US5.1 a US5.9.
- **Pronto:**
  - comum;
  - isolamento por tenant e pessoa;
  - verificação por assinatura separada do login e do trading;
  - prazo vencido com evento, métrica e saúde;
  - expurgo de 12 meses;
  - prefixo dos pedidos na lista liberada do filtro;
  - specs de ponta a ponta na Conta e no administrador.
- **Hipótese:** um pedido feito pela Conta ou registrado por e-mail chega ao fim dentro do prazo, com a resposta visível e sem dado pessoal em log.
- **Demonstração:** criar, atender, recusar com motivo e verificar um pedido por e-mail.

## B5 — U5 `u5-anonymization`

- **Histórias:** US6.2, US6.1, US6.3.
- **Pronto:**
  - comum;
  - teste obrigatório "a anonimização não deixa a carteira em nenhuma tabela nem log";
  - testes de falha no meio, repetição e concorrência com login e ciclo de bot;
  - reconciliação do ledger sem divergência;
  - migração expand-contract do estado `ANONYMIZED` e da carteira sem endereço.
- **Hipótese:** a exclusão apaga tudo o que identifica a pessoa sem tocar no ledger.
- **Demonstração:** anonimizar uma conta de teste com bots e ordens, e conferir que ela não entra mais.
