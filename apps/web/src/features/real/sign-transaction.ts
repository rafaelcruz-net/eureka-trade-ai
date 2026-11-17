import { WalletCannotSignTransaction, type WalletPort } from '@/features/auth/wallets';
import { decodeBase64, encodeBase64 } from '@/lib/encoding/base64';

export { WalletCannotSignTransaction };

/**
 * 261113-trading-real-bot U6 (BR3.1, BR3.6, BR3.7; U6-NFR1.1): the browser only SIGNS. It decodes the transaction the
 * server built (`transactionBase64` of the signing session), hands those exact bytes to the wallet and returns the
 * signed serialized transaction in base64. It never builds, changes or sends a transaction, and keeps nothing after the
 * dialog closes. As a last guard, the message of what the wallet returned (every byte after the signatures) must equal
 * the message of the server, byte for byte; otherwise nothing is sent and the server would refuse it anyway
 * (`SIGNED_MESSAGE_MISMATCH`).
 */
export class SignedTransactionMismatch extends Error {
  constructor() {
    super('The signed transaction does not carry the message of the proposal');
    this.name = 'SignedTransactionMismatch';
  }
}

const SIGNATURE_BYTES = 64;

/** The count of signatures of a wire transaction (compact-u16) and where it ends. */
function signatureCount(transaction: Uint8Array): { count: number; offset: number } {
  let count = 0;
  for (let index = 0; index < 3; index++) {
    const byte = transaction[index];
    if (byte === undefined) {
      break;
    }
    count |= (byte & 0x7f) << (7 * index);
    if ((byte & 0x80) === 0) {
      return { count, offset: index + 1 };
    }
  }
  throw new SignedTransactionMismatch();
}

/** The message of a wire transaction: every byte after the compact-u16 count and the 64-byte signatures. */
export function messageOf(transaction: Uint8Array): Uint8Array {
  const { count, offset } = signatureCount(transaction);
  const start = offset + count * SIGNATURE_BYTES;
  if (start >= transaction.length) {
    throw new SignedTransactionMismatch();
  }
  return transaction.subarray(start);
}

function sameBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((byte, index) => byte === right[index]);
}

/** Signs the transaction of the server with the connected wallet; the result is `signedTransactionBase64`. */
export async function signServerTransaction(
  wallet: WalletPort,
  transactionBase64: string,
): Promise<string> {
  if (!wallet.canSignTransaction) {
    throw new WalletCannotSignTransaction();
  }
  const given = decodeBase64(transactionBase64);
  const signed = await wallet.signTransaction(given);
  const before = signatureCount(given);
  const after = signatureCount(signed);
  if (before.count !== after.count || !sameBytes(messageOf(given), messageOf(signed))) {
    throw new SignedTransactionMismatch();
  }
  return encodeBase64(signed);
}
