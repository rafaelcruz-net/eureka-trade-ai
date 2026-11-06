/**
 * Public package of the Administration module (U8). The module offers no Java interface to other modules in the beta:
 * its only entry points are the administrative routes ({@code /api/v1/admin/kill-switch}, {@code /system-status},
 * {@code /tenants}) and its two jobs (the kill switch reconciliation and its start-up round). It USES the public
 * interfaces of the other modules only: C16 {@code BotAdministration} (U5), C17 {@code IdentityAdministration} (U3),
 * {@code MarketDataQuery} (U4), {@code AuditBacklog} (U2) and {@code RejectionCounts} (U6). Only
 * {@code KillSwitchApplier} calls the system pause of U5 (ArchUnit).
 */
package com.memetrade.administration.api;
