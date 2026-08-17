---
phase: 14-ui-fidelity-and-route-recovery
plan: "02"
status: complete
completed: 2026-08-11
---

# Plan 14-02 Summary

Replaced the superseded horizontal navigation with the approved shared left rail.

## Changes

- One 248px rail now owns brand, primary routes, active account, account connection,
  queue status, Feedback, logout, purge, and collapse controls.
- Account selection remains rail-only.
- Senders and Unread use deterministic category accents with a neutral fallback.
- Category groups are closed by default and explicitly hide their bodies when closed.
- Responsive contracts cover 980px, 720px, 375px, and 320px stress widths.

## Evidence

- `Phase14WorkspaceFidelityTest`: shared shell, CSRF, rail-only account, category,
  exact-preview, and responsive contracts passed.
- Authenticated Senders and Unread each rendered one navigation and one account
  selector with zero open categories, zero visible cleanup rows, and no desktop overflow.

## Safety

Existing CSRF forms, account ownership, preview endpoints, hidden query parameters,
and exact-ID selection modes were preserved.
