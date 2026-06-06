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
        tabIndex={0}
        aria-label="Guest user — click to sign in with Google"
        title="Sign in with Google"
        className="relative w-8 h-8 cursor-pointer"
        onClick={() => void signIn()}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') void signIn(); }}
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
        tabIndex={0}
        aria-label={`${fullName ?? user.email ?? ''} — click to open menu`}
        aria-expanded={dropdownOpen}
        title={fullName ?? user.email ?? ''}
        className="w-8 h-8 rounded-full overflow-hidden border-2 border-blue-400 cursor-pointer"
        onClick={() => setDropdownOpen((o) => !o)}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setDropdownOpen((o) => !o); }}
      >
        {avatarUrl ? (
          <img
            src={avatarUrl}
            alt={fullName ?? user.email ?? ''}
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
