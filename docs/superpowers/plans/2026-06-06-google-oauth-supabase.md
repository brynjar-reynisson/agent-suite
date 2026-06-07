# Google OAuth Login via Supabase Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Google OAuth login to the chat frontend via Supabase Auth — clicking the avatar triggers Google sign-in, and a logged-in user sees their Google photo/name with a Sign out dropdown.

**Architecture:** A new `auth.ts` module owns the Supabase client and `useAuth` hook; a new `UserAvatar.tsx` replaces the inline component in App.tsx and handles both guest and authenticated states; App.tsx wires them together with four-line changes. The Spring Boot backend is untouched.

**Tech Stack:** React 19, TypeScript, Vite, `@supabase/supabase-js`, Supabase local auth (Google OAuth provider), Tailwind CSS 4

---

### Task 1: Configuration prerequisites

**Files:**
- Modify: `supabase/config.toml`
- Modify: `restart.sh`
- Modify: `frontend/.env`

> ⚠️ These are config-only changes — no TypeScript to test. Verification is confirming Supabase restarts cleanly and the Google provider appears in its auth settings.

- [ ] **Step 1: Add the Google OAuth provider to `supabase/config.toml`**

Open `supabase/config.toml`. Find the `[auth.external.apple]` block (around line 315). Add the following block immediately after it:

```toml
[auth.external.google]
enabled = true
client_id = "env(SUPABASE_AUTH_EXTERNAL_GOOGLE_CLIENT_ID)"
secret = "env(SUPABASE_AUTH_EXTERNAL_GOOGLE_SECRET)"
```

- [ ] **Step 2: Add Google OAuth credentials to `restart.sh`**

Open `restart.sh`. Add these two lines alongside the existing `export` statements (after the `MISTRAL_AI_API_KEY` line):

```bash
export SUPABASE_AUTH_EXTERNAL_GOOGLE_CLIENT_ID="<your_google_client_id>"
export SUPABASE_AUTH_EXTERNAL_GOOGLE_SECRET="<your_google_client_secret>"
```

Replace `<your_google_client_id>` and `<your_google_client_secret>` with the real credentials obtained from Google Cloud Console.

- [ ] **Step 3: Populate `frontend/.env` with the Supabase local URL and anon key**

The file `frontend/.env` already exists (empty). Add:

```
VITE_SUPABASE_URL=http://127.0.0.1:54321
VITE_SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9.CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0
```

These are the fixed local-development values — the anon key is public by design.

- [ ] **Step 4: Restart Supabase to pick up the new Google provider config**

Supabase must be restarted with the Google credentials in the environment so it can read the `env(...)` references in `config.toml`. In a terminal at the project root:

```bash
export SUPABASE_AUTH_EXTERNAL_GOOGLE_CLIENT_ID="<your_google_client_id>"
export SUPABASE_AUTH_EXTERNAL_GOOGLE_SECRET="<your_google_client_secret>"
supabase stop && supabase start
```

Expected output: Supabase starts cleanly with all services listed as `started`. No errors about missing env vars.

- [ ] **Step 5: Commit**

```bash
git -C . add supabase/config.toml restart.sh frontend/.env
git -C . commit -m "feat: configure Supabase Google OAuth provider and local env vars"
```

---

### Task 2: Install `@supabase/supabase-js` and create `frontend/src/auth.ts`

**Files:**
- Modify: `frontend/package.json` (via npm install)
- Create: `frontend/src/auth.ts`

- [ ] **Step 1: Install the Supabase JS client**

```bash
cd C:/Users/Lenovo/IdeaProjects/agent-suite/frontend && npm install @supabase/supabase-js
```

Expected: package installs without errors; `@supabase/supabase-js` appears in `package.json` dependencies.

- [ ] **Step 2: Create `frontend/src/auth.ts`** with the complete implementation:

```ts
import { createClient } from '@supabase/supabase-js';
import { useEffect, useState } from 'react';
import type { User } from '@supabase/supabase-js';

const supabase = createClient(
  import.meta.env.VITE_SUPABASE_URL as string,
  import.meta.env.VITE_SUPABASE_ANON_KEY as string,
);

export function useAuth(): {
  user: User | null;
  loading: boolean;
  signIn: () => Promise<void>;
  signOut: () => Promise<void>;
} {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    supabase.auth.getSession().then(({ data: { session } }) => {
      setUser(session?.user ?? null);
      setLoading(false);
    });

    const {
      data: { subscription },
    } = supabase.auth.onAuthStateChange((_event, session) => {
      setUser(session?.user ?? null);
    });

    return () => subscription.unsubscribe();
  }, []);

  const signIn = async () => {
    await supabase.auth.signInWithOAuth({
      provider: 'google',
      options: { redirectTo: 'http://127.0.0.1:5176' },
    });
  };

  const signOut = async () => {
    await supabase.auth.signOut();
  };

  return { user, loading, signIn, signOut };
}
```

- [ ] **Step 3: Verify TypeScript compiles**

```bash
cd C:/Users/Lenovo/IdeaProjects/agent-suite/frontend && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git -C C:/Users/Lenovo/IdeaProjects/agent-suite add frontend/package.json frontend/package-lock.json frontend/src/auth.ts
git -C C:/Users/Lenovo/IdeaProjects/agent-suite commit -m "feat: add Supabase client and useAuth hook"
```

---

### Task 3: Create `frontend/src/UserAvatar.tsx`

**Files:**
- Create: `frontend/src/UserAvatar.tsx`

- [ ] **Step 1: Create `frontend/src/UserAvatar.tsx`** with the complete implementation:

```tsx
import { useEffect, useRef, useState } from 'react';
import type { User } from '@supabase/supabase-js';

interface Props {
  user: User | null;
  signIn: () => Promise<void>;
  signOut: () => Promise<void>;
}

export function UserAvatar({ user, signIn, signOut }: Props) {
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const wrapperRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!dropdownOpen) return;
    const handleMouseDown = (e: MouseEvent) => {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) {
        setDropdownOpen(false);
      }
    };
    document.addEventListener('mousedown', handleMouseDown);
    return () => document.removeEventListener('mousedown', handleMouseDown);
  }, [dropdownOpen]);

  if (!user) {
    return (
      <div
        role="button"
        aria-label="Guest user — click to sign in with Google"
        title="Sign in with Google"
        className="relative w-8 h-8 cursor-pointer"
        onClick={() => void signIn()}
      >
        <div className="w-full h-full rounded-full flex items-center justify-center font-bold text-gray-500 bg-gray-200 border-2 border-gray-300 text-[0.85rem]">
          G
        </div>
        <div
          aria-hidden="true"
          className="absolute bottom-0 right-0 w-2.5 h-2.5 rounded-full bg-amber-400 border-2 border-white"
        />
      </div>
    );
  }

  const avatarUrl = user.user_metadata?.avatar_url as string | undefined;
  const fullName = user.user_metadata?.full_name as string | undefined;
  const initial = (fullName?.[0] ?? user.email?.[0] ?? '?').toUpperCase();

  return (
    <div ref={wrapperRef} className="relative">
      <div
        role="button"
        aria-label={`${fullName ?? user.email} — click to open menu`}
        title={fullName ?? user.email}
        className="w-8 h-8 rounded-full overflow-hidden border-2 border-blue-400 cursor-pointer"
        onClick={() => setDropdownOpen((o) => !o)}
      >
        {avatarUrl ? (
          <img
            src={avatarUrl}
            alt={fullName ?? user.email}
            className="w-full h-full object-cover"
          />
        ) : (
          <div className="w-full h-full rounded-full flex items-center justify-center font-bold text-gray-500 bg-gray-200 text-[0.85rem]">
            {initial}
          </div>
        )}
      </div>
      {dropdownOpen && (
        <div className="absolute right-0 top-10 z-20 bg-white border border-gray-200 rounded-lg shadow-lg p-3 min-w-[200px]">
          <p className="font-semibold text-sm text-gray-800 truncate">{fullName ?? '—'}</p>
          <p className="text-xs text-gray-500 mb-3 truncate">{user.email}</p>
          <button
            onClick={() => { void signOut(); setDropdownOpen(false); }}
            className="w-full text-left text-sm text-red-600 hover:text-red-700 font-medium"
          >
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Verify TypeScript compiles**

```bash
cd C:/Users/Lenovo/IdeaProjects/agent-suite/frontend && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git -C C:/Users/Lenovo/IdeaProjects/agent-suite add frontend/src/UserAvatar.tsx
git -C C:/Users/Lenovo/IdeaProjects/agent-suite commit -m "feat: add UserAvatar component with guest and logged-in states"
```

---

### Task 4: Wire up `useAuth` and `UserAvatar` in `App.tsx`

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Remove the inline `UserAvatar` function from `App.tsx`**

Find and delete the entire `UserAvatar` function in `frontend/src/App.tsx`. It looks like:

```tsx
function UserAvatar() {
  return (
    <div
      role="img"
      aria-label="Guest user avatar"
      title="Guest"
      className="relative w-8 h-8"
    >
      <div className="w-full h-full rounded-full flex items-center justify-center font-bold text-gray-500 bg-gray-200 border-2 border-gray-300 text-[0.85rem]">
        G
      </div>
      <div
        aria-hidden="true"
        className="absolute bottom-0 right-0 w-2.5 h-2.5 rounded-full bg-amber-400 border-2 border-white"
      />
    </div>
  );
}
```

- [ ] **Step 2: Add imports at the top of `App.tsx`**

Add these two import lines alongside the existing imports:

```tsx
import { useAuth } from './auth';
import { UserAvatar } from './UserAvatar';
```

- [ ] **Step 3: Add `useAuth()` call inside the `App` function**

Inside `function App()`, alongside the existing `useState` declarations, add:

```tsx
const { user, signIn, signOut } = useAuth();
```

- [ ] **Step 4: Replace `<UserAvatar />` in the header JSX**

Find `<UserAvatar />` in the header's right button group and replace it with:

```tsx
<UserAvatar user={user} signIn={signIn} signOut={signOut} />
```

- [ ] **Step 5: Verify TypeScript compiles**

```bash
cd C:/Users/Lenovo/IdeaProjects/agent-suite/frontend && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 6: Manual browser verification**

Start the app with `bash restart.sh` and open `http://127.0.0.1:5176`.

Verify:
1. The gray "G" avatar with amber dot appears in the header
2. Clicking it redirects to Google's OAuth consent page
3. After completing Google sign-in, you land back at `http://127.0.0.1:5176`
4. The avatar now shows your Google profile photo (or your initial)
5. Clicking the avatar opens a dropdown showing your name, email, and a "Sign out" button
6. Clicking "Sign out" returns to the guest state
7. Refreshing the page while logged in restores the logged-in state (session persisted in localStorage)

- [ ] **Step 7: Commit**

```bash
git -C C:/Users/Lenovo/IdeaProjects/agent-suite add frontend/src/App.tsx
git -C C:/Users/Lenovo/IdeaProjects/agent-suite commit -m "feat: wire Google OAuth login into chat header"
```
