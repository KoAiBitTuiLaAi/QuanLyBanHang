/** Sidebar và breadcrumb dùng cùng một cấu hình trang trên toàn ứng dụng. */
const AppNavigation = (() => {
  let renderedMenu = '';
  let profileRequest = 0;
  let profileRefreshTimer;
  const stateKey = 'saleStockNavigation';
  const navigation = window.navigation;

  function readState(entry) {
    try { return entry?.getState?.()?.[stateKey]; } catch { return null; }
  }

  function scrollOwner() {
    const user = Auth.getUser();
    return String(user?.id ?? user?.username ?? '');
  }

  function savedScrollTop() {
    let state = readState(navigation?.currentEntry) || window.history.state?.[stateKey];
    // A new document inherits the menu's viewport, not the white page's scroll.
    if (!state && ['push', 'replace'].includes(navigation?.activation?.navigationType)) {
      state = readState(navigation.activation.from);
    }
    return state?.owner === scrollOwner() && Number.isFinite(state.top) ? Math.max(0, state.top) : 0;
  }

  let menuScrollTop = savedScrollTop();

  function saveScrollPosition() {
    const nav = document.getElementById('sidebar-nav');
    if (!nav || !nav.childElementCount) return;
    menuScrollTop = nav.scrollTop;
    const state = { owner: scrollOwner(), top: menuScrollTop };
    try {
      if (navigation?.updateCurrentEntry) {
        navigation.updateCurrentEntry({ state: { ...navigation.currentEntry.getState(), [stateKey]: state } });
      } else {
        window.history.replaceState({ ...window.history.state, [stateKey]: state }, '');
      }
    } catch { /* History state is optional; ordinary links must remain usable. */ }
  }

  function renderSidebar(user) {
    const nav = document.getElementById('sidebar-nav');
    if (!nav) return;
    const items = MenuConfig.getMenuForUser(user);
    const signature = JSON.stringify(items);
    if (renderedMenu === signature && nav.childElementCount) return;
    const top = nav.childElementCount ? nav.scrollTop : menuScrollTop;
    const fragment = document.createDocumentFragment();
    let lastSection = null;
    for (const item of items) {
      if (item.section && item.section !== lastSection) {
        lastSection = item.section;
        const section = document.createElement('div');
        section.className = 'sidebar__section-label';
        section.textContent = item.section;
        fragment.append(section);
      }
      const link = document.createElement('a');
      link.className = 'sidebar__link';
      link.href = item.href;
      link.dataset.page = item.id;
      link.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true">${item.icon}</svg><span></span>`;
      link.querySelector('span').textContent = item.label;
      fragment.append(link);
    }
    nav.replaceChildren(fragment);
    nav.scrollTop = top;
    menuScrollTop = nav.scrollTop;
    renderedMenu = signature;
  }

  function renderBreadcrumb(page) {
    const container = document.querySelector('.topbar__breadcrumb');
    if (!container || !page) return;
    const nav = document.createElement('nav');
    nav.className = 'app-breadcrumb';
    nav.setAttribute('aria-label', 'Đường dẫn điều hướng');
    const list = document.createElement('ol');
    for (const [index, crumb] of MenuConfig.getBreadcrumbs(page).entries()) {
      const item = document.createElement('li');
      if (index) {
        const separator = document.createElement('span');
        separator.className = 'app-breadcrumb__separator';
        separator.setAttribute('aria-hidden', 'true');
        separator.textContent = '›';
        item.append(separator);
      }
      const label = document.createElement(crumb.href ? 'a' : 'span');
      label.textContent = crumb.label;
      if (crumb.href) label.href = crumb.href;
      if (crumb.current) label.setAttribute('aria-current', 'page');
      item.append(label);
      list.append(item);
    }
    nav.append(list);
    container.replaceChildren(nav);
  }

  function refresh() {
    const page = MenuConfig.getPageForLocation(window.location);
    document.querySelectorAll('#sidebar-nav [data-page]').forEach((link) => {
      const active = link.dataset.page === page?.id;
      link.classList.toggle('sidebar__link--active', active);
      if (active) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    });
    renderBreadcrumb(page);
  }

  function connectSidebarProfile() {
    const footer = document.querySelector('.sidebar__user');
    if (!footer) return;
    const linkToProfile = element => {
      if (!element || element.tagName === 'BUTTON') return;
      let link = element;
      if (element.tagName !== 'A') {
        link = document.createElement('a');
        for (const attribute of element.attributes) link.setAttribute(attribute.name, attribute.value);
        link.append(...element.childNodes);
        element.replaceWith(link);
      }
      link.href = '/profile';
      link.setAttribute('aria-label', 'Hồ sơ cá nhân');
      link.title = 'Hồ sơ cá nhân';
    };
    linkToProfile(footer.querySelector('.sidebar__user-info'));
    // Dashboard/warehouse avatar buttons retain their existing upload action.
    linkToProfile(footer.querySelector('.sidebar__avatar'));
  }

  async function refreshSidebarProfile() {
    if (!Auth.isLoggedIn() || !document.querySelector('.sidebar__user') || !Auth.apiFetch) return;
    const requestId = ++profileRequest;
    let profile = Auth.getUser() || {};
    try {
      const response = await Auth.apiFetch('/users/profile', {cache:'no-store'});
      const result = await response.json();
      if (response.ok && result.success && result.data && !Array.isArray(result.data)) {
        profile = {...profile, ...result.data};
      }
    } catch { /* Keep the signed-in identity if the profile endpoint is unavailable. */ }
    if (requestId !== profileRequest || !Auth.isLoggedIn()) return;
    const candidate = document.createElement('img');
    const url = profile.avatarThumbnailUrl || profile.avatarUrl;
    const loaded = url ? await Auth.setProtectedImage(candidate, url) : false;
    if (requestId !== profileRequest || !Auth.isLoggedIn()) {
      if (candidate.dataset.objectUrl) URL.revokeObjectURL(candidate.dataset.objectUrl);
      return;
    }
    const avatar = document.querySelector('.sidebar__avatar');
    if (!avatar) {
      if (candidate.dataset.objectUrl) URL.revokeObjectURL(candidate.dataset.objectUrl);
      return;
    }
    const name = profile.fullName || profile.username || 'U';
    const nameElement = document.getElementById('user-name');
    if (nameElement) nameElement.textContent = name;
    // Reuse existing image/fallback IDs so legacy avatar-upload controls keep working.
    const image = avatar.querySelector('img') || document.createElement('img');
    const fallback = avatar.querySelector('span') || document.createElement('span');
    if (image.dataset.objectUrl) URL.revokeObjectURL(image.dataset.objectUrl);
    image.alt = '';
    image.hidden = !loaded;
    if (loaded) {
      image.src = candidate.src;
      image.dataset.objectUrl = candidate.dataset.objectUrl;
    } else {
      if (candidate.dataset.objectUrl) URL.revokeObjectURL(candidate.dataset.objectUrl);
      image.removeAttribute('src');
      delete image.dataset.objectUrl;
    }
    fallback.textContent = name.trim().split(/\s+/).slice(0,2).map(part=>part[0]).join('').toUpperCase();
    fallback.hidden = loaded;
    avatar.replaceChildren(fallback, image);
  }

  async function initialize() {
    if (!Auth.isLoggedIn()) return;
    connectSidebarProfile();
    // Render the existing shell before the first await, then reconcile with DB.
    // While synchronizing, stale menu links cannot be activated.
    const nav = document.getElementById('sidebar-nav');
    if (nav) nav.inert = true;
    renderSidebar(Auth.getUser());
    refresh();
    // Nạp lại permissions từ DB trước khi dựng menu để thay đổi
    // ma trận không tạo ra tình trạng menu hiện nhưng API trả 403 (hoặc ngược lại).
    await Auth.ready;
    // Legacy page scripts still set avatar initials during startup. Render the
    // shared saved image after those scripts have run, even on slow script loads.
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', refreshSidebarProfile, {once:true});
    } else refreshSidebarProfile();
    renderSidebar(Auth.getUser());
    refresh();
    enforcePageAccess();
    if (nav) nav.inert = false;
    // Release the static outgoing menu only after restoring this menu and
    // reconciling its permissions. Does not grant access or skip Auth.ready.
    window.AppTransition?.navigationReady();
  }

  function enforcePageAccess() {
    const page = MenuConfig.getPageForLocation(window.location);
    if (page && !['dashboard', 'profile'].includes(page.id)
        && !MenuConfig.getMenuForUser(Auth.getUser()).some(item => item.id === page.id)) {
      window.location.href = '/error?status=403';
    }
  }

  const actionPermissions = {
    products: [
      ['#create-product', 'PRODUCT_CREATE'],
      ['#btn-import-excel', 'PRODUCT_CREATE', 'PRODUCT_EDIT'],
    ],
    'price-lists': [['[data-price-action], #save-price-list', 'PRODUCT_EDIT']],
    'discount-policies': [['[data-action="edit"], [data-action="delete"], #save-disc-policy', 'PRODUCT_EDIT']],
    dealers: [
      ['#btn-add-dealer', 'CUSTOMER_CREATE'],
      ['[data-action="edit"], [data-action="assign"], [data-action="lock"], [data-action="unlock"], [data-action="activate"], [data-action="deactivate"], #btn-add-delivery-point', 'CUSTOMER_EDIT'],
      ['[data-action="orders"], [data-action="order-history"]', 'ORDER_VIEW'],
    ],
    users: [
      ['#create-user, #import-users, #confirm-user-import', 'USER_CREATE', 'USER_ASSIGN_ROLE'],
      ['[data-edit]', 'USER_EDIT'],
      ['[data-toggle]', 'USER_LOCK'],
    ],
    'dealer-orders': [
      ['#btn-reorder-from-detail, #btn-submit-reorder, [data-action="reorder"]', 'ORDER_CREATE', 'PRODUCT_VIEW'],
    ],
  };
  const pageId = MenuConfig.getPageForLocation(window.location)?.id;
  (actionPermissions[pageId] || []).forEach(binding => Auth.bindPermissions(...binding));

  initialize();
  document.getElementById('sidebar-nav')?.addEventListener('scroll', saveScrollPosition, { passive: true });
  document.addEventListener('click', saveScrollPosition, { capture: true });
  window.addEventListener('pagehide', saveScrollPosition);
  window.addEventListener('pageswap', saveScrollPosition);
  window.addEventListener('auth:permissions-changed', () => {
    renderSidebar(Auth.getUser());
    refresh();
    enforcePageAccess();
  });
  window.addEventListener('auth:profile-changed', refreshSidebarProfile);
  function scheduleProfileRefresh() {
    window.clearTimeout(profileRefreshTimer);
    profileRefreshTimer = window.setTimeout(refreshSidebarProfile, 150);
  }
  window.addEventListener('focus', scheduleProfileRefresh);
  window.addEventListener('pagehide', () => {
    window.clearTimeout(profileRefreshTimer);
    profileRequest++;
  });
  window.addEventListener('pageshow', event => {
    if (event.persisted) scheduleProfileRefresh();
    menuScrollTop = savedScrollTop();
    const nav = document.getElementById('sidebar-nav');
    if (nav) nav.scrollTop = menuScrollTop;
    refresh();
  });
  window.addEventListener('popstate', refresh);
  return { refresh, refreshProfile: refreshSidebarProfile };
})();
