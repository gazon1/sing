---
title: Repair includes trashed legacy recurring rows
date: 2026-10-27
status: accepted
---

## Context
Legacy repeating tasks carrying a rule and no series id could be trashed before the repair pass ran. The DAO query filtered `archived_at IS NULL`, so such rows were never adopted.

## Decision
Include trashed rows in `legacyRecurringForUser` (drop the `archived_at IS NULL` filter). The repair adopts them by stamping `seriesId`, but does not materialise their window and does not reopen them. Restore still clears the per-profile repair marker so restored rows get visited.

## Consequences
A trashed legacy row becomes a trashed series member rather than a live rule-without-series row. Restore no longer resurrects an unadopted rule. Repair errors are collected per row; the marker is written only when no row failed.