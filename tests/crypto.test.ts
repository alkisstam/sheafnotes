import 'fake-indexeddb/auto';
import { readFileSync } from 'node:fs';
import { beforeEach, describe, expect, it } from 'vitest';
import { baseNote, collectOrphanFiles, saveContent } from '../src/lib/actions';
import { buildBackup, decryptBackup, encryptBackup, importBackup, NeedsPassword } from '../src/lib/backup';
import {
  createVault, decryptBlob, deriveKey, encryptBlob, newBackupHeader, openJson, random, rewrapVault, unb64, unlockVault, WrongPassword,
} from '../src/lib/crypto';
import { db } from '../src/lib/db';
import { changePassword, decryptNote, lockNote, lockNow, noteFileIds, removeLock, setPassword, unlock } from '../src/lib/lock';
import { store } from '../src/lib/store';
import type { Note } from '../src/lib/types';

const vectors = JSON.parse(readFileSync(new URL('./fixtures/crypto-vectors.json', import.meta.url), 'utf8'));
const doc = (text: string, fileId?: string) => ({
  type: 'doc',
  content: [
    { type: 'paragraph', content: [{ type: 'text', text }] },
    ...(fileId ? [{ type: 'pdfAttachment', attrs: { fileId, name: 'a.pdf' } }] : []),
  ],
});

describe('crypto', () => {
  it('opens the shared vectors', async () => {
    const key = await unlockVault(vectors.vault, vectors.password);
    expect(await openJson(key, vectors.note.locked, vectors.note.id)).toEqual(vectors.note.plain);
    await expect(openJson(key, vectors.note.locked, 'another-note')).rejects.toBeInstanceOf(WrongPassword);
    const file = new Blob([unb64(vectors.backup.file)]);
    expect(await (await decryptBackup(file, vectors.password)).text()).toBe(vectors.backup.plain);
    // The same password, typed decomposed, still works.
    expect(await (await decryptBackup(file, vectors.password.normalize('NFD'))).text()).toBe(vectors.backup.plain);
  });

  it('rejects a wrong password, and keeps the data key when the password changes', async () => {
    const { header, key } = await createVault('one', 1000);
    await expect(unlockVault(header, 'two')).rejects.toBeInstanceOf(WrongPassword);
    const next = await rewrapVault(header, 'one', 'two', 1000);
    expect(next.id).toBe(header.id);
    await expect(rewrapVault(header, 'nope', 'two', 1000)).rejects.toBeInstanceOf(WrongPassword);
    const again = await unlockVault(next, 'two');
    const sealed = await crypto.subtle.encrypt({ name: 'AES-GCM', iv: new Uint8Array(12) }, key, new Uint8Array([1, 2, 3]));
    expect(new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: new Uint8Array(12) }, again, sealed))).toEqual(new Uint8Array([1, 2, 3]));
  });

  it('round-trips backups of any size, and refuses changed, cut or reordered files', async () => {
    const salt = random(16);
    const key = await deriveKey('pw', salt, 1000);
    const header = { ...newBackupHeader(salt, 1000), chunk: 10 };
    for (const text of ['', 'short', 'x'.repeat(10), 'y'.repeat(35)]) {
      const sealed = await encryptBlob(new Blob([text]), key, header);
      expect(await (await decryptBlob(sealed, key)).text()).toBe(text);
    }
    const sealed = new Uint8Array(await (await encryptBlob(new Blob(['z'.repeat(35)]), key, header)).arrayBuffer());
    const bad = sealed.slice();
    bad[bad.length - 1] ^= 1;
    await expect(decryptBlob(new Blob([bad]), key)).rejects.toBeInstanceOf(WrongPassword);
    // Chunks are 4 + 10 + 16 bytes, and the last one 4 + 5 + 16. Cut off the last chunk, or add one.
    await expect(decryptBlob(new Blob([sealed.slice(0, sealed.length - 25)]), key)).rejects.toBeInstanceOf(WrongPassword);
    await expect(decryptBlob(new Blob([sealed, sealed.slice(-25)]), key)).rejects.toBeInstanceOf(WrongPassword);
    const headerEnd = sealed.length - 3 * 30 - 25;
    const swapped = new Uint8Array([...sealed.slice(0, headerEnd), ...sealed.slice(headerEnd + 30, headerEnd + 60), ...sealed.slice(headerEnd, headerEnd + 30), ...sealed.slice(headerEnd + 60)]);
    await expect(decryptBlob(new Blob([swapped]), key)).rejects.toBeInstanceOf(WrongPassword);
    await expect(decryptBlob(new Blob([sealed]), await deriveKey('other', salt, 1000))).rejects.toBeInstanceOf(WrongPassword);
  });
});

describe('locked notes', () => {
  beforeEach(async () => {
    await lockNow();
    await Promise.all(db.tables.map((t) => t.clear()));
  });

  it('encrypts a note, keeps editing it encrypted, and opens only with the password', async () => {
    const note = baseNote({ title: 'Plans', content: doc('Secret plan', 'f1') });
    await db.notes.add(note);
    await setPassword('hunter2');
    await lockNote(note.id);

    let row = (await db.notes.get(note.id))!;
    expect(row.title).toBe('Plans');
    expect(row.text).toBe('');
    expect(JSON.stringify(row)).not.toContain('Secret');
    expect(noteFileIds(row)).toEqual(['f1']);
    expect((await decryptNote(row)).text).toBe('Secret plan\na.pdf');

    await saveContent(note.id, doc('Edited secret') as Note['content'], 'w:1');
    row = (await db.notes.get(note.id))!;
    expect(JSON.stringify(row)).not.toContain('Edited');
    expect((await decryptNote(row)).text).toBe('Edited secret');
    expect(row.fileIds).toEqual([]);

    await lockNow();
    expect(store.getState().unlocked).toBe(false);
    await expect(decryptNote(row)).rejects.toThrow();
    await expect(unlock('wrong')).rejects.toBeInstanceOf(WrongPassword);
    await unlock('hunter2');
    expect(store.getState().unlocked).toBe(true);

    await changePassword('hunter2', 'correct horse');
    await lockNow();
    await expect(unlock('hunter2')).rejects.toBeInstanceOf(WrongPassword);
    await unlock('correct horse');
    await removeLock(note.id);
    row = (await db.notes.get(note.id))!;
    expect(row.locked).toBeNull();
    expect(row.text).toBe('Edited secret');
  });

  it('opens notes locked on another device with that device\'s password', async () => {
    const note = baseNote({ content: doc('From afar') });
    await db.notes.add(note);
    await setPassword('afar');
    await lockNote(note.id);
    await lockNow();
    await db.meta.clear();
    await expect(setPassword('mine')).rejects.toBeInstanceOf(WrongPassword);
    await setPassword('afar');
    expect((await decryptNote((await db.notes.get(note.id))!)).text).toBe('From afar');
  });

  it('keeps the files of locked notes, and survives backups, encrypted or not', async () => {
    const note = baseNote({ title: 'With file', content: doc('Has a PDF', 'p1') });
    await db.notes.add(note);
    await db.files.add({ id: 'p1', name: 'a.pdf', size: 1, pageCount: 1, pageTexts: [''], thumb: null, encrypted: false, createdAt: 0 });
    await setPassword('pw');
    await lockNote(note.id);
    await collectOrphanFiles();
    expect(await db.files.get('p1')).toBeDefined();

    const plain = await buildBackup();
    const sealed = await encryptBackup(plain, 'backup pw');
    expect(await sealed.text()).not.toContain('With file');
    await db.notes.clear();
    await expect(importBackup(sealed)).rejects.toBeInstanceOf(NeedsPassword);
    await expect(importBackup(sealed, { password: 'nope' })).rejects.toBeInstanceOf(WrongPassword);
    expect((await importBackup(sealed, { password: 'backup pw' })).added).toBe(1);
    const back = (await db.notes.get(note.id))!;
    expect((await decryptNote(back)).text).toBe('Has a PDF\na.pdf');

    await db.notes.clear();
    expect((await importBackup(plain)).added).toBe(1);
  });
});
