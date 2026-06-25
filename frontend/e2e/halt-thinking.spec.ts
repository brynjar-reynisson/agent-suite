import { test, expect, type Page } from '@playwright/test';

// SSE body for a complete AI response
const sseComplete = (text: string) =>
  `event: content\ndata: ${text}\n\nevent: done\ndata: \n\n`;

// SSE headers required by the app's onopen handler
const sseHeaders = {
  'Content-Type': 'text/event-stream',
  'Cache-Control': 'no-cache',
};

async function fillAndSend(page: Page, text: string) {
  const input = page.locator('footer input[type="text"]');
  await input.fill(text);
  await input.press('Enter');
}

test.beforeEach(async ({ page }) => {
  // Mock static endpoints so tests work without a running backend
  await page.route('**/ai/config/user', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ isAdmin: false, grantedToolGroups: ['web'] }),
    }),
  );
  await page.route('**/ai/conversations', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([]),
    }),
  );
  await page.route('**/ai/config/directories', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/ai/config/mcp-tools**', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
});

test.describe('halt_thinking', () => {
  test('send button stays enabled while streaming', async ({ page }) => {
    await page.route('**/ai/chat', async route => {
      // Hang for 10 s — the test finishes before this resolves
      await new Promise<void>(r => setTimeout(r, 10_000));
      try {
        await route.fulfill({ status: 200, headers: sseHeaders, body: sseComplete('late') });
      } catch {
        // Request was aborted by test teardown
      }
    });

    await page.goto('/');
    await fillAndSend(page, 'hello');

    // Loading indicator must appear
    await expect(page.getByText('Thinking...')).toBeVisible();

    // Send button must NOT be disabled — this is the key halt_thinking requirement
    await expect(page.locator('button', { hasText: 'Send' })).not.toBeDisabled();
  });

  test('new message while streaming interrupts and starts a fresh stream', async ({ page }) => {
    let callCount = 0;

    await page.route('**/ai/chat', async route => {
      callCount++;
      if (callCount === 1) {
        // First request: hang until aborted by the second message
        await new Promise<void>(r => setTimeout(r, 10_000));
        try {
          await route.fulfill({ status: 200, headers: sseHeaders, body: '' });
        } catch {
          // Expected — client aborted this request
        }
      } else {
        // Second request: complete immediately
        await route.fulfill({
          status: 200,
          headers: sseHeaders,
          body: sseComplete('Answer to the second message'),
        });
      }
    });

    await page.goto('/');

    // Send first message and wait for loading to begin
    await fillAndSend(page, 'first message');
    await expect(page.getByText('Thinking...')).toBeVisible();

    // Interrupt by sending a second message
    await fillAndSend(page, 'second message');

    // Both user messages should remain in the chat
    await expect(page.getByText('first message', { exact: true })).toBeVisible();
    await expect(page.getByText('second message', { exact: true })).toBeVisible();

    // The second stream's AI response should appear
    await expect(page.getByText('Answer to the second message')).toBeVisible();

    // Two separate SSE requests were made
    expect(callCount).toBe(2);
  });

  test('!stop aborts the stream and clears the loading indicator', async ({ page }) => {
    await page.route('**/ai/chat', async route => {
      await new Promise<void>(r => setTimeout(r, 10_000));
      try {
        await route.fulfill({ status: 200, headers: sseHeaders, body: sseComplete('late') });
      } catch {
        // Aborted by !stop
      }
    });

    await page.goto('/');
    await fillAndSend(page, 'a question');
    await expect(page.getByText('Thinking...')).toBeVisible();

    await fillAndSend(page, '!stop');

    // Loading indicator must disappear after abort
    await expect(page.getByText('Thinking...')).not.toBeVisible();
  });

  test('!erase-last while loading shows toast instead of erasing', async ({ page }) => {
    await page.route('**/ai/chat', async route => {
      await new Promise<void>(r => setTimeout(r, 10_000));
      try {
        await route.fulfill({ status: 200, headers: sseHeaders, body: sseComplete('late') });
      } catch {
        // Aborted by test teardown
      }
    });

    await page.goto('/');
    await fillAndSend(page, 'a question');
    await expect(page.getByText('Thinking...')).toBeVisible();

    await fillAndSend(page, '!erase-last');

    // Must show the loading guard toast
    await expect(page.getByText('Use !stop first before erasing')).toBeVisible();
    // Loading must still be in progress
    await expect(page.getByText('Thinking...')).toBeVisible();
  });

  test('!erase-last with no messages shows toast', async ({ page }) => {
    await page.goto('/');

    await fillAndSend(page, '!erase-last');

    await expect(page.getByText('Nothing to erase')).toBeVisible();
  });

  test('!erase-last removes the last user+AI turn from the UI', async ({ page }) => {
    await page.route('**/ai/chat', route =>
      route.fulfill({
        status: 200,
        headers: sseHeaders,
        body: sseComplete('AI answer to be erased'),
      }),
    );

    // Mock the erase endpoint — real backend not needed
    await page.route('**/ai/conversations/*/erase-last', route =>
      route.fulfill({ status: 200 }),
    );

    await page.goto('/');
    await fillAndSend(page, 'user message to be erased');

    // Wait for the AI response to render
    await expect(page.getByText('AI answer to be erased')).toBeVisible();

    await fillAndSend(page, '!erase-last');

    // Both sides of the turn must be gone
    await expect(page.getByText('user message to be erased')).not.toBeVisible();
    await expect(page.getByText('AI answer to be erased')).not.toBeVisible();
  });
});
