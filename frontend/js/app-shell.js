/** Điều hướng dùng chung cho các trang nghiệp vụ chưa có sidebar. */
(() => {
  const content = document.querySelector('[data-app-content]');
  if (!content || document.getElementById('sidebar') || !Auth.requireLogin()) return;

  const shell = document.createElement('div');
  shell.className = 'app-shell';
  content.before(shell);
  // Optional page controls (for example the portal cart) retain their real DOM
  // nodes/IDs when the standalone header is replaced by the shared toolbar.
  const pageActions = content.querySelector('[data-app-topbar-actions]');
  content.querySelector('[data-standalone-header]')?.remove();
  content.classList.add('main-area', 'shared-main-area');

  const sidebar = document.createElement('aside');
  sidebar.className = 'sidebar';
  sidebar.id = 'sidebar';
  sidebar.innerHTML = `
    <div class="sidebar__header">
      <a class="sidebar__brand" href="/dashboard">
        <img src="/assets/brand-mark.svg" alt="" class="sidebar__logo">
        <span class="sidebar__brand-name">SaleStock</span>
      </a>
      <button class="sidebar__close" id="sidebar-close" type="button" aria-label="Đóng menu">
        <svg viewBox="0 0 24 24"><path d="M18 6 6 18M6 6l12 12"/></svg>
      </button>
    </div>
    <nav class="sidebar__nav" id="sidebar-nav" aria-label="Menu chính"></nav>
    <div class="sidebar__footer">
      <div class="sidebar__user">
        <a class="sidebar__avatar" href="/profile" aria-label="Hồ sơ cá nhân">
          <span id="shell-avatar-fallback"></span><img id="shell-avatar-image" alt="" hidden>
        </a>
        <div class="sidebar__user-info">
          <span class="sidebar__user-name" id="user-name"></span>
          <span class="sidebar__user-role" id="user-role"></span>
          <span class="sidebar__user-scope" id="user-scope"></span>
        </div>
      </div>
      <button class="sidebar__logout" id="btn-logout" type="button" aria-label="Đăng xuất">
        <svg viewBox="0 0 24 24"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="m16 17 5-5-5-5M21 12H9"/></svg>
      </button>
    </div>`;

  const topbar = document.createElement('header');
  topbar.className = 'topbar';
  topbar.innerHTML = `
    <button class="topbar__menu-btn" id="sidebar-toggle" type="button" aria-label="Mở menu" aria-controls="sidebar" aria-expanded="false">
      <svg viewBox="0 0 24 24"><path d="M3 6h18M3 12h18M3 18h18"/></svg>
    </button>
    <div class="topbar__breadcrumb" id="shell-page-title"></div>
    <div class="topbar__actions">
      <a class="topbar__btn topbar__btn--icon" href="/profile" aria-label="Hồ sơ cá nhân">
        <svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="4"/><path d="M4 22a8 8 0 0 1 16 0"/></svg>
      </a>
      <button class="topbar__btn topbar__btn--logout" id="btn-logout-topbar" type="button" aria-label="Đăng xuất">
        <svg viewBox="0 0 24 24"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="m16 17 5-5-5-5M21 12H9"/></svg><span>Đăng xuất</span>
      </button>
    </div>`;
  if (pageActions) {
    topbar.querySelector('.topbar__actions').prepend(...pageActions.childNodes);
    pageActions.remove();
  }
  content.prepend(topbar);
  shell.append(sidebar, content);

  const overlay = document.createElement('div');
  overlay.id = 'sidebar-overlay';
  overlay.className = 'sidebar-overlay';
  overlay.setAttribute('aria-hidden', 'true');
  shell.after(overlay);

  const user = Auth.getUser();
  const $ = (id) => document.getElementById(id);
  const displayName = user.fullName || user.username;
  $('user-name').textContent = displayName;
  $('user-role').textContent = (user.roles || []).map(MenuConfig.getRoleDisplayName).join(', ');
  $('user-scope').textContent = [...(user.warehouses || []), ...(user.territories || [])]
    .map((resource) => resource.name).join(', ');
  $('user-scope').hidden = !$('user-scope').textContent;
  $('shell-avatar-fallback').textContent = displayName.trim().split(/\s+/)
    .slice(0, 2).map((word) => word[0]).join('').toUpperCase();
  const toggle = $('sidebar-toggle');
  const mobileMenu = matchMedia('(max-width: 1024px)');
  function syncMenuAccess(open) {
    sidebar.inert = mobileMenu.matches && !open;
    content.inert = mobileMenu.matches && open;
  }
  function closeMenu() {
    sidebar.classList.remove('sidebar--open');
    overlay.classList.remove('sidebar-overlay--visible');
    toggle.setAttribute('aria-expanded', 'false');
    syncMenuAccess(false);
  }
  toggle.addEventListener('click', () => {
    const open = sidebar.classList.toggle('sidebar--open');
    overlay.classList.toggle('sidebar-overlay--visible', open);
    toggle.setAttribute('aria-expanded', String(open));
    syncMenuAccess(open);
    if (open) $('sidebar-close').focus();
  });
  mobileMenu.addEventListener('change', closeMenu);
  syncMenuAccess(false);
  $('sidebar-close').addEventListener('click', () => { closeMenu(); toggle.focus(); });
  overlay.addEventListener('click', closeMenu);
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && sidebar.classList.contains('sidebar--open')) {
      closeMenu();
      toggle.focus();
    }
  });
  $('btn-logout').addEventListener('click', () => Auth.logout());
  $('btn-logout-topbar').addEventListener('click', () => Auth.logout());

})();
