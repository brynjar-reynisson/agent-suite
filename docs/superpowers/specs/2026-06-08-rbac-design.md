# RBAC Design: User and Admin Roles

**Date:** 2026-06-08  
**Status:** Approved

## Goal

Introduce a lightweight role system so that tool group access can be gated per role in the future. The immediate deliverable is the infrastructure — no tools are gated yet. When `md-writer` (and later `mcp`) move to admin-only, the change is a single line in `AuthorizationService`.

## Roles

| Role | Description | Stored in DB |
|------|-------------|--------------|
| `user` | Default for all users including guests | No (implicit) |
| `admin` | Full access; future gated tools | Yes (`user_role` row) |

No row in `user_role` = `user` access. One `admin` row = admin access.

## Data Model

New migration: `supabase/migrations/20260608000000_add_user_roles.sql`

```sql
CREATE TABLE user_role (
    user_id    BIGINT      NOT NULL REFERENCES suite_user(user_id) ON DELETE CASCADE,
    role       TEXT        NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_role PRIMARY KEY (user_id, role)
);

INSERT INTO user_role (user_id, role)
SELECT user_id, 'admin' FROM suite_user WHERE email = 'breynisson@gmail.com';
```

## Backend Components

### `UserRoleRepository` (new)

Location: `src/main/java/com/example/agentsuite/jooq/repository/UserRoleRepository.java`

- `boolean isAdmin(long userId)` — `SELECT EXISTS` query: `user_role WHERE user_id = ? AND role = 'admin'`
- Uses manual jOOQ DSL (no codegen required for new table)

### `AuthorizationService` (new)

Location: `src/main/java/com/example/agentsuite/service/AuthorizationService.java`

```java
boolean isAdmin(long userId)                             // delegates to UserRoleRepository
boolean canUseToolGroup(String group, boolean isAdmin)  // all groups return true today
```

`canUseToolGroup` is where future gating lives. Adding admin-only `md-writer`:
```java
case "md-writer" -> isAdmin;
```

### `UserResolverFilter` (modified)

After resolving `userId`, call `authorizationService.isAdmin(userId)` and set:
```java
request.setAttribute(ATTR_IS_ADMIN, isAdmin);  // new constant: "currentUserIsAdmin"
```
Guest (`user_id = 1`) gets `isAdmin = false`.

### `AiController` (modified)

- Read `isAdmin` from request attribute in `chat()`
- Pass `isAdmin` into `buildToolInstances()`
- `buildToolInstances()` calls `authorizationService.canUseToolGroup(group, isAdmin)` before adding each tool instance
- `AuthorizationService` injected into `AiController` constructor

## What Is Not Gated Yet

- `unix`, `md-writer`, `web` — all available to `user` and `admin`
- Guest = `user` = same access

## Future Gating (not in this implementation)

When ready, change `canUseToolGroup` in `AuthorizationService`:
```java
case "md-writer" -> isAdmin;
case "mcp"       -> isAdmin;
```
No other code changes required.

## Admin Management

Role grants managed via SQL. No admin UI in this implementation.

```sql
-- Grant admin
INSERT INTO user_role (user_id, role)
SELECT user_id, 'admin' FROM suite_user WHERE email = 'user@example.com';

-- Revoke admin
DELETE FROM user_role WHERE user_id = (SELECT user_id FROM suite_user WHERE email = 'user@example.com') AND role = 'admin';
```
