# Requisitos de confiabilidade e acessibilidade — U3 `u3-reacceptance`

Conversa: português (Brasil). Fontes:
- `construction/u3-reacceptance/functional-design/` (BR3.1, BR3.3, BR3.7 a BR3.9);
- `inception/requirements-analysis/requirements.md` (NFR2, NFR4, NFR5);
- regra de acessibilidade básica do beta em `project.md`.

O NFR7 da inception (expurgos) não se aplica à U3.

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR5.1 | A migração `0013` só acrescenta ou relaxa:<ul><li>`ALTER COLUMN invite_id DROP NOT NULL`;</li><li>coluna `source` com padrão `INVITE`, `CHECK` dos valores e `CHECK` cruzado com `invite_id`;</li><li>restrição única nova `(user_id, terms_version)`.</li></ul>Nenhuma linha existente muda e nenhuma restrição existente é removida. `uq_terms_acceptance_invite` continua, e o nulo nela é permitido. Hoje cada pessoa tem um único aceite, o do convite, então a restrição nova não conflita com os dados existentes | teste que lê a migração: aceita `DROP NOT NULL` e falha com qualquer `UPDATE`, `DELETE`, `DROP TABLE`, `DROP COLUMN` ou `DROP CONSTRAINT` fora do rollback; o teste de integração aplica a migração sobre dados com aceites de convite |
| NFR2.5 | A trava falha fechada: com o banco indisponível, responde com o erro técnico existente (`503`) e nunca deixa a requisição passar sem conferir | teste de integração com o banco parado |
| NFR2.6 | Os bots PAPER ativos continuam no worker durante a trava; as rotas de bot do convidado recebem `403` | teste do ciclo do worker com uma pessoa desatualizada e teste de API das rotas de bot |
| NFR4.1 | As telas de convite e de novo aceite (T7) seguem a acessibilidade básica do beta: <ul><li>navegação só pelo teclado, em ordem, com foco visível;</li><li>"Pular para o conteúdo" como primeiro foco;</li><li>títulos h1 a h3 em ordem e regiões de página (`header`, `main`, `footer`);</li><li>rótulos visíveis nas caixas;</li><li>contraste legível no tema escuro;</li><li>cor sempre acompanhada de texto ou ícone;</li><li>mensagens de estado numa região `aria-live`.</li></ul> | teste de componente (ordem de foco, rótulos, títulos, regiões, `aria-live`) e spec de ponta a ponta com teclado; o contraste é conferido na revisão visual do Build and Test |
| NFR4.2 | Uma falha ao carregar os documentos ou ao aceitar mostra o erro em texto com "tentar de novo", e as caixas ficam desabilitadas | teste de componente |
