import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import type { FeedbackItem } from '@/lib/api/admin-api';
import { messages } from '@/lib/i18n/messages';
import { tokenStore } from '@/lib/auth/token-store';
import { FeedbackListPage } from '@/features/feedback/FeedbackListPage';
import { AdminShell } from '@/features/layout/AdminShell';
import { Providers, fakeAdminApi } from '@/test-support';

const text = messages.feedback;

function item(id: string, body: string): FeedbackItem {
  return {
    feedbackId: id,
    tenantId: 't-1',
    tenantName: 'Beta',
    screen: 'S3',
    text: body,
    createdAt: '2026-11-05T10:00:00Z',
  };
}

function renderList(api = fakeAdminApi()) {
  render(
    <Providers api={api}>
      <AdminShell>
        <FeedbackListPage timeZone="UTC" />
      </AdminShell>
    </Providers>,
  );
  return api;
}

describe('feedback of the guests (BR6.5, BR6.6, AC6.1.4)', () => {
  beforeEach(() => tokenStore.clear());

  it('shows each comment with the tenant name, the screen and the time', async () => {
    renderList(
      fakeAdminApi({
        listFeedback: jest.fn(async () => ({ items: [item('f-1', 'Lista lenta')] })),
      }),
    );

    const entry = await screen.findByTestId('feedback-item-f-1');
    expect(entry).toHaveTextContent(text.tenant('Beta'));
    expect(entry).toHaveTextContent(text.screen('S3'));
    expect(entry).toHaveTextContent('05/11/2026, 10:00');
    expect(entry).toHaveTextContent('Lista lenta');
  });

  it('renders markup as plain text and never as HTML', async () => {
    const markup = '<img src=x onerror="alert(1)"> **negrito**';
    renderList(
      fakeAdminApi({ listFeedback: jest.fn(async () => ({ items: [item('f-1', markup)] })) }),
    );

    const body = await screen.findByTestId('feedback-text');
    expect(body.textContent).toBe(markup);
    expect(body.querySelector('img')).toBeNull();
    expect(body.querySelector('strong')).toBeNull();
  });

  it('shows the empty state', async () => {
    renderList();
    expect(await screen.findByTestId('feedback-empty')).toHaveTextContent(text.empty);
  });

  it('shows the error state', async () => {
    renderList(
      fakeAdminApi({
        listFeedback: jest.fn(async () => {
          throw new ApiError(503, 'SERVICE_UNAVAILABLE');
        }),
      }),
    );
    expect(await screen.findByTestId('feedback-error')).toHaveTextContent(text.error);
  });

  it('loads the older comments by cursor', async () => {
    const api = fakeAdminApi({
      listFeedback: jest
        .fn()
        .mockResolvedValueOnce({ items: [item('f-1', 'novo')], nextCursor: 'c-2' })
        .mockResolvedValueOnce({ items: [item('f-0', 'antigo')], nextCursor: null }),
    });
    renderList(api);

    await userEvent.click(await screen.findByTestId('feedback-more'));

    expect(await screen.findByTestId('feedback-item-f-0')).toHaveTextContent('antigo');
    expect(screen.getAllByTestId(/^feedback-item-/).map((node) => node.dataset.testid)).toEqual([
      'feedback-item-f-1',
      'feedback-item-f-0',
    ]);
  });
});
