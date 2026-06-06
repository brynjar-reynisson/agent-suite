---
title: Google OAuth Login via Supabase (Frontend-Only)
date: 2026-06-06
status: approved
---

# Google OAuth Login via Supabase (Frontend-Only)

## Summary

Add Google OAuth authentication to the chat UI using Supabase Auth. Clicking the guest avatar triggers the Google OAuth flow. After login the avatar shows the user's Google photo and name, and a dropdown provides a Sign out option. The Spring Boot backend is not changed — it continues to treat all requests as the guest user.

## Prerequisites

### Google Cloud Console
Create an OAuth 2.0 Web Client with:
- Authorized redirect URI: `http://127.0.0.1:54321/auth/v1/callback`
- Produces a `client_id` and `client_secret`

### `supabase/config.toml`
Add the Google provider section:

```toml
[auth.external.google]
enabled = true
client_id = "env(SUPABASE_AUTH_EXTERNAL_GOOGLE_CLIENT_ID)"
secret = "env(SUPABASE_AUTH_EXTERNAL_GOOGLE_SECRET)"
```

### Environment variables
Add to `restart.sh` alongside existing API keys:
```bash
export SUPABASE_AUTH_EXTERNAL_GOOGLE_CLIENT_ID="<client_id>"
export SUPABASE_AUTH_EXTERNAL_GOOGLE_SECRET="<client_secret>"
```

Add to `frontend/.env` (anon key is public, safe to commit):
```
VITE_SUPABASE_URL=http://127.0.0.1:54321
VITE_SUPABASE_ANON_KEY=<local anon key from `supabase status`>
```

Supabase must be restarted after `config.toml` changes (`supabase stop && supabase start`).

## New Files

### `frontend/src/auth.ts`

Single responsibility: Supabase client + `useAuth` hook. No other file imports from `@supabase/supabase-js` directly.

```ts
import { createClient } from '@supabase/supabase-js';
import { useEffect, useState } from 'react';
import type { User } from '@supabase/supabase-js';

const supabase = createClient(
  import.meta.env.VITE_SUPABASE_URL,
  import.meta.env.VITE_SUPABASE_ANON_KEY
);

export function useAuth(): {
  user: User | null;
  loading: boolean;
  signIn: () => Promise<void>;
  signOut: () => Promise<void>;
}
```

**Behaviour:**
- On mount: calls `supabase.auth.getSession()` to restore an existing session from localStorage
- Subscribes to `supabase.auth.onAuthStateChange` to stay in sync; unsubscribes on unmount
- `signIn`: calls `supabase.auth.signInWithOAuth({ provider: 'google', options: { redirectTo: 'http://127.0.0.1:5176' } })`
- `signOut`: calls `supabase.auth.signOut()`
- `loading` is `true` until the initial `getSession()` resolves

### `frontend/src/UserAvatar.tsx`

Replaces the inline `UserAvatar` function currently in `App.tsx`.

**Props:** `{ user: User | null; signIn: () => Promise<void>; signOut: () => Promise<void> }`

**Guest state** (`user` is null):
- Gray circle, "G" initial, amber badge dot (same appearance as today)
- `cursor: pointer`
- `title="Sign in with Google"`
- Click calls `signIn()`

**Logged-in state** (`user` is set):
- Circular avatar: shows `user.user_metadata.avatar_url` as an `<img>` if present; otherwise shows the first character of `user.user_metadata.full_name` or `user.email` in the same circle style
- No amber badge dot
- `role="img"`, `aria-label="{user's name} — click to open menu"`
- Click toggles a dropdown positioned below-right of the avatar

**Dropdown** (visible when logged in and avatar clicked):
- Shows `user.user_metadata.full_name` (bold) and `user.email` (small, muted)
- "Sign out" button: calls `signOut()` and closes dropdown
- Closes on click-outside via `mousedown` listener (same pattern as `PromptCombobox` in `App.tsx`)

## Modified Files

### `frontend/src/App.tsx`
- Remove the inline `UserAvatar` function definition
- Import `useAuth` from `./auth` and `UserAvatar` from `./UserAvatar`
- Add `const { user, signIn, signOut } = useAuth();` inside `App()`
- Replace `<UserAvatar />` in the header with `<UserAvatar user={user} signIn={signIn} signOut={signOut} />`

### `supabase/config.toml`
- Add `[auth.external.google]` section as shown above

### `restart.sh`
- Add the two Google OAuth env vars

### `frontend/.env`
- New file with `VITE_SUPABASE_URL` and `VITE_SUPABASE_ANON_KEY`

## Dependencies

Add to `frontend/package.json`:
```
@supabase/supabase-js  (latest)
```

## Out of Scope

- Backend user identity / JWT validation
- Associating conversations with authenticated users
- Any other auth providers
- Email/password login
- Session timeout / token refresh UI
