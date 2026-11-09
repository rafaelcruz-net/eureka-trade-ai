# Estratégia de implantação

Conversa: português (Brasil). Respostas 1A, 2A e 3A de `deployment-pipeline-questions.md`.

## Hoje: recreate local

| Componente | Estratégia | Motivo |
|---|---|---|
| `postgres` | mantido; volume preservado | os dados continuam; as migrações rodam no serviço `db-migrate` |
| `db-bootstrap`, `db-migrate` | rodam a cada subida e terminam | aplicam papéis e migrações Liquibase (só expand) antes da API |
| `api`, `worker`, `web`, `admin` | recreate: param e sobem com a imagem nova | um único computador; não há tráfego de usuário a proteger |

Ordem garantida pelo Compose: `postgres` saudável → `db-bootstrap` → `db-migrate` → `api` saudável → `worker`, `web`, `admin`. O `verify.sh` espera os quatro serviços saudáveis, a primeira coleta da fonte simulada e as tarefas de auditoria antes de rodar os smokes.

## Futuro na AWS: blue/green

| Componente | Estratégia | Troca de tráfego | Critério de aborto |
|---|---|---|---|
| `api` | blue/green | depois do smoke de ambiente no verde, de uma vez | smoke falhando; saúde `DOWN`; erro 5xx acima do normal nos primeiros minutos |
| `worker` | blue/green com uma só instância ativa | o azul para de pegar fatias; o verde assume na fatia seguinte, sem repetir fatias (ShedLock e cursor de fatia) | ciclo falhando; fatia sem conclusão |
| `web`, `admin` | blue/green | depois do smoke | smoke falhando |
| banco | sem blue/green; uma instância com réplica de leitura (decisão da U4) | — | migração falhando aborta antes da troca |

## Migrações (expand-contract)

- Toda migração de uma release só acrescenta (tabela, coluna anulável, índice, papel). A versão anterior continua funcionando sobre o banco migrado.
- A remoção ou mudança destrutiva (contract) só entra numa release seguinte, depois que nenhuma versão em uso depende do que vai sair.
- Isso permite o rollback só de imagens, sem desfazer migração (`rollback-runbook.md`).
- Os testes `MigrationsIT` de cada Unit conferem a ordem e o conteúdo das migrações.

## Portões de promoção

1. **Merge em `main`:** todos os portões do `quality-gates.md` verdes.
2. **Staging (futuro):** automático a cada merge; smoke de ambiente verde antes de trocar o tráfego.
3. **Produção do beta PAPER (futuro):** tag de release; o mesmo SHA já verde no staging; aprovação do responsável do projeto no ambiente `production` do GitHub; smoke verde na produção.
4. **REAL:** bloqueado até G2 e G3; exige um revisor independente (C-14).

## Janela e comunicação

- Implantação na produção do beta em horário combinado com os convidados; o kill switch da U8 pausa os bots durante a troca se for preciso.
- O worker termina a fatia corrente antes de parar (desligamento gracioso do Spring), então nenhuma decisão fica pela metade.
