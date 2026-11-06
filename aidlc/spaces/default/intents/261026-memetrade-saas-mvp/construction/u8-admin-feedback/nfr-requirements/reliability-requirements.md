# Requisitos de confiabilidade — U8 Administração e feedback

Base: [regras](../functional-design/rules.md) BR2.3 a BR2.8 e BR3.3, respostas Q1, Q5 e Q6, e NFR10 de `requirements.md`.

## Requisitos

| ID | Requisito | Meta e verificação | Origem |
|---|---|---|---|
| NFR10.1 | Convergência do kill switch | Depois de uma queda do processo entre gravar o estado e chamar a U5, ou de uma exceção nessa chamada, a U5 fica igual à U8 em até 35 s. Teste com queda simulada e relógio controlado | BR2.3, BR2.5, Q1 |
| NFR10.2 | Corrida entre ON e OFF | Com ON e OFF alternados em paralelo (50 repetições), o estado final da U5 (`systemPaused()`) é sempre igual ao estado da U8 depois da reconciliação, e nenhum bot fica com SYSTEM com a U8 em OFF | BR2.8, Q6 |
| NFR10.3 | Falhas parciais repetidas | Com falha injetada em parte dos bots, `pendingBots` reflete as falhas, e o trabalho repete até zerar | BR2.3 |
| NFR10.4 | Estado do sistema parcial | Com qualquer parte fora do ar ou lenta, a tela mostra as outras partes e a que falhou como `UNKNOWN` com o motivo, nunca zero. Teste para cada uma das seis partes | BR3.3, C-05 |
| NFR10.5 | Feedback preservado em falha | Em 400, 429, 503 ou falha de rede, o texto continua no campo, e um reenvio com a mesma chave não duplica. Teste de componente e smoke | BR7.3, AC6.1.3 |
| NFR10.6 | Disponibilidade | Sem meta numérica enquanto o beta roda só localmente. A meta é definida junto com a hospedagem (NFR10) | NFR10 |
| NFR11.1 | Acessibilidade básica | No app do administrador e no diálogo de feedback: tudo operável por teclado, foco visível, rótulos visíveis, diálogos que prendem o foco e o devolvem à origem, e estado dito em texto ou ícone além da cor ("Kill switch ACIONADO", estados de convite). Testes de componente e smoke só com teclado | NFR11, BR7.1–BR7.3 |
| NFR12.1 | Qualidade | 80% de cobertura de linhas no backend, no `apps/web` e no `apps/admin`, medidos separadamente. Testes obrigatórios de risco desta Unit: tenant (NFR5.1, NFR5.2), pausa pelo kill switch (NFR2.2, NFR10.1, NFR10.2), deduplicação (NFR4.2) e entrada sem reuso de assinatura (NFR6.1) | NFR12, team.md |
| NFR13.1 | Portabilidade | O serviço `admin` sobe no Docker Compose do Windows com o mesmo comando dos outros serviços, e a suíte passa na CI em Linux. O smoke `e2e/u8-admin-feedback` roda nos dois | NFR13 |
| NFR14.1 | Usabilidade | Todos os textos do app do administrador e do diálogo de feedback em pt-BR, tema escuro, só para computador | NFR14 |
| NFR10.7 | Migração só de acréscimo | A migração da U8 só acrescenta tabelas e colunas (e o papel ADMIN na restrição de sessão). A versão anterior roda sobre o banco migrado. Testado no `MigrationsIT` | team.md (rollback) |
