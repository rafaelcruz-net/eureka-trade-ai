# Requisitos de escalabilidade — U8 Administração e feedback

Base: NFR2 de `requirements.md` (20 convidados e 60 bots ativos) e as respostas Q1 e Q6.

## Requisitos

| ID | Requisito | Meta e verificação | Origem |
|---|---|---|---|
| NFR2.4 | Capacidade do beta | O kill switch atende 60 bots ativos com as metas do NFR2.1 e do NFR2.2. A aplicação é feita pela U5 em lotes, e a U8 não carrega a lista de bots | NFR2, C16 |
| NFR2.5 | Volume de feedback | Até 200 comentários por hora (20 pessoas × 10). Com retenção de 180 dias, o teto teórico é cerca de 864 mil linhas; o uso esperado do beta é muito menor. A lista do administrador é paginada por cursor (máximo de 100 por página) e usa índice por `createdAt` | Q6, Q8 do desenho |
| NFR2.6 | Expurgo em lotes | O expurgo apaga em lotes de até 1.000 linhas por transação, para não segurar travas longas | BR6.7 |
| NFR2.7 | Uma execução por vez | O trabalho de reconciliação e o expurgo usam trava distribuída, com uma execução por vez em todos os processos. Só o processo de API roda esses trabalhos | BR2.5, BR6.7 |

## Fora do escopo

- Nenhuma meta de escala além do beta (NFR2). Com mais convidados, o NFR Design e os testes de integração precisam ser refeitos.
- O app do administrador tem poucos usuários (os administradores configurados), sem meta própria de concorrência além do NFR3.1.
