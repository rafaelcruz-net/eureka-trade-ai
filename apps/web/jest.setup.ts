import '@testing-library/jest-dom';
import { TextDecoder, TextEncoder } from 'node:util';

// jsdom does not expose the WHATWG encoders that the wallet signing flow uses.
if (typeof globalThis.TextEncoder === 'undefined') {
  Object.assign(globalThis, { TextEncoder, TextDecoder });
}

// 261113-trading-real-bot U6 (logical-components.md "Ajustes menores"): jsdom does not implement the modal methods of
// <dialog>. The stubs only toggle the `open` attribute, like a browser does; the focus tests check the initial focus and
// its return, which the components set themselves.
if (typeof HTMLDialogElement !== 'undefined') {
  if (typeof HTMLDialogElement.prototype.showModal !== 'function') {
    HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  }
  if (typeof HTMLDialogElement.prototype.close !== 'function') {
    HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
      this.removeAttribute('open');
    };
  }
}
