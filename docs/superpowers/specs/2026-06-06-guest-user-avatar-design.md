---
title: Guest User Avatar
date: 2026-06-06
status: approved
---

# Guest User Avatar

## Summary

Add a visual-only user identity indicator to the top-right of the chat header. It communicates that the user is browsing as a guest and that login will eventually be possible, without any interactive behavior yet.

## Component

A small `UserAvatar` function component added inline in `App.tsx`. No new file needed.

**Visuals:**
- 32×32px circle, `background: #e5e7eb`, `border: 2px solid #d1d5db`
- "G" letter centered, `font-size: 0.85rem`, `font-weight: 700`, `color: #6b7280`
- Amber dot badge (10×10px, `background: #f59e0b`) positioned absolute at bottom-right, with a 2px white border
- `cursor: default` — no pointer, no hover state change
- `title="Guest"` native browser tooltip

## Placement

Appended after the `☰` history button in the header's right button group. No other header changes.

## Out of scope

- Click handler or navigation
- Login flow or auth
- Dropdown or popover
- Any backend changes
