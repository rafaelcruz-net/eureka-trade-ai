import {
  createHash,
  createPublicKey,
  generateKeyPairSync,
  sign,
  type KeyObject,
} from 'node:crypto';
import type { SmokeWallet } from '../support/test-wallet';

/**
 * TEST code that talks to the LOCAL Solana validator of infra/docker-compose.real.yml (never devnet nor mainnet): an
 * airdrop to the test main wallet, a plain SOL transfer from it and (U5) a new test token minted straight into the
 * associated token account of a wallet, all built and signed here with keys generated in test code, by hand and
 * without any Solana SDK. Production never builds a transfer in the browser (ADR-008).
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

const SYSTEM_PROGRAM = Buffer.alloc(32);
const TOKEN_PROGRAM = 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA';
const ASSOCIATED_TOKEN_PROGRAM = 'ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL';
const MINT_SIZE = 82n;

function u32(value: number): Buffer {
  const buffer = Buffer.alloc(4);
  buffer.writeUInt32LE(value);
  return buffer;
}

/** Ed25519 field and curve constants, to tell an address on the curve from a program derived one. */
const P = 2n ** 255n - 19n;

function power(base: bigint, exponent: bigint): bigint {
  let result = 1n;
  let b = ((base % P) + P) % P;
  let e = exponent;
  while (e > 0n) {
    if (e & 1n) result = (result * b) % P;
    b = (b * b) % P;
    e >>= 1n;
  }
  return result;
}

const D = (((-121665n * power(121666n, P - 2n)) % P) + P) % P;

/** Whether 32 bytes decode to a point of Ed25519 (x squared must be a square modulo p). */
function onCurve(bytes: Buffer): boolean {
  const copy = Buffer.from(bytes);
  copy[31] = copy[31]! & 0x7f;
  const y = BigInt(`0x${Buffer.from(copy).reverse().toString('hex')}`);
  if (y >= P) return false;
  const y2 = (y * y) % P;
  const u = (y2 - 1n + P) % P;
  const v = (D * y2 + 1n) % P;
  const x2 = (u * power(v, P - 2n)) % P;
  return x2 === 0n || power(x2, (P - 1n) / 2n) === 1n;
}

/** findProgramAddress: the first bump, from 255 down, whose hash is off the curve. */
function programAddress(seeds: Buffer[], program: Buffer): Buffer {
  for (let bump = 255; bump >= 0; bump--) {
    const hash = createHash('sha256')
      .update(
        Buffer.concat([
          ...seeds,
          Buffer.from([bump]),
          program,
          Buffer.from('ProgramDerivedAddress'),
        ]),
      )
      .digest();
    if (!onCurve(hash)) {
      return hash;
    }
  }
  throw new Error('no program address found');
}

/** The associated token account of an owner for a mint (classic Token program). */
export function associatedTokenAccount(owner: string, mint: string): string {
  return encode(
    programAddress(
      [decode(owner), decode(TOKEN_PROGRAM), decode(mint)],
      decode(ASSOCIATED_TOKEN_PROGRAM),
    ),
  );
}

function rawPublicKey(key: KeyObject): Buffer {
  const spki = (key.type === 'public' ? key : createPublicKey(key)).export({
    format: 'der',
    type: 'spki',
  });
  return Buffer.from(spki.subarray(spki.length - 32));
}

/**
 * U5 (divergence spec): creates a NEW test token of {@code decimals} (mint authority the payer, no freeze authority)
 * and mints {@code baseUnits} straight into the associated token account of {@code owner}, in ONE legacy transaction
 * of four instructions signed by the payer and the new mint: System createAccount, Token InitializeMint2 (20),
 * Associated Token CreateIdempotent (1) and Token MintTo (7). For the bot wallet, that token arrives with no order and
 * no ledger entry: a divergence the reconciliation must find. Returns the mint.
 */
export async function mintToNewToken(
  payer: SmokeWallet,
  owner: string,
  baseUnits: bigint,
  decimals = 6,
): Promise<string> {
  const mintKeys = generateKeyPairSync('ed25519');
  const mintPublic = rawPublicKey(mintKeys.publicKey);
  const mint = encode(mintPublic);
  const ata = decode(associatedTokenAccount(owner, mint));
  const rent = await rpc<number>('getMinimumBalanceForRentExemption', [Number(MINT_SIZE)]);
  const { value } = await rpc<{ value: { blockhash: string } }>('getLatestBlockhash', [
    { commitment: 'finalized' },
  ]);
  // Accounts: 0 payer (signer, writable), 1 mint (signer, writable), 2 associated account (writable), then the
  // read-only owner, System, Token and Associated Token programs.
  const keys = [
    Buffer.from(payer.publicKey),
    mintPublic,
    ata,
    decode(owner),
    SYSTEM_PROGRAM,
    decode(TOKEN_PROGRAM),
    decode(ASSOCIATED_TOKEN_PROGRAM),
  ];
  const instruction = (program: number, accounts: number[], data: Buffer) =>
    Buffer.concat([
      Buffer.from([program, accounts.length, ...accounts]),
      Buffer.from([data.length]),
      data,
    ]);
  const createAccount = Buffer.concat([
    u32(0),
    u64(BigInt(rent)),
    u64(MINT_SIZE),
    decode(TOKEN_PROGRAM),
  ]);
  const initializeMint2 = Buffer.concat([
    Buffer.from([20, decimals]),
    Buffer.from(payer.publicKey),
    Buffer.from([0]),
  ]);
  const createIdempotent = Buffer.from([1]);
  const mintTo = Buffer.concat([Buffer.from([7]), u64(baseUnits)]);
  const message = Buffer.concat([
    Buffer.from([2, 0, 4]),
    Buffer.from([keys.length]),
    ...keys,
    decode(value.blockhash),
    Buffer.from([4]),
    instruction(4, [0, 1], createAccount),
    instruction(5, [1], initializeMint2),
    instruction(6, [0, 2, 3, 1, 4, 5], createIdempotent),
    instruction(5, [1, 2, 0], mintTo),
  ]);
  const payerSignature = sign(null, message, payer.key);
  const mintSignature = sign(null, message, mintKeys.privateKey);
  const wire = Buffer.concat([Buffer.from([2]), payerSignature, mintSignature, message]);
  const sent = await rpc<string>('sendTransaction', [
    wire.toString('base64'),
    { encoding: 'base64' },
  ]);
  await waitFinalized(sent);
  return mint;
}
