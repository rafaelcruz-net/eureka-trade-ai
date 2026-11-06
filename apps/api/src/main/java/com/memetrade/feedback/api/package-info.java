/**
 * Public package of the Feedback module (U8). The module offers no Java interface to other modules: its entry points
 * are {@code POST /api/v1/feedback} (guest, C6), {@code GET /api/v1/admin/feedback} (administrator, C8) and the daily
 * purge of the comments older than 180 days. Tenant and person always come from the token; the administrator list
 * never carries the person nor the wallet (BR6.6), and the text of a comment never reaches a log.
 */
package com.memetrade.feedback.api;
