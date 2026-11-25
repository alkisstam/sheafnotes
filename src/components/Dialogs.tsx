import { useLiveQuery } from 'dexie-react-hooks';
import { Bell, BellOff, Download, FileClock, Lock, LockKeyhole, FolderPlus, HardDrive, Laptop, Moon, QrCode, RefreshCw, Sun, Upload, X } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { autoBackupEncrypted, autoBackupSupported, chooseBackupFile, exportBackup, importBackup, NeedsPassword, setAutoBackupPassword, stopAutoBackup } from '../lib/backup';
import { WrongPassword } from '../lib/crypto';
import { askToUnlock, changePassword, LOCK_AFTER_DEFAULT, localVault, lockNow, setLockAfter } from '../lib/lock';
import { relativeTime } from '../lib/dates';
import { db } from '../lib/db';
import { formatBytes } from '../lib/pdf';
import { foldQuery } from '../lib/search';
import { store, useStore, type DialogState, type ThemePref } from '../lib/store';
import { disableServerSync, enableServerSync, syncNow } from '../lib/sync';
import { changeTags, createNotebook, patchNotes } from '../lib/actions';
import { HUES, noteBg, PALETTES } from '../lib/colors';
import { EXPORT_FORMATS, exportNotes, shareFormats, type ExportMode } from '../lib/export';
import { normalizeTag } from '../lib/tags';

export const THEMES: { id: ThemePref; label: string; icon: typeof Sun }[] = [
  { id: 'system', label: 'System', icon: Laptop },
  { id: 'light', label: 'Light', icon: Sun },
  { id: 'dark', label: 'Dark', icon: Moon },
];

function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: React.ReactNode; wide?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const d = ref.current!;
    if (!d.open) d.showModal();
    return () => d.close();
  }, []);
  return (
    <dialog
      ref={ref}
      className="dialog"
      data-wide={wide}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onClick={(e) => {
        if (e.target === ref.current) onClose();
      }}
    >
      <div className="dialog-body">
        <header>
          <h2>{title}</h2>
          <button type="button" className="icon-btn" aria-label="Close" onClick={onClose}><X size={16} /></button>
        </header>
        {children}
      </div>
    </dialog>
  );
}

function Prompt({ dialog, close }: { dialog: Extract<DialogState, { kind: 'prompt' }>; close: () => void }) {
  const [value, setValue] = useState(dialog.value);
  return (
    <Modal title={dialog.title} onClose={close}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (!value.trim() && !dialog.allowEmpty) return;
          dialog.onConfirm(value);
          close();
        }}
      >
        <label className="field">
          <span>{dialog.label}</span>
          <input autoFocus value={value} onChange={(e) => setValue(e.target.value)} onFocus={(e) => e.target.select()} />
        </label>
        {dialog.hint && <p className="dialog-hint">{dialog.hint}</p>}
        <footer>
          <button type="button" className="btn" onClick={close}>Cancel</button>
          <button type="submit" className="btn primary" disabled={!value.trim() && !dialog.allowEmpty}>{dialog.confirm}</button>
        </footer>
      </form>
    </Modal>
  );
}

function PasswordDialog({ dialog, close }: { dialog: Extract<DialogState, { kind: 'password' }>; close: () => void }) {
  const [current, setCurrent] = useState('');
  const [pw, setPw] = useState('');
  const [again, setAgain] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const mismatch = dialog.repeat && again && pw !== again;
  const ready = pw && (!dialog.repeat || pw === again) && (!dialog.current || current) && !busy;
  const cancel = () => {
    if (busy) return;
    dialog.onCancel?.();
    close();
  };
  return (
    <Modal title={dialog.title} onClose={cancel}>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          if (!ready) return;
          setBusy(true);
          setError(null);
          const err = await dialog.onSubmit(pw, current).catch((x: Error) => x.message);
          setBusy(false);
          if (err) setError(err);
          else close();
        }}
      >
        {dialog.body && <p className="dialog-hint">{dialog.body}</p>}
        {dialog.current && (
          <label className="field">
            <span>Current password</span>
            <input type="password" autoFocus autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />
          </label>
        )}
        <label className="field">
          <span>{dialog.current ? 'New password' : 'Password'}</span>
          <input type="password" autoFocus={!dialog.current} autoComplete={dialog.repeat ? 'new-password' : 'current-password'} value={pw} onChange={(e) => setPw(e.target.value)} />
        </label>
        {dialog.repeat && (
          <label className="field">
            <span>Repeat password</span>
            <input type="password" autoComplete="new-password" value={again} onChange={(e) => setAgain(e.target.value)} />
          </label>
        )}
        {(error || mismatch) && <p className="dialog-error" role="alert">{error ?? 'The passwords do not match.'}</p>}
        <footer>
          <button type="button" className="btn" onClick={cancel} disabled={busy}>Cancel</button>
          <button type="submit" className="btn primary" disabled={!ready}>{busy ? 'Working…' : dialog.confirm}</button>
        </footer>
      </form>
    </Modal>
  );
}

function PickNote({ dialog, close }: { dialog: Extract<DialogState, { kind: 'pickNote' }>; close: () => void }) {
  const [query, setQuery] = useState('');
  const input = useRef<HTMLInputElement>(null);
  // After the Modal's showModal(), which moves focus to its close button.
  useEffect(() => input.current?.focus(), []);
  const current = useStore((s) => s.noteId);
  const notes = useLiveQuery(() => db.notes.filter((n) => !n.trashedAt && n.id !== current).toArray(), [current], []);
  const shown = useMemo(() => {
    const q = foldQuery(query);
    return notes
      .map((n) => ({ n, title: n.title || 'Untitled' }))
      .filter(({ title }) => !q || foldQuery(title).includes(q))
      .sort((a, b) => b.n.updatedAt - a.n.updatedAt)
      .slice(0, 50);
  }, [notes, query]);
  const pick = (id: string, title: string) => {
    close();
    dialog.onPick(id, title);
  };
  return (
    <Modal title="Link to note" onClose={close}>
      <form
        className="pick-note"
        onSubmit={(e) => {
          e.preventDefault();
          if (shown[0]) pick(shown[0].n.id, shown[0].title);
        }}
      >
        <label className="field">
          <span className="sr-only">Search notes</span>
          <input ref={input} value={query} placeholder="Search notes" onChange={(e) => setQuery(e.target.value)} />
        </label>
        <div className="pick-note-list" role="listbox" aria-label="Notes">
          {shown.length === 0 && <p className="dialog-hint">No matching note</p>}
          {shown.map(({ n, title }) => (
            <button key={n.id} type="button" role="option" aria-selected={false} onClick={() => pick(n.id, title)}>{title}{n.text && <small>{n.text}</small>}</button>
          ))}
        </div>
        {dialog.onUnlink && (
          <footer>
            <button
              type="button"
              className="btn"
              onClick={() => {
                close();
                dialog.onUnlink!();
              }}
            >Remove link</button>
          </footer>
        )}
      </form>
    </Modal>
  );
}

type InstallEvent = Event & { prompt: () => Promise<void>; userChoice: Promise<{ outcome: string }> };
let installEvent: InstallEvent | null = null;
const installListeners = new Set<() => void>();
window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  installEvent = e as InstallEvent;
  installListeners.forEach((l) => l());
});
window.addEventListener('appinstalled', () => {
  installEvent = null;
  installListeners.forEach((l) => l());
});

const SHORTCUTS: [string, string][] = [
  ['Ctrl K', 'Search'],
  ['Alt N', 'New note'],
  ['Alt T', "Today's note"],
  ['Ctrl \\', 'Focus mode'],
  ['/', 'Insert a block'],
  ['Ctrl click', 'Open a link'],
];

/** Reminders only fire while the app runs, so this says where, and on desktop lets it keep running. */
function RemindersSettings() {
  const [perm, setPerm] = useState(() => ('Notification' in window ? Notification.permission : 'denied'));
  const [prefs, setPrefs] = useState<{ background: boolean; autostart: boolean } | null>(null);
  useEffect(() => {
    void window.desktop?.prefs().then(setPrefs);
  }, []);
  const change = (p: { background?: boolean; autostart?: boolean }) => {
    setPrefs((old) => old && { ...old, ...p });
    void window.desktop!.setPrefs(p);
  };
  return (
    <section className="settings-section">
      <h3>Reminders</h3>
      {window.desktop ? (
        <>
          <p>Reminders show while Sheaf Notes is running.</p>
          {prefs && (
            <div className="settings-checks">
              <label><input type="checkbox" checked={prefs.background} onChange={(e) => change({ background: e.target.checked })} /> Keep running in the tray when the window is closed</label>
              <label><input type="checkbox" checked={prefs.autostart} onChange={(e) => change({ autostart: e.target.checked })} /> Start at login, in the tray</label>
            </div>
          )}
        </>
      ) : (
        <>
          <p>Reminders show while Sheaf Notes is open in a tab or as an installed app. The phone app reminds you even when it is closed.</p>
          {perm === 'default' && (
            <div className="settings-row">
              <button type="button" className="btn" onClick={() => void Notification.requestPermission().then(setPerm)}><Bell size={14} /> Allow notifications</button>
            </div>
          )}
          {perm === 'denied' && <p className="settings-stat"><BellOff size={14} /><span>Notifications are blocked. Allow them in the browser's site settings.</span></p>}
        </>
      )}
    </section>
  );
}

function Settings({ close }: { close: () => void }) {
  const theme = useStore((s) => s.theme);
  const setTheme = useStore((s) => s.setTheme);
  const showToast = useStore((s) => s.showToast);
  const counts = useLiveQuery(async () => ({
    notes: await db.notes.filter((n) => !n.trashedAt).count(),
    files: await db.files.count(),
    images: await db.images.count(),
  }), []);
  const autoBackup = useLiveQuery(async () => {
    const handle = (await db.meta.get('backupFile'))?.value as FileSystemFileHandle | undefined;
    const at = (await db.meta.get('backupWrittenTime'))?.value as number | undefined;
    return handle ? { name: handle.name, at, encrypted: await autoBackupEncrypted() } : null;
  }, []);
  const lock = useLiveQuery(async () => ({
    set: Boolean(await localVault()),
    after: ((await db.meta.get('lockAfter'))?.value as number | undefined) ?? LOCK_AFTER_DEFAULT,
    count: await db.notes.filter((n) => Boolean(n.locked) && !n.trashedAt).count(),
  }), []);
  const unlocked = useStore((s) => s.unlocked);

  const restore = async (file: File, password?: string): Promise<void> => {
    try {
      const { added, updated, skipped } = await importBackup(file, { password });
      showToast({ message: `Restored: ${added} added, ${updated} updated, ${skipped} already current.` });
      await syncNow(true);
    } catch (err) {
      if (!(err instanceof NeedsPassword)) return showToast({ message: (err as Error).message });
      store.getState().setDialog({
        kind: 'password',
        title: 'Restore encrypted backup',
        body: `Enter the password ${file.name} was encrypted with.`,
        confirm: 'Restore',
        onSubmit: async (pw) => {
          try {
            const { added, updated, skipped } = await importBackup(file, { password: pw });
            showToast({ message: `Restored: ${added} added, ${updated} updated, ${skipped} already current.` });
            void syncNow(true);
            return null;
          } catch (e) {
            return e instanceof WrongPassword ? 'Wrong password, or the file is damaged.' : (e as Error).message;
          }
        },
      });
    }
  };

  const exportEncrypted = () =>
    store.getState().setDialog({
      kind: 'password',
      title: 'Export encrypted backup',
      body: 'The backup is encrypted with AES-256, using a key made from this password. Keep the password safe: without it the backup cannot be restored.',
      repeat: true,
      confirm: 'Export',
      onSubmit: async (pw) => {
        try {
          await exportBackup(pw);
          return null;
        } catch {
          return 'The backup could not be created.';
        }
      },
    });

  const toggleBackupEncryption = () => {
    if (autoBackup?.encrypted) {
      void setAutoBackupPassword(null).then(() => syncNow(true));
      return;
    }
    store.getState().setDialog({
      kind: 'password',
      title: 'Encrypt the auto-backup file',
      body: 'This device keeps a key made from the password, not the password itself. On your other computers, enter the same password when asked.',
      repeat: true,
      confirm: 'Encrypt',
      onSubmit: async (pw) => {
        await setAutoBackupPassword(pw);
        void syncNow(true);
        return null;
      },
    });
  };

  const changeLockPassword = () =>
    store.getState().setDialog({
      kind: 'password',
      title: 'Change password',
      body: 'Locked notes stay locked. Other devices use the new password once they sync.',
      current: true,
      repeat: true,
      confirm: 'Change',
      onSubmit: async (pw, cur) => {
        try {
          await changePassword(cur, pw);
          showToast({ message: 'Password changed' });
          return null;
        } catch (e) {
          return e instanceof WrongPassword ? 'The current password is not right.' : (e as Error).message;
        }
      },
    });
  const sync = useLiveQuery(async () => {
    const on = !!(await db.meta.get('syncToken'))?.value;
    const at = (await db.meta.get('syncedAt'))?.value as number | undefined;
    return { on, at };
  }, []);
  const [token, setToken] = useState('');
  // The desktop app has no server behind it, so it asks for one; the web app syncs with the server it came from.
  const [server, setServer] = useState('https://');
  const [pairQr, setPairQr] = useState<string | null>(null);
  const [usage, setUsage] = useState<string | null>(null);
  const [persisted, setPersisted] = useState<boolean | null>(null);
  const [, bump] = useState(0);
  const fileRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    void window.desktop?.syncServer().then((s) => s && setServer(s));
  }, []);

  useEffect(() => {
    void navigator.storage?.estimate?.().then((e) => setUsage(`${formatBytes(e.usage ?? 0)} of ${formatBytes(e.quota ?? 0)} available`));
    void navigator.storage?.persisted?.().then(setPersisted);
    const l = () => bump((n) => n + 1);
    installListeners.add(l);
    return () => {
      installListeners.delete(l);
    };
  }, []);

  return (
    <Modal title="Settings" onClose={close} wide>
      <section className="settings-section">
        <h3>Appearance</h3>
        <div className="segmented" role="radiogroup" aria-label="Theme">
          {THEMES.map(({ id, label, icon: Icon }) => (
            <button key={id} type="button" role="radio" aria-checked={theme === id} onClick={() => setTheme(id)}>
              <Icon size={14} /> {label}
            </button>
          ))}
        </div>
      </section>

      <RemindersSettings />

      <section className="settings-section">
        <h3>Your data</h3>
        <p>
          Everything stays in this browser. Clearing site data removes it, so keep a backup.
        </p>
        <p className="settings-stat">
          <HardDrive size={14} />
          <span>
            {counts ? `${counts.notes} ${counts.notes === 1 ? 'note' : 'notes'}, ${counts.files} ${counts.files === 1 ? 'PDF' : 'PDFs'}, ${counts.images} ${counts.images === 1 ? 'image' : 'images'}` : '…'}
            {usage ? `. ${usage}.` : ''}
            {persisted === true ? ' Protected from automatic cleanup.' : ''}
          </span>
        </p>
        <div className="settings-row">
          <button
            type="button"
            className="btn"
            onClick={() => void exportBackup().catch(() => showToast({ message: 'The backup could not be created.' }))}
          >
            <Download size={14} /> Export backup
          </button>
          <button type="button" className="btn" onClick={exportEncrypted}>
            <LockKeyhole size={14} /> Export encrypted
          </button>
          <button type="button" className="btn" onClick={() => fileRef.current?.click()}>
            <Upload size={14} /> Restore backup
          </button>
          {persisted === false && (
            <button
              type="button"
              className="btn"
              onClick={() => void navigator.storage.persist().then((ok) => {
                setPersisted(ok);
                showToast({ message: ok ? 'Storage is now protected.' : 'The browser declined. Installing the app usually helps.' });
              })}
            >
              Protect storage
            </button>
          )}
          <input
            ref={fileRef}
            hidden
            type="file"
            accept="application/json,.json,.sheafenc"
            aria-label="Backup file"
            onChange={(e) => {
              const file = e.target.files?.[0];
              e.target.value = '';
              if (file) void restore(file);
            }}
          />
        </div>
        <p className="dialog-hint">Restoring merges with what is here. If a note exists in both, the newer copy wins. Encrypted backups use AES-256 with a key made from your password; without the password they cannot be restored.</p>
        {autoBackupSupported && (
          <>
            <p className="settings-stat">
              <FileClock size={14} />
              <span>
                {autoBackup
                  ? `Auto-backup to ${autoBackup.name}${autoBackup.encrypted ? ', encrypted' : ''}${autoBackup.at ? `, last saved ${relativeTime(autoBackup.at)}` : ''}.`
                  : 'Auto-backup is off.'}
              </span>
            </p>
            <div className="settings-row">
              <button
                type="button"
                className="btn"
                onClick={() => void chooseBackupFile()
                  .then(() => syncNow(true))
                  .catch((err: Error) => err.name !== 'AbortError' && showToast({ message: 'Auto-backup could not be set up.' }))}
              >
                <FileClock size={14} /> {autoBackup ? 'Change file' : 'Auto-backup to a file'}
              </button>
              {autoBackup && (
                <button type="button" className="btn" onClick={toggleBackupEncryption}>
                  <LockKeyhole size={14} /> {autoBackup.encrypted ? 'Stop encrypting' : 'Encrypt file'}
                </button>
              )}
              {autoBackup && (
                <button type="button" className="btn" onClick={() => void stopAutoBackup()}>
                  Stop
                </button>
              )}
            </div>
            <p className="dialog-hint">
              The file is rewritten about a minute after each change. Put it in a Dropbox, Drive or Syncthing folder and choose the same file on your other computers to keep them in sync.
            </p>
          </>
        )}
      </section>

      <section className="settings-section">
        <h3>Locked notes</h3>
        <p className="settings-stat">
          <Lock size={14} />
          <span>
            {!lock ? '…' : !lock.set ? 'No password is set. Choose Lock note in a note\'s menu to set one.' : `${lock.count} locked ${lock.count === 1 ? 'note' : 'notes'}. ${unlocked ? 'Unlocked for now.' : 'Locked.'}`}
          </span>
        </p>
        <div className="settings-row">
          {lock?.set ? (
            <>
              {unlocked ? (
                <button type="button" className="btn" onClick={() => void lockNow()}><Lock size={14} /> Lock now</button>
              ) : (
                <button type="button" className="btn" onClick={() => void askToUnlock()}>Unlock</button>
              )}
              <button type="button" className="btn" onClick={changeLockPassword}>Change password</button>
            </>
          ) : (
            <button type="button" className="btn" onClick={() => void askToUnlock()}>Set password</button>
          )}
          <label className="settings-inline">
            <span>Lock again after</span>
            <select value={lock?.after ?? LOCK_AFTER_DEFAULT} onChange={(e) => void setLockAfter(Number(e.target.value))}>
              <option value={1}>1 minute</option>
              <option value={5}>5 minutes</option>
              <option value={15}>15 minutes</option>
              <option value={60}>1 hour</option>
              <option value={0}>Only when I lock</option>
            </select>
          </label>
        </div>
        <p className="dialog-hint">
          Locked notes are encrypted with AES-256. Their titles, tags and notebooks stay visible, and their attached PDFs and images are not encrypted. If you forget the password, locked notes cannot be recovered.
        </p>
      </section>

      <section className="settings-section">
        <h3>Sync</h3>
        <p>
          {window.desktop
            ? 'Keep your devices in step through your Sheaf Notes server. Notes are sent to that server over HTTPS.'
            : 'Keep your devices in step through the server this app is hosted on. Notes are sent to that server over HTTPS.'}
        </p>
        {sync?.on ? (
          <>
            <p className="settings-stat">
              <RefreshCw size={14} />
              <span>{sync.at ? `Last synced ${relativeTime(sync.at)}.` : 'Not synced yet.'}</span>
            </p>
            <div className="settings-row">
              <button type="button" className="btn" onClick={() => void syncNow()}>
                <RefreshCw size={14} /> Sync now
              </button>
              <button
                type="button"
                className="btn"
                onClick={async () => {
                  if (pairQr) return setPairQr(null);
                  const { toDataURL } = await import('qrcode');
                  const token = (await db.meta.get('syncToken'))?.value as string;
                  const url = window.desktop ? await window.desktop.syncServer() : new URL('.', location.href).href;
                  setPairQr(await toDataURL(JSON.stringify({ url, token }), { margin: 1, width: 240 }));
                }}
              >
                <QrCode size={14} /> {pairQr ? 'Hide code' : 'Pair a phone'}
              </button>
              <button type="button" className="btn" onClick={() => void disableServerSync().then(() => setPairQr(null))}>
                Turn off
              </button>
            </div>
            {pairQr && (
              <>
                <img className="pair-qr" src={pairQr} alt="Pairing code with the sync address and token" width={240} height={240} />
                <p className="dialog-hint">Scan this in the Sheaf Notes Android app. It contains your sync token, so don't share it.</p>
              </>
            )}
          </>
        ) : (
          <form
            className="settings-row"
            onSubmit={async (e) => {
              e.preventDefault();
              if (!token.trim()) return;
              try {
                await window.desktop?.setSyncServer(server.trim());
              } catch {
                return showToast({ message: 'Enter the server address, starting with https://' });
              }
              await enableServerSync(token);
              setToken('');
            }}
          >
            {window.desktop && (
              <input
                type="url"
                autoComplete="off"
                placeholder="Server address"
                aria-label="Server address"
                value={server}
                onChange={(e) => setServer(e.target.value)}
              />
            )}
            <input
              type="password"
              autoComplete="off"
              placeholder="Sync token"
              aria-label="Sync token"
              value={token}
              onChange={(e) => setToken(e.target.value)}
            />
            <button type="submit" className="btn">
              <RefreshCw size={14} /> Turn on sync
            </button>
          </form>
        )}
        <p className="dialog-hint">Changes sync about a minute after you make them, and when you come back to the app. If a note was edited on two devices, the newer edit wins.</p>
      </section>

      {installEvent && (
        <section className="settings-section">
          <h3>Install</h3>
          <p>Run Sheaf Notes in its own window, with or without a connection.</p>
          <div className="settings-row">
            <button
              type="button"
              className="btn primary"
              onClick={() => void installEvent?.prompt().then(() => {
                installEvent = null;
                bump((n) => n + 1);
              })}
            >
              Install app
            </button>
          </div>
        </section>
      )}

      <section className="settings-section">
        <h3>Shortcuts</h3>
        <dl className="shortcuts">
          {SHORTCUTS.map(([k, v]) => (
            <div key={k}><dt><kbd>{k}</kbd></dt><dd>{v}</dd></div>
          ))}
        </dl>
      </section>
    </Modal>
  );
}

function NoteColor({ noteIds, close }: { noteIds: string[]; close: () => void }) {
  const notes = useLiveQuery(() => db.notes.bulkGet(noteIds), [noteIds]);
  const colors = new Set(notes?.map((n) => n?.color ?? null));
  const current = colors.size === 1 ? [...colors][0] : null;
  return (
    <Modal title="Background color" onClose={close}>
      {PALETTES.map((p) => (
        <section key={p.id} className="color-row">
          <h3>{p.label}</h3>
          <div className="color-swatches">
            {HUES.map((h) => {
              const id = `${p.id}-${h.id}`;
              return (
                <button
                  key={id}
                  type="button"
                  aria-label={`${p.label} ${h.label}`}
                  title={h.label}
                  aria-pressed={current === id}
                  style={{ background: noteBg(id) }}
                  onClick={() => void patchNotes(noteIds, { color: id })}
                />
              );
            })}
          </div>
        </section>
      ))}
      <footer>
        <button type="button" className="btn" disabled={colors.size === 1 && !current} onClick={() => void patchNotes(noteIds, { color: null })}>No color</button>
      </footer>
    </Modal>
  );
}

function ExportNote({ noteIds, mode, close }: { noteIds: string[]; mode: ExportMode; close: () => void }) {
  const setSelected = useStore((s) => s.setSelected);
  return (
    <Modal title={`${mode === 'share' ? 'Share' : 'Export'} ${noteIds.length === 1 ? 'note' : `${noteIds.length} notes`}`} onClose={close}>
      <div className="pick-note-list">
        {(mode === 'share' ? shareFormats() : EXPORT_FORMATS).map((f) => (
          <button
            key={f.id}
            type="button"
            onClick={() => {
              close();
              setSelected(null);
              void exportNotes(noteIds, f.id, mode);
            }}
          >
            {f.label}
          </button>
        ))}
      </div>
    </Modal>
  );
}

function PickNotebook({ dialog, close }: { dialog: Extract<DialogState, { kind: 'pickNotebook' }>; close: () => void }) {
  const notebooks = useLiveQuery(() => db.notebooks.orderBy('createdAt').toArray(), [], []);
  const setDialog = useStore((s) => s.setDialog);
  const pick = (id: string | null) => {
    close();
    dialog.onPick(id);
  };
  return (
    <Modal title="Select Notebook" onClose={close}>
      <div className="pick-note-list">
        <button type="button" onClick={() => pick(null)}>No notebook</button>
        {notebooks.map((b) => <button key={b.id} type="button" onClick={() => pick(b.id)}>{b.name || 'Untitled'}</button>)}
        <button
          type="button"
          className="pick-create"
          onClick={() => setDialog({ kind: 'prompt', title: 'Create Notebook', label: 'Name', value: '', confirm: 'Create', onConfirm: (name) => void createNotebook(name, false).then(dialog.onPick) })}
        >
          <FolderPlus size={14} /> Create Notebook
        </button>
      </div>
    </Modal>
  );
}

function BulkTags({ dialog, close }: { dialog: Extract<DialogState, { kind: 'bulkTags' }>; close: () => void }) {
  const setSelected = useStore((s) => s.setSelected);
  const [on, setOn] = useState(dialog.common);
  const [created, setCreated] = useState<string[]>([]);
  const [value, setValue] = useState('');
  const shown = [...new Set([...dialog.allTags, ...created])].sort();
  const add = () => {
    const tag = normalizeTag(value);
    if (tag) {
      setCreated((c) => [...c, tag]);
      setOn((o) => (o.includes(tag) ? o : [...o, tag]));
    }
    setValue('');
  };
  return (
    <Modal title="Tags" onClose={close}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          add();
        }}
      >
        <div className="tag-picks">
          {shown.map((t) => (
            <button key={t} type="button" aria-pressed={on.includes(t)} onClick={() => setOn(on.includes(t) ? on.filter((x) => x !== t) : [...on, t])}>{t}</button>
          ))}
        </div>
        <label className="field">
          <span>New tag</span>
          <input value={value} placeholder="Like work/ideas" onChange={(e) => setValue(e.target.value)} />
        </label>
        <footer>
          <button type="button" className="btn" onClick={close}>Cancel</button>
          <button
            type="button"
            className="btn primary"
            onClick={() => {
              void changeTags(dialog.noteIds, on.filter((t) => !dialog.common.includes(t)), dialog.common.filter((t) => !on.includes(t)));
              setSelected(null);
              close();
            }}
          >
            Save
          </button>
        </footer>
      </form>
    </Modal>
  );
}

export function Dialogs() {
  const dialog = useStore((s) => s.dialog);
  const setDialog = useStore((s) => s.setDialog);
  const setSelected = useStore((s) => s.setSelected);
  const checked = useRef(false);
  const close = () => setDialog(null);
  if (!dialog) return null;
  if (dialog.kind === 'settings') return <Settings close={close} />;
  if (dialog.kind === 'noteColor') return <NoteColor noteIds={dialog.noteIds} close={() => { close(); setSelected(null); }} />;
  if (dialog.kind === 'export') return <ExportNote noteIds={dialog.noteIds} mode={dialog.mode} close={close} />;
  if (dialog.kind === 'pickNotebook') return <PickNotebook dialog={dialog} close={close} />;
  if (dialog.kind === 'bulkTags') return <BulkTags dialog={dialog} close={close} />;
  if (dialog.kind === 'pickNote') return <PickNote dialog={dialog} close={close} />;
  if (dialog.kind === 'prompt') return <Prompt key={dialog.title} dialog={dialog} close={close} />;
  if (dialog.kind === 'password') return <PasswordDialog key={dialog.title} dialog={dialog} close={close} />;
  return (
    <Modal title={dialog.title} onClose={close}>
      {dialog.body && <p>{dialog.body}</p>}
      {dialog.check && (
        <div className="settings-checks">
          <label><input type="checkbox" onChange={(e) => (checked.current = e.target.checked)} /> {dialog.check}</label>
        </div>
      )}
      <footer>
        <button type="button" className="btn" onClick={close} autoFocus>Cancel</button>
        <button
          type="button"
          className={dialog.danger ? 'btn danger' : 'btn primary'}
          onClick={() => {
            dialog.onConfirm(checked.current);
            checked.current = false;
            close();
          }}
        >
          {dialog.confirm}
        </button>
      </footer>
    </Modal>
  );
}

export function ToastHost() {
  const toast = useStore((s) => s.toast);
  const dismiss = useStore((s) => s.dismissToast);
  useEffect(() => {
    if (!toast || toast.sticky) return;
    const t = setTimeout(dismiss, toast.action ? 6000 : 3800);
    return () => clearTimeout(t);
  }, [toast, dismiss]);
  if (!toast) return null;
  return (
    <div className="toast" role="status" key={toast.id}>
      <span>{toast.message}</span>
      {toast.action && (
        <button
          type="button"
          onClick={() => {
            toast.action!.run();
            dismiss();
          }}
        >
          {toast.action.label}
        </button>
      )}
    </div>
  );
}
