import { test, expect, type Page } from '@playwright/test';

const sseHeaders = {
  'Content-Type': 'text/event-stream',
  'Cache-Control': 'no-cache',
};

const sseWithUsage = (text: string, usage: object) =>
  `event: content\ndata: ${text}\n\nevent: done\ndata: ${JSON.stringify(usage)}\n\n`;

async function fillAndSend(page: Page, text: string) {
  const input = page.locator('footer input[type="text"]');
  await input.fill(text);
  await input.press('Enter');
}

test.beforeEach(async ({ page }) => {
  await page.route('**/ai/config/user', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ isAdmin: false, grantedToolGroups: ['web'] }),
    }),
  );
  await page.route('**/ai/conversations', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) }),
  );
  await page.route('**/ai/config/directories', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/ai/config/mcp-tools**', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
});

test.describe('turn token usage display', () => {
  test('shows input/output token counts on the AI message', async ({ page }) => {
    await page.route('**/ai/chat', route =>
      route.fulfill({
        status: 200,
        headers: sseHeaders,
        body: sseWithUsage('Hello!', {
          inputTokens: 1200,
          outputTokens: 340,
          cacheReadTokens: null,
          cacheWriteTokens: null,
        }),
      }),
    );

    await page.goto('/');
    await fillAndSend(page, 'hi');

    await expect(page.getByText('1.2k in')).toBeVisible();
    await expect(page.getByText('340 out')).toBeVisible();
    await expect(page.getByText(/cached/)).not.toBeVisible();
  });

  test('shows cache token count when the provider reports it', async ({ page }) => {
    await page.route('**/ai/chat', route =>
      route.fulfill({
        status: 200,
        headers: sseHeaders,
        body: sseWithUsage('Hello!', {
          inputTokens: 1200,
          outputTokens: 340,
          cacheReadTokens: 812,
          cacheWriteTokens: 0,
        }),
      }),
    );

    await page.goto('/');
    await fillAndSend(page, 'hi');

    await expect(page.getByText('812 cached')).toBeVisible();
  });
});
