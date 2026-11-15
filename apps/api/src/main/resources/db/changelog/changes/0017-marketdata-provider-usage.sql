--liquibase formatted sql

-- 261113-trading-real-bot, U2 u2-real-market-data (reliability-design.md "Orçamento", infrastructure-specification.md
-- "Contador", NFR3.7). Expand only: a new table, nothing existing changes. The monthly call counter of a real market
-- source survives a restart: the worker adds one call BEFORE it opens the call, in a short transaction of its own, and
-- the hourly usage job keeps the greatest value between this table and the one CoinGecko reports. The month is the UTC
-- calendar month (YYYY-MM). Rows older than 13 months are deleted by the market history cleanup.

--changeset memetrade:0017-marketdata-provider-usage
CREATE TABLE marketdata_provider_usage (
    source     varchar(32) NOT NULL,
    month      char(7)     NOT NULL,
    calls      bigint      NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT marketdata_provider_usage_pk PRIMARY KEY (source, month),
    CONSTRAINT marketdata_provider_usage_month_ck CHECK (month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT marketdata_provider_usage_calls_ck CHECK (calls >= 0)
);
--rollback DROP TABLE marketdata_provider_usage;
