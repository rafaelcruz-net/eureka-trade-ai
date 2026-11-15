import { sign } from 'node:crypto';
import type { SmokeWallet } from '../support/test-wallet';

/**
 * TEST code that talks to the LOCAL Solana validator of infra/docker-compose.real.yml (never devnet nor mainnet): an
 * airdrop to the test main wallet and a plain SOL transfer from it, built and signed here with the key generated in
 * test code. Production never builds a transfer in the browser (ADR-008).
 */
const RPC = process.env.SOLANA_RPC_URL ?? 'http://127.0.0.1:8899';
const ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
export const LAMPORTS_PER_SOL = 1_000_000_000n;

async function rpc<T>(method: string, params: unknown[]): Promise<T> {
  const response = await fetch(RPC, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }),
  });
  const body = (await response.json()) as { result?: T; error?: { message: string } };
  if (body.error) {
    throw new Error(`${method} failed: ${body.error.message}`);
  }
  return body.result as T;
}

function decode(text: string): Buffer {
  let value = 0n;
  for (const char of text) {
    value = value * 58n + BigInt(ALPHABET.indexOf(char));
  }
  const bytes: number[] = [];
  while (value > 0n) {
    bytes.unshift(Number(value & 0xffn));
    value >>= 8n;
  }
  for (const char of text) {
    if (char !== '1') break;
    bytes.unshift(0);
  }
  return Buffer.from(bytes);
}

export function encode(bytes: Buffer): string {
  let value = BigInt(`0x${bytes.toString('hex') || '0'}`);
  let out = '';
  while (value > 0n) {
    out = ALPHABET[Number(value % 58n)] + out;
    value /= 58n;
  }
  for (const byte of bytes) {
    if (byte !== 0) break;
    out = `1${out}`;
  }
  return out;
}

function u64(value: bigint): Buffer {
  const buffer = Buffer.alloc(8);
  buffer.writeBigUInt64LE(value);
  return buffer;
}

export async function balance(address: string): Promise<bigint> {
  const result = await rpc<{ value: number }>('getBalance', [address, { commitment: 'finalized' }]);
  return BigInt(result.value);
}

async function waitFinalized(signature: string, timeoutMs = 90_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const status = await rpc<{ value: ({ confirmationStatus?: string } | null)[] }>(
      'getSignatureStatuses',
      [[signature]],
    );
    if (status.value[0]?.confirmationStatus === 'finalized') {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
  throw new Error(`${signature} not finalized in time`);
}

export async function airdrop(address: string, lamports: bigint): Promise<void> {
  await waitFinalized(await rpc<string>('requestAirdrop', [address, Number(lamports)]));
}

/** A legacy System transfer from the test wallet, signed in test code, waited until finalized. */
export async function transfer(from: SmokeWallet, to: string, lamports: bigint): Promise<string> {
  const { value } = await rpc<{ value: { blockhash: string } }>('getLatestBlockhash', [
    { commitment: 'finalized' },
  ]);
  const message = Buffer.concat([
    Buffer.from([1, 0, 1, 3]),
    Buffer.from(from.publicKey),
    decode(to),
    Buffer.alloc(32),
    decode(value.blockhash),
    Buffer.from([1, 2, 2, 0, 1, 12]),
    Buffer.from([2, 0, 0, 0]),
    u64(lamports),
  ]);
  const signature = sign(null, message, from.key);
  const wire = Buffer.concat([Buffer.from([1]), signature, message]);
  const sent = await rpc<string>('sendTransaction', [
    wire.toString('base64'),
    { encoding: 'base64' },
  ]);
  await waitFinalized(sent);
  return encode(signature);
}
