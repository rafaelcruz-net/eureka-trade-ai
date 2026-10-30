// Proves the Jest runner, jsdom and the jest-dom matchers are wired before any feature test.
describe('jest runner', () => {
  it('runs in jsdom with jest-dom matchers', () => {
    document.body.innerHTML = '<p data-testid="probe">ok</p>';
    expect(document.querySelector('[data-testid="probe"]')).toHaveTextContent('ok');
  });
});
