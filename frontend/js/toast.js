const Toast = (() => {
  const icons = {
    error: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6M9 9l6 6"/></svg>',
    success: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/></svg>',
    warning: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>',
  };

  function show(message, type = 'error', duration = 4000) {
    if (!Object.hasOwn(icons, type)) type = 'error';
    let container = document.getElementById('toast-container');
    if (!container) {
      container = document.createElement('div');
      container.id = 'toast-container';
      document.body.appendChild(container);
    }

    const toast = document.createElement('div');
    toast.className = `toast toast--${type}`;
    toast.setAttribute('role', 'alert');
    const icon = document.createElement('span');
    icon.className = 'toast__icon';
    icon.setAttribute('aria-hidden', 'true');
    icon.innerHTML = icons[type];
    const text = document.createElement('span');
    text.className = 'toast__message';
    text.textContent = String(message ?? '');
    toast.appendChild(icon);
    toast.appendChild(text);
    container.appendChild(toast);

    requestAnimationFrame(() => toast.classList.add('toast--visible'));
    setTimeout(() => {
      toast.classList.remove('toast--visible');
      toast.classList.add('toast--exit');
      toast.addEventListener('animationend', () => toast.remove(), { once: true });
      // Also remove when animations are disabled or the page is in the background.
      setTimeout(() => toast.remove(), 300);
    }, duration);
    return toast;
  }

  return { show };
})();
