/**
 * Password-based encryption shared with the Android app (Crypto.kt), so either side can open the other's files.
 * PBKDF2-HMAC-SHA256 turns a password into an AES-256-GCM key. Passwords are NFC-normalized and UTF-8 encoded first.
 */

export const ITERATIONS = 600_000;
const enc = new TextEncoder();
const dec = new TextDecoder();

export class WrongPassword extends Error {
  constructor(message = 'Wrong password.') {
    super(message);
  }
}

export const random = (n: number) => crypto.getRandomValues(new Uint8Array(n));

export function b64(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

export const unb64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

/** The AES-256-GCM key a password and salt give. Never extractable. */
export async function deriveKey(password: string, salt: Uint8Array<ArrayBuffer>, iterations: number): Promise<CryptoKey> {
  const base = await crypto.subtle.importKey('raw', enc.encode(password.normalize('NFC')), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', hash: 'SHA-256', salt, iterations },
    base,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

const gcm = (iv: Uint8Array<ArrayBuffer>, aad?: Uint8Array<ArrayBuffer>) =>
  (aad ? { name: 'AES-GCM', iv, additionalData: aad } : { name: 'AES-GCM', iv }) as AesGcmParams;

export async function seal(key: CryptoKey, iv: Uint8Array<ArrayBuffer>, plain: Uint8Array<ArrayBuffer>, aad?: Uint8Array<ArrayBuffer>) {
  return new Uint8Array(await crypto.subtle.encrypt(gcm(iv, aad), key, plain));
}

/** Throws WrongPassword when the key is wrong or the bytes were changed; GCM cannot tell the two apart. */
export async function open(key: CryptoKey, iv: Uint8Array<ArrayBuffer>, sealed: Uint8Array<ArrayBuffer>, aad?: Uint8Array<ArrayBuffer>) {
  try {
    return new Uint8Array(await crypto.subtle.decrypt(gcm(iv, aad), key, sealed));
  } catch {
    throw new WrongPassword();
  }
}

// ---- The vault: one random data key for locked notes, wrapped by the app password. ----

/** Stored on this device and on every locked note, so any device with the password can open them. */
export interface VaultHeader {
  id: string;
  salt: string;
  iterations: number;
  /** IV and AES-GCM ciphertext of the 32-byte data key, under the password's key. */
  iv: string;
  key: string;
}

const importDataKey = (raw: Uint8Array<ArrayBuffer>) =>
  crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);

async function wrap(id: string, raw: Uint8Array<ArrayBuffer>, password: string, iterations: number): Promise<VaultHeader> {
  const salt = random(16);
  const iv = random(12);
  const kek = await deriveKey(password, salt, iterations);
  const key = await seal(kek, iv, raw, enc.encode(`sheaf-vault:${id}`));
  return { id, salt: b64(salt), iterations, iv: b64(iv), key: b64(key) };
}

async function unwrapRaw(header: VaultHeader, password: string) {
  const kek = await deriveKey(password, unb64(header.salt), header.iterations);
  return open(kek, unb64(header.iv), unb64(header.key), enc.encode(`sheaf-vault:${header.id}`));
}

export async function createVault(password: string, iterations = ITERATIONS) {
  const raw = random(32);
  const header = await wrap(crypto.randomUUID(), raw, password, iterations);
  return { header, key: await importDataKey(raw) };
}

/** The vault's data key. Throws WrongPassword. */
export async function unlockVault(header: VaultHeader, password: string): Promise<CryptoKey> {
  return importDataKey(await unwrapRaw(header, password));
}

/** The same data key under a new password, so locked notes need no re-encrypting. */
export async function rewrapVault(header: VaultHeader, oldPassword: string, newPassword: string, iterations = ITERATIONS) {
  return wrap(header.id, await unwrapRaw(header, oldPassword), newPassword, iterations);
}

export interface Sealed {
  iv: string;
  data: string;
}

/** Encrypts a JSON value. The context (a note id) is bound in, so ciphertext cannot be moved to another note. */
export async function sealJson(key: CryptoKey, value: unknown, context: string): Promise<Sealed> {
  const iv = random(12);
  const data = await seal(key, iv, enc.encode(JSON.stringify(value)), enc.encode(context));
  return { iv: b64(iv), data: b64(data) };
}

export async function openJson<T>(key: CryptoKey, sealed: Sealed, context: string): Promise<T> {
  return JSON.parse(dec.decode(await open(key, unb64(sealed.iv), unb64(sealed.data), enc.encode(context))));
}

// ---- Encrypted backup files ----
//
// "SHEAFENC", version byte 1, u32 header length, UTF-8 JSON header, then chunks of [u32 length, AES-GCM ciphertext].
// Chunk i uses IV = nonce(8) || i (u32 big-endian) and AAD = header bytes || final flag, and only the last chunk
// has the flag set, so a truncated, reordered or extended file fails to decrypt.

const MAGIC = enc.encode('SHEAFENC');
export const CHUNK = 1024 * 1024;

export interface BackupHeader {
  kdf: 'PBKDF2-SHA256';
  iterations: number;
  salt: string;
  cipher: 'AES-256-GCM';
  chunk: number;
  nonce: string;
}

export const newBackupHeader = (salt: Uint8Array, iterations = ITERATIONS): BackupHeader => ({
  kdf: 'PBKDF2-SHA256',
  iterations,
  salt: b64(salt),
  cipher: 'AES-256-GCM',
  chunk: CHUNK,
  nonce: b64(random(8)),
});

const u32 = (n: number) => {
  const b = new Uint8Array(4);
  new DataView(b.buffer).setUint32(0, n);
  return b;
};

function chunkIv(nonce: Uint8Array, i: number) {
  const iv = new Uint8Array(12);
  iv.set(nonce);
  new DataView(iv.buffer).setUint32(8, i);
  return iv;
}

function chunkAad(header: Uint8Array, final: boolean) {
  const aad = new Uint8Array(header.length + 1);
  aad.set(header);
  aad[header.length] = final ? 1 : 0;
  return aad;
}

export async function isEncryptedBackup(blob: Blob) {
  const head = new Uint8Array(await blob.slice(0, MAGIC.length).arrayBuffer());
  return head.length === MAGIC.length && head.every((b, i) => b === MAGIC[i]);
}

/** Encrypts with a key derived from the header's salt; a fresh nonce must be in every header. */
export async function encryptBlob(blob: Blob, key: CryptoKey, header: BackupHeader): Promise<Blob> {
  const headerBytes = enc.encode(JSON.stringify(header));
  const nonce = unb64(header.nonce);
  const parts: BlobPart[] = [MAGIC, new Uint8Array([1]), u32(headerBytes.length), headerBytes];
  const count = Math.max(1, Math.ceil(blob.size / header.chunk));
  for (let i = 0; i < count; i++) {
    const plain = new Uint8Array(await blob.slice(i * header.chunk, (i + 1) * header.chunk).arrayBuffer());
    const sealed = await seal(key, chunkIv(nonce, i), plain, chunkAad(headerBytes, i === count - 1));
    parts.push(u32(sealed.length), sealed);
  }
  return new Blob(parts, { type: 'application/octet-stream' });
}

const DAMAGED = 'That encrypted backup is damaged.';

export async function readBackupHeader(blob: Blob): Promise<{ header: BackupHeader; headerBytes: Uint8Array<ArrayBuffer>; offset: number }> {
  if (!(await isEncryptedBackup(blob))) throw new Error('That file is not an encrypted backup.');
  const fixed = new DataView(await blob.slice(MAGIC.length, MAGIC.length + 5).arrayBuffer());
  if (fixed.byteLength < 5) throw new Error(DAMAGED);
  if (fixed.getUint8(0) !== 1) throw new Error('That encrypted backup was made by a newer version of Sheaf Notes.');
  const len = fixed.getUint32(1);
  const start = MAGIC.length + 5;
  const headerBytes = new Uint8Array(await blob.slice(start, start + len).arrayBuffer());
  let header: BackupHeader;
  try {
    header = JSON.parse(dec.decode(headerBytes));
  } catch {
    throw new Error(DAMAGED);
  }
  if (header.kdf !== 'PBKDF2-SHA256' || header.cipher !== 'AES-256-GCM') throw new Error(DAMAGED);
  return { header, headerBytes, offset: start + len };
}

/** The backup JSON inside an encrypted file. Throws WrongPassword for a wrong key or changed bytes. */
export async function decryptBlob(blob: Blob, key: CryptoKey): Promise<Blob> {
  const { header, headerBytes, offset: start } = await readBackupHeader(blob);
  const nonce = unb64(header.nonce);
  const parts: BlobPart[] = [];
  let offset = start;
  for (let i = 0; offset < blob.size; i++) {
    const lenBytes = new Uint8Array(await blob.slice(offset, offset + 4).arrayBuffer());
    if (lenBytes.length < 4) throw new WrongPassword('Wrong password, or the file is damaged.');
    const len = new DataView(lenBytes.buffer).getUint32(0);
    const end = offset + 4 + len;
    const sealed = new Uint8Array(await blob.slice(offset + 4, end).arrayBuffer());
    try {
      parts.push(await open(key, chunkIv(nonce, i), sealed, chunkAad(headerBytes, end >= blob.size)));
    } catch {
      throw new WrongPassword('Wrong password, or the file is damaged.');
    }
    offset = end;
  }
  if (!parts.length) throw new WrongPassword('Wrong password, or the file is damaged.');
  return new Blob(parts, { type: 'application/json' });
}
