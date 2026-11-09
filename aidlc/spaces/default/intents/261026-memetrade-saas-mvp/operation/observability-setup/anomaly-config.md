# Configuração de detecção de anomalias

Conversa: português (Brasil). Resposta 8A. Desenho para o CloudWatch, a ligar só depois de duas semanas de dados reais na hospedagem. Até lá, valem os limites fixos de `alarms.md`, derivados dos `monitoring-design.md` e `performance-design.md` das Units.

## Métricas monitoradas

| Métrica | Banda | Período | Severidade | Por quê |
|---|---|---|---|---|
| Tráfego total da API (`http_server_requests_seconds_count`) | 3 desvios | 5 min | Média | queda brusca indica falha de entrada; pico indica abuso |
| Taxa de 5xx | 2 desvios, só acima | 5 min | Média (o limite fixo de 2% continua Alto) | erro crescente abaixo do limite fixo |
| Logins recusados (`login_attempts_total{result="rejected"}`) | 2 desvios, só acima | 15 min | Média | tentativas de ataque |
| Recusas de risco por bot (derivada da U6) | 3 desvios, só acima | 15 min | Média | bot com comportamento anormal |
| `scanner_excluded_by_unknown` | 3 desvios, só acima | 15 min | Média | perda de cobertura da fonte |

## Regras

- Treino com 14 dias de dados reais. Janelas de manutenção e releases são excluídas do treino.
- A anomalia nunca substitui um limite fixo ligado à segurança, ao ledger ou ao kill switch.
- Os alarmes de anomalia vão para o tópico SNS de severidade Média (`alarms.md`).
- Os modelos são revistos a cada 30 dias. Uma anomalia que dispara sem incidente real em mais de 3 de 10 casos tem a banda revista.
