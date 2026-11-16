import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import type { Favourite } from '../api/types';
import { validateLabel } from '../validation';

export type FocusTarget = 'rename' | 'up' | 'down' | 'delete';

export interface FavouriteItemProps {
  favourite: Favourite;
  isFirst: boolean;
  isLast: boolean;
  /** Disables the move buttons (a reorder is in flight). */
  moveDisabled: boolean;
  /** Resolves true when the rename succeeded. */
  onRename: (id: string, label: string | null) => Promise<boolean>;
  onMove: (id: string, direction: -1 | 1) => void;
  onDelete: (id: string) => Promise<void>;
  /** Lets the view focus this item's controls after list changes. */
  registerControl: (id: string, target: FocusTarget, element: HTMLButtonElement | null) => void;
}

type Mode = 'view' | 'rename' | 'confirm';

export function displayName(favourite: Pick<Favourite, 'label' | 'city'>): string {
  return favourite.label ?? favourite.city;
}

export function FavouriteItem(props: FavouriteItemProps) {
  const { favourite, isFirst, isLast, moveDisabled, onRename, onMove, onDelete, registerControl } = props;
  const name = displayName(favourite);
  const [mode, setMode] = useState<Mode>('view');
  const [labelInput, setLabelInput] = useState('');
  const [labelError, setLabelError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const renameButton = useRef<HTMLButtonElement | null>(null);
  const deleteButton = useRef<HTMLButtonElement | null>(null);
  const labelInputRef = useRef<HTMLInputElement>(null);
  const cancelDeleteRef = useRef<HTMLButtonElement>(null);
  const returnFocus = useRef<'rename' | 'delete' | null>(null);

  useEffect(() => {
    if (mode === 'rename') labelInputRef.current?.focus();
    if (mode === 'confirm') cancelDeleteRef.current?.focus();
    if (mode === 'view' && returnFocus.current) {
      (returnFocus.current === 'rename' ? renameButton : deleteButton).current?.focus();
      returnFocus.current = null;
    }
  }, [mode]);

  const closeTo = (target: 'rename' | 'delete') => {
    returnFocus.current = target;
    setLabelError(null);
    setMode('view');
  };

  const startRename = () => {
    setLabelInput(favourite.label ?? '');
    setLabelError(null);
    setMode('rename');
  };

  const saveRename = async () => {
    const result = validateLabel(labelInput);
    if (!result.ok) {
      setLabelError(result.message);
      return;
    }
    setPending(true);
    const ok = await onRename(favourite.id, result.value);
    setPending(false);
    if (ok) closeTo('rename');
  };

  const confirmDelete = async () => {
    setPending(true);
    await onDelete(favourite.id);
    setPending(false);
  };

  const onEscape = (event: KeyboardEvent, target: 'rename' | 'delete') => {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeTo(target);
    }
  };

  const labelId = `label-${favourite.id}`;
  const labelErrorId = `label-error-${favourite.id}`;

  return (
    <li>
      <div>
        {favourite.label && <p className="fav-label"><strong>{favourite.label}</strong></p>}
        <p>{favourite.city}</p>
        <p className="muted">
          {favourite.location.name}, {favourite.location.country}
        </p>
      </div>

      {mode === 'rename' && (
        <form
          aria-label={`Rename ${name}`}
          onSubmit={(e) => {
            e.preventDefault();
            void saveRename();
          }}
          noValidate
        >
          <div className="field">
            <label htmlFor={labelId}>Label for {name}</label>
            <input
              id={labelId}
              ref={labelInputRef}
              type="text"
              value={labelInput}
              onChange={(e) => setLabelInput(e.target.value)}
              onKeyDown={(e) => onEscape(e, 'rename')}
              aria-invalid={labelError ? true : undefined}
              aria-describedby={labelError ? labelErrorId : undefined}
            />
          </div>
          <button type="submit" disabled={pending}>
            Save
          </button>
          <button type="button" className="secondary" onClick={() => closeTo('rename')}>
            Cancel
          </button>
          {labelError && (
            <p id={labelErrorId} className="field-error" role="alert">
              {labelError}
            </p>
          )}
        </form>
      )}

      {mode === 'confirm' && (
        <div className="actions" onKeyDown={(e) => onEscape(e, 'delete')}>
          <p>Delete {name}?</p>
          <button type="button" onClick={() => void confirmDelete()} disabled={pending}>
            Yes, delete
          </button>
          <button type="button" className="secondary" ref={cancelDeleteRef} onClick={() => closeTo('delete')}>
            Cancel
          </button>
        </div>
      )}

      {mode === 'view' && (
        <div className="actions">
          <button
            type="button"
            className="secondary"
            ref={(el) => {
              renameButton.current = el;
              registerControl(favourite.id, 'rename', el);
            }}
            onClick={startRename}
          >
            Rename
          </button>
          <button
            type="button"
            className="secondary"
            ref={(el) => registerControl(favourite.id, 'up', el)}
            disabled={isFirst || moveDisabled}
            onClick={() => onMove(favourite.id, -1)}
          >
            Move <span className="visually-hidden">{name}</span> up
          </button>
          <button
            type="button"
            className="secondary"
            ref={(el) => registerControl(favourite.id, 'down', el)}
            disabled={isLast || moveDisabled}
            onClick={() => onMove(favourite.id, 1)}
          >
            Move <span className="visually-hidden">{name}</span> down
          </button>
          <button
            type="button"
            className="secondary"
            ref={(el) => {
              deleteButton.current = el;
              registerControl(favourite.id, 'delete', el);
            }}
            onClick={() => setMode('confirm')}
          >
            Delete
          </button>
        </div>
      )}
    </li>
  );
}
