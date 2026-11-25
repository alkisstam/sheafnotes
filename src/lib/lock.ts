import type { JSONContent } from '@tiptap/core';
import { flushEditor } from './actions';
import { createVault, openJson, rewrapVault, sealJson, unlockVault, WrongPassword, type VaultHeader } from './crypto';
import { db, getMeta, setMeta, uid } from './db';
import { docText, imageIdsIn, pdfIdsIn } from './doc';
import { store } from './store';
import type { Note } from './types';

/**
 * Locked notes keep their title, tags and notebook in the clear. Their content and text are encrypted with the vault's
 * data key, and the note row holds an empty document in their place, so lists, search and Tasks find nothing in them.
 */

export const LOCKED_DOC: JSONContent = { type: 'doc', content: [] };

/** Data keys of the vaults unlocked in this session, by vault id. Memory only. */
const keys = new Map<string, CryptoKey>();
/** Saves that still need a key; locking waits for them. */
const inflight = new Set<Promise<unknown>>();

const markUnlocked = () => store.setState({ unlocked: keys.size > 0 });

export const isLocked = (n: Pick<Note, 'locked'>) => Boolean(n.locked);
export const canLock = (n: Note) => n.kind === 'note';
export const isOpenable = (n: Note) => !n.locked || keys.has(n.locked.vault.id);

/** PDF and image ids a note uses, also when its content is encrypted. */
export const noteFileIds = (n: Note): string[] => (n.locked ? (n.fileIds ?? []) : [...pdfIdsIn(n.content), ...imageIdsIn(n.content)]);

export const localVault = () => getMeta<VaultHeader>('vault');

/** Vault headers this device knows of: its own, then any other found on a locked note, e.g. one synced from another device. */
async function knownVaults(): Promise<VaultHeader[]> {
  const out = new Map<string, VaultHeader>();
  const local = await localVault();
  if (local) out.set(local.id, local);
  await db.notes.each((n) => {
    if (n.locked && !out.has(n.locked.vault.id)) out.set(n.locked.vault.id, n.locked.vault);
  });
  return [...out.values()];
}

/** Whether locked notes exist that were made with a password this device has not seen. */
export async function hasForeignVault() {
  return !(await localVault()) && (await knownVaults()).length > 0;
}

/** Sets the app password for the first time. If notes locked elsewhere exist, the password must be theirs. */
export async function setPassword(password: string) {
  if (await localVault()) throw new Error('A password is already set.');
  if (await hasForeignVault()) {
    await unlock(password);
    return;
  }
  const { header, key } = await createVault(password);
  await setMeta('vault', header);
  keys.set(header.id, key);
  markUnlocked();
  armTimer();
}

/** Opens every vault the password fits. Throws WrongPassword if it fits none. */
export async function unlock(password: string) {
  const vaults = await knownVaults();
  let opened = 0;
  for (const v of vaults) {
    if (keys.has(v.id)) {
      opened++;
      continue;
    }
    try {
      keys.set(v.id, await unlockVault(v, password));
      opened++;
      if (!(await localVault())) await setMeta('vault', v);
    } catch (e) {
      if (!(e instanceof WrongPassword)) throw e;
    }
  }
  if (!opened) throw new WrongPassword();
  markUnlocked();
  armTimer();
}

/** Keeps the data key, so locked notes stay as they are; only their copy of the vault header is rewritten. */
export async function changePassword(oldPassword: string, newPassword: string) {
  const header = await localVault();
  if (!header) throw new Error('No password is set.');
  const next = await rewrapVault(header, oldPassword, newPassword);
  if (!keys.has(header.id)) keys.set(header.id, await unlockVault(next, newPassword));
  await db.transaction('rw', db.notes, db.meta, async () => {
    await setMeta('vault', next);
    await db.notes
      .filter((n) => n.locked?.vault.id === header.id)
      .modify((n) => {
        n.locked = { ...n.locked!, vault: next };
        n.updatedAt = Date.now();
      });
  });
  markUnlocked();
}

/** Forgets every key. Pending edits are saved first. */
export async function lockNow() {
  flushEditor();
  await Promise.all(inflight);
  keys.clear();
  clearTimeout(timer);
  markUnlocked();
}

function track<T>(p: Promise<T>) {
  inflight.add(p);
  void p.finally(() => inflight.delete(p)).catch(() => {});
  return p;
}

function keyFor(note: Note) {
  const key = note.locked && keys.get(note.locked.vault.id);
  if (!key) throw new Error('Unlock your locked notes first.');
  return key;
}

/** The content and text of a locked note. Needs the vault unlocked. */
export async function decryptNote(note: Note): Promise<{ content: JSONContent; text: string }> {
  if (!note.locked) return { content: note.content, text: note.text };
  return openJson(keyFor(note), note.locked, note.id);
}

async function sealed(note: Pick<Note, 'id'>, header: VaultHeader, key: CryptoKey, content: JSONContent): Promise<Partial<Note>> {
  const s = await sealJson(key, { content, text: docText(content) }, note.id);
  return {
    content: LOCKED_DOC,
    text: '',
    locked: { v: 1, vault: header, ...s },
    fileIds: [...new Set([...pdfIdsIn(content), ...imageIdsIn(content)])],
  };
}

/** Saves editor content into a locked note. */
export const saveLockedContent = (note: Note, content: JSONContent, writer: string) =>
  track(
    (async () => {
      const patch = await sealed(note, note.locked!.vault, keyFor(note), content);
      await db.notes.update(note.id, { ...patch, writer, updatedAt: Date.now() });
    })(),
  );

export async function lockNote(id: string) {
  flushEditor();
  await Promise.all(inflight);
  const header = await localVault();
  const key = header && keys.get(header.id);
  if (!header || !key) throw new Error('Unlock your locked notes first.');
  const note = await db.notes.get(id);
  if (!note || note.locked || !canLock(note)) return;
  const patch = await sealed(note, header, key, note.content);
  await db.notes.update(id, { ...patch, writer: `lock:${uid()}`, updatedAt: Date.now() });
  armTimer();
}

/** Takes the lock off a note for good. */
export async function removeLock(id: string) {
  flushEditor();
  await Promise.all(inflight);
  const note = await db.notes.get(id);
  if (!note?.locked) return;
  const { content } = await decryptNote(note);
  await db.notes.update(id, { content, text: docText(content), locked: null, fileIds: null, writer: `unlock:${uid()}`, updatedAt: Date.now() });
}

// ---- Auto-lock ----

export const LOCK_AFTER_DEFAULT = 5;
let timer: ReturnType<typeof setTimeout> | undefined;
let lockAfter = LOCK_AFTER_DEFAULT;

function armTimer() {
  clearTimeout(timer);
  if (keys.size && lockAfter > 0 && typeof window !== 'undefined') timer = setTimeout(() => void lockNow(), lockAfter * 60_000);
}

export async function setLockAfter(minutes: number) {
  lockAfter = minutes;
  await setMeta('lockAfter', minutes);
  armTimer();
}

/** Locks after a stretch without input, and when the computer locks or sleeps. */
export async function startAutoLock() {
  lockAfter = (await getMeta<number>('lockAfter')) ?? LOCK_AFTER_DEFAULT;
  let last = 0;
  const activity = () => {
    // Re-arming on every key press is wasteful; once a second is plenty.
    if (!keys.size || Date.now() - last < 1000) return;
    last = Date.now();
    armTimer();
  };
  for (const ev of ['pointerdown', 'keydown', 'wheel', 'touchstart']) window.addEventListener(ev, activity, { passive: true, capture: true });
  window.desktop?.onLockScreen?.(() => void lockNow());
}

/** Opens the password dialog that fits: set a first password, or unlock. Resolves true once unlocked. */
export function askToUnlock(): Promise<boolean> {
  return new Promise((resolve) => {
    void (async () => {
      const first = !(await localVault()) && !(await hasForeignVault());
      const foreign = await hasForeignVault();
      store.getState().setDialog({
        kind: 'password',
        title: first ? 'Set a password for locked notes' : 'Unlock locked notes',
        body: first
          ? 'One password locks all your notes. Titles, tags and notebooks stay visible. If you forget this password, locked notes cannot be recovered.'
          : foreign
            ? 'These notes were locked on another device. Enter the password used there.'
            : undefined,
        repeat: first,
        confirm: first ? 'Set password' : 'Unlock',
        onSubmit: async (pw) => {
          try {
            if (first) await setPassword(pw);
            else await unlock(pw);
            resolve(true);
            return null;
          } catch (e) {
            return e instanceof WrongPassword ? 'That password is not right.' : (e as Error).message;
          }
        },
        onCancel: () => resolve(false),
      });
    })();
  });
}

/** Locks a note, asking for the password first when none is set or the session is locked. */
export async function lockNoteFlow(id: string) {
  const header = await localVault();
  if (!header || !keys.has(header.id)) {
    if (!(await askToUnlock())) return;
  }
  try {
    await lockNote(id);
    store.getState().showToast({ message: 'Note locked' });
  } catch (e) {
    store.getState().showToast({ message: (e as Error).message });
  }
}

export async function removeLockFlow(id: string) {
  const note = await db.notes.get(id);
  if (!note) return;
  if (!isOpenable(note) && !(await askToUnlock())) return;
  try {
    await removeLock(id);
    store.getState().showToast({ message: 'Lock removed' });
  } catch (e) {
    store.getState().showToast({ message: (e as Error).message });
  }
}
