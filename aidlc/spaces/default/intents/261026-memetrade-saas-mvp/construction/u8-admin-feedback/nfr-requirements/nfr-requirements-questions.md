# Perguntas de requisitos não funcionais — U8 Administração e feedback

Unit `u8-admin-feedback`. Base: [desenho funcional](../functional-design/functional-spec.md), requisitos NFR1 a NFR14 e contratos C6, C8 e C16.

Já valem sem pergunta:
- NFR3 (p95 ≤ 2 s nas telas, com 20 usuários).
- NFR5 (0 falhas nos testes de tenant).
- NFR6 (nonce de uso único com expiração ≤ 5 min).
- NFR7 (endereço mascarado em logs).
- NFR9 (métrica de saúde e de erro por componente, `correlationId`).
- NFR11 (teclado, foco e rótulos).
- NFR12 (80% de cobertura).
- NFR13 (Windows e Linux).
- NFR14 (pt-BR, tema escuro).

## Q1. [Desempenho] Quanto tempo o kill switch pode levar para pausar todos os bots?

A capacidade do beta é de 60 bots ativos (NFR2). A gravação do estado ON já impede novas operações, porque o ciclo confere o estado da U5 (AC7.2.2).

A. A resposta do `PUT ON` com 60 bots ativos sai em até 5 s (p95). Todos os bots ficam com o motivo SYSTEM em até 35 s no pior caso: a resposta mais uma execução do trabalho de reconciliação. A espera pela trava de aplicação tem limite de 10 s; passou disso, a requisição responde 200 com `reconcileNeeded = true` e o trabalho conclui.
B. Sem meta numérica, só a garantia de que nenhuma operação nova é criada depois do ON.
X. Other (please specify)

[Answer]: A

## Q2. [Desempenho] Tempo de resposta das telas administrativas

A. As telas do administrador seguem o NFR3 (p95 ≤ 2 s). O estado do sistema tem orçamento próprio de p95 ≤ 1 s: cada consulta às outras Units tem limite de 500 ms, e quem passar do limite aparece como `UNKNOWN`. O envio de feedback responde em p95 ≤ 500 ms.
B. Só o NFR3 geral, sem orçamento por consulta.
X. Other (please specify)

[Answer]: A

## Q3. [Segurança] Proteções do app do administrador

A. O app do administrador é uma imagem e um serviço próprios (`admin`, porta 3001) com política de segurança de conteúdo estrita (só scripts do próprio app e conexão só com a API). Um teste da CI confere que o pacote entregue ao convidado não contém nenhuma rota `/api/v1/admin` (ADR-009). A entrada administrativa usa o mesmo limite de tentativas da entrada do convidado.
B. Igual à A, sem a política de segurança de conteúdo agora.
X. Other (please specify)

[Answer]: A

## Q4. [Privacidade] Texto do feedback

O texto é livre e pode trazer dado pessoal ou um endereço de carteira digitado pela pessoa.

A. O texto nunca vai para log, métrica nem evento de auditoria; só o tamanho e a tela. Ele é guardado como foi escrito, o administrador vê o texto completo, e o texto é apagado em 180 dias. O aviso da tela S9 diz que o comentário é visível só para a administração.
B. Mascarar automaticamente padrões de carteira no texto antes de guardar.
X. Other (please specify)

[Answer]: A

## Q5. [Observabilidade] Métricas e alertas do kill switch

A. Métricas:
   - medidor do estado (0/1);
   - medidor de `pendingBots`;
   - contador de divergências encontradas pela reconciliação;
   - tempo da aplicação e da retirada da pausa;
   - contador de feedback recebido e recusado por motivo.

   Alertas no log de nível ERROR quando:
   - `reconcileNeeded` dura mais de 60 s;
   - `pendingBots > 0` dura mais de 2 minutos;
   - a reconciliação encontra divergência.

   A saúde da U8 fica degradada nesses casos.
B. Só as métricas, sem alertas.
X. Other (please specify)

[Answer]: A

## Q6. [Teste] Como medir as metas de desempenho

Os cenários de carga das Units anteriores ainda não rodaram e foram adiados.

A. Medir em teste de integração na CI, sem cenário de carga novo:
   - pausa de 60 bots ativos com tempo medido;
   - corrida entre ON e OFF, repetida 50 vezes;
   - queda simulada entre gravar o estado e chamar a U5;
   - 20 envios de feedback simultâneos.

   O `system-status` é medido no smoke.
B. Criar um cenário de carga `u8` além dos testes de integração.
X. Other (please specify)

[Answer]: A

## Q7. [Tecnologia] Organização do app do administrador

A. `apps/admin` é um app Next.js novo no mesmo workspace pnpm, com a mesma versão do Next.js, do TypeScript e do Node do `apps/web`, a mesma configuração de lint e formato, e tipos gerados só de `openapi-admin.yaml`. Não há pacote de componentes compartilhado por enquanto; o pouco que se repete é copiado.
B. Criar já um pacote de componentes compartilhado entre os dois apps.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

[Answer]: Looks correct
