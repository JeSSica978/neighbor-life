(() => {
  "use strict";
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const icons = {
    pin: '<path d="M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0Z"/><circle cx="12" cy="10" r="2.5"/>',
    search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4.5 4.5"/>',
    arrow: '<path d="M4 12h16m-6-6 6 6-6 6"/>',
    close: '<path d="m6 6 12 12M6 18 18 6"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
    heart: '<path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z"/>',
    sparkles: '<path d="m12 3 2.4 6.6L21 12l-6.6 2.4L12 21l-2.4-6.6L3 12l6.6-2.4L12 3ZM20 2v4m-2-2h4"/>',
    ticket: '<path d="M3 7h18v4a2 2 0 0 0 0 4v4H3v-4a2 2 0 0 0 0-4V7Z"/><path d="M15 7v2m0 3v2m0 3v2"/>',
    "chevron-left": '<path d="m15 5-7 7 7 7"/>',
    "chevron-right": '<path d="m9 5 7 7-7 7"/>',
    grid: '<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
    food: '<path d="M5 3v7m3-7v7m-6-7v7a3 3 0 0 0 6 0m-3 3v8M16 3c-3 4-3 9 1 9h2V3m0 9v9"/>',
    coffee: '<path d="M3 8h13v6a6.5 6.5 0 0 1-13 0V8Zm13 1h2a3 3 0 0 1 0 6h-2M3 21h14M6 2v3m4-3v3m4-3v3"/>',
    bed: '<path d="M3 18v3m18-3v3M3 18h18V9H3v9Zm2-9V4h14v5M6 9V7h4v2m4 0V7h4v2"/>',
    flower: '<path d="M12 8c-5-8-11-1-7 3-9 3-1 11 3 6 0 9 9 6 8 1 8 4 12-5 5-7 5-6-5-10-9-3Z"/><circle cx="12" cy="13" r="3"/>',
    bag: '<path d="M4 7h16l-1 14H5L4 7Zm4 0V6a4 4 0 0 1 8 0v1"/>',
    film: '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M7 3v18M17 3v18M3 8h4m-4 8h4m10-8h4m-4 8h4m-10-7 4 3-4 3V9Z"/>',
    book: '<path d="M12 5c-4-3-8-2-10-1v16c3-2 6-2 10 0 4-2 7-2 10 0V4c-2-1-6-2-10 1Zm0 0v15"/>',
    leaf: '<path d="M20 3c1 8-1 14-7 15-6 1-10-4-7-9 2-4 9-1 14-6ZM3 21c2-7 6-10 13-13"/>',
    bike: '<circle cx="5" cy="17" r="4"/><circle cx="19" cy="17" r="4"/><path d="m5 17 5-9 9 9H5l4-6h7m-7-5h4m5 4-2-5h-4"/>',
    palette: '<path d="M21 12a9 9 0 1 0-9 9h1a2 2 0 0 0 1-4 2 2 0 0 1 1-4h3a3 3 0 0 0 3-1Z"/><circle cx="8" cy="7" r=".5"/><circle cx="6" cy="12" r=".5"/><circle cx="13" cy="6" r=".5"/><circle cx="17" cy="9" r=".5"/>'
  };
  const icon = (name) => '<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">' + (icons[name] || icons.sparkles) + '</svg>';
  const fillIcons = (root = document) => $$("[data-icon]", root).forEach(el => { el.innerHTML = icon(el.dataset.icon); });
  const make = (tag, className = "", text) => {
    const el = document.createElement(tag);
    if (className) el.className = className;
    if (text !== undefined) el.textContent = text;
    return el;
  };
  const addIcon = (element, name) => { const holder = make("span"); holder.innerHTML = icon(name); element.append(holder); };
  const price = (amount) => new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 2 }).format(Number(amount) || 0);
  const text = (value, fallback = "") => value === null || value === undefined ? fallback : String(value);
  const plainText = (content) => {
    const safeDocument = new DOMParser().parseFromString(text(content).replace(/<br\s*\/?>/gi, "\n").replace(/<\/p>/gi, "\n\n"), "text/html");
    safeDocument.querySelectorAll("script,style,iframe,object").forEach(el => el.remove());
    return (safeDocument.body.textContent || "").trim();
  };
  const categoryIcon = (name) => {
    if (/美食|餐|火锅|烧烤/.test(name)) return "food";
    if (/咖啡|茶|甜|饮/.test(name)) return "coffee";
    if (/酒店|民宿/.test(name)) return "bed";
    if (/美容|丽人|美发/.test(name)) return "flower";
    if (/购物|超市|商场/.test(name)) return "bag";
    if (/电影|娱乐|KTV/.test(name)) return "film";
    if (/运动|健身/.test(name)) return "bike";
    if (/教育|书/.test(name)) return "book";
    if (/生活|服务|亲子/.test(name)) return "leaf";
    return "palette";
  };
  const loadSaved = () => {
    try { const rows = JSON.parse(localStorage.getItem("neighborlife.saved") || "[]"); return Array.isArray(rows) ? rows.filter(row => row && row.id && typeof row.name === "string").slice(0, 500) : []; }
    catch (_) { return []; }
  };
  const SHOP_PAGE_SIZE = 12;
  const state = { view: "discover", types: [], shops: [], notes: [], saved: loadSaved(), type: null, search: "", page: 1, shopTotal: null, hasNext: false, loading: false, user: null, requestId: 0, activeShop: null };
  const typeName = (id) => text(state.types.find(type => String(type.id) === String(id))?.name, "邻里好店");
  let toastTimer;
  function toast(message) {
    const el = $("#toast");
    const currentDialog = $("dialog[open]");
    (currentDialog || document.body).append(el);
    el.textContent = message;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.hidden = true; }, 2800);
  }
  function clearAuth() {
    sessionStorage.removeItem("neighborlife.token");
    state.user = null;
    state.saved = loadSaved();
    updateSavedBadge();
    updateAccount();
  }
  async function api(path, options = {}, includeMetadata = false) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 12000);
    const headers = { Accept: "application/json", ...(options.headers || {}) };
    const token = sessionStorage.getItem("neighborlife.token");
    if (token) headers.authorization = token;
    if (options.body && !(options.body instanceof FormData)) headers["Content-Type"] = "application/json";
    try {
      const response = await fetch(path, { ...options, headers, signal: controller.signal });
      if (response.status === 401) { clearAuth(); throw new Error("请先登录后再继续。"); }
      const result = await response.json();
      if (!response.ok) throw new Error(result.errorMsg || "服务暂时无法响应，请稍后重试。");
      if (result.success !== true) throw new Error(typeof result.errorMsg === "string" ? result.errorMsg : "操作暂未完成，请稍后重试。");
      return includeMetadata ? result : result.data;
    } catch (error) {
      if (error.name === "AbortError" || error instanceof TypeError) throw new Error("连接服务失败，请稍后重试。");
      throw error;
    } finally { clearTimeout(timer); }
  }
  function status(title, description, retry = false) {
    $("#result-status").hidden = false;
    $("#status-title").textContent = title;
    $("#status-text").textContent = description;
    $("#retry-button").hidden = !retry;
  }
  function updatePager() {
    const shopPages = state.view !== "notes" && state.shopTotal !== null ? Math.max(1, Math.ceil(state.shopTotal / SHOP_PAGE_SIZE)) : null;
    $("#pagination").hidden = state.view === "saved" || shopPages === 1;
    $("#page-label").textContent = "第 " + state.page + (shopPages === null ? "" : " / " + shopPages) + " 页";
    $("#previous-page").disabled = state.loading || state.page <= 1;
    $("#next-page").disabled = state.loading || !state.hasNext;
  }
  function startLoading() {
    state.loading = true;
    state.hasNext = false;
    $("#result-status").hidden = true;
    $("#shop-grid").replaceChildren();
    $("#notes-grid").replaceChildren();
    const grid = state.view === "notes" ? $("#notes-grid") : $("#shop-grid");
    for (let i = 0; i < (state.view === "notes" ? 2 : 3); i++) grid.append(make("div", "skeleton-card"));
    $("#result-summary").textContent = "正在加载…";
    updatePager();
  }
  function renderCategories() {
    const rows = [{ id: "all", name: "全部", iconName: "grid" }, ...state.types];
    const root = $("#categories");
    root.replaceChildren();
    rows.forEach(type => {
      const selected = type.id === "all" ? state.type === null : String(type.id) === String(state.type);
      const button = make("button", "category-button" + (selected ? " selected" : ""));
      button.setAttribute("aria-pressed", String(selected));
      const symbol = make("span", "category-icon");
      symbol.innerHTML = icon(type.iconName || categoryIcon(type.name));
      button.append(symbol, make("span", "", type.name));
      button.addEventListener("click", () => {
        if (state.loading || (type.id === "all" && state.type === null && !state.search)) return;
        state.type = type.id === "all" ? null : type.id;
        state.search = "";
        state.page = 1;
        $("#search-input").value = "";
        renderCategories();
        loadShops();
      });
      root.append(button);
    });
  }
  function updateFilter() {
    const active = state.view === "discover" && (state.type !== null || state.search);
    $("#filter-status").hidden = !active;
    $("#filter-description").textContent = state.search ? "搜索结果：“" + state.search + "”" : "当前分类：" + typeName(state.type);
  }
  const sortedShops = rows => [...rows]; // 全局排序由后端完成，不能只排当前页。
  function updateSavedBadge() {
    $("#saved-count").textContent = state.saved.length;
    $("#saved-count").hidden = !state.saved.length;
    $$("[data-save]").forEach(button => {
      const saved = state.saved.some(row => String(row.id) === button.dataset.save);
      button.classList.toggle("saved", saved);
      button.setAttribute("aria-pressed", String(saved));
      button.setAttribute("aria-label", saved ? "取消收藏店铺" : "收藏店铺");
    });
  }
  function renderShops() {
    if (state.view === "notes") return;
    const rows = sortedShops(state.view === "saved" ? state.saved : state.shops);
    $("#shop-grid").replaceChildren();
    $("#result-status").hidden = true;
    rows.forEach(shop => {
      const card = $("#shop-template").content.firstElementChild.cloneNode(true);
      card.classList.add("tone-" + ((Number(shop.typeId) % 5) + 1));
      $$("[data-shop]", card).forEach(button => { button.dataset.shop = shop.id; });
      $(".shop-cover", card).setAttribute("aria-label", "查看" + shop.name + "详情");
      $("[data-save]", card).dataset.save = shop.id;
      $(".shop-name", card).textContent = shop.name;
      $(".cover-symbol", card).innerHTML = icon(categoryIcon(typeName(shop.typeId)));
      $(".cover-name", card).textContent = text(shop.name).charAt(0);
      $(".cover-category", card).textContent = typeName(shop.typeId);
      $(".rating-value", card).textContent = shop.score ? (Number(shop.score) / 10).toFixed(1) : "暂无评分";
      $(".comments", card).textContent = shop.comments ? shop.comments + " 条评价" : "";
      $(".shop-area", card).textContent = [typeName(shop.typeId), shop.area].filter(Boolean).join(" · ");
      const average = $(".average-price", card);
      average.append(document.createTextNode("人均 "), make("strong", "", shop.avgPrice == null ? "未提供" : "¥" + price(shop.avgPrice)));
      fillIcons(card);
      $("#shop-grid").append(card);
    });
    $("#result-summary").textContent = state.view === "saved" ? rows.length + (state.user ? " 家收藏 · 已保存到账号" : " 家收藏 · 保存在当前浏览器") : (state.shopTotal === null ? "" : "共 " + state.shopTotal + " 家好店 · ") + "本页 " + rows.length + " 家 · 点击店铺查看详情与优惠";
    if (!rows.length) status(state.view === "saved" ? "把喜欢的店，留在这里。" : "还没找到合适的店铺", state.view === "saved" ? "点击店铺卡片上的爱心，收藏你的下一站。" : "试试其他分类或店铺名称。");
    updateFilter();
    updateSavedBadge();
    updatePager();
  }
  async function loadShops() {
    const requestId = ++state.requestId;
    state.shopTotal = null;
    startLoading();
    updateFilter();
    const query = new URLSearchParams({ current: String(state.page), size: String(SHOP_PAGE_SIZE), sort: $("#sort-select").value });
    if (state.location) { query.set("x",state.location.x); query.set("y",state.location.y); }
    let endpoint = "/shop/of/name";
    if (state.type !== null) { endpoint = "/shop/of/type"; query.set("typeId", state.type); }
    else if (state.search) query.set("name", state.search);
    try {
      const result = await api(endpoint + "?" + query, {}, true);
      if (requestId !== state.requestId || state.view !== "discover") return;
      state.shops = Array.isArray(result.data) ? result.data : [];
      state.shopTotal = Number.isSafeInteger(result.total) && result.total >= 0 ? result.total : null;
      state.hasNext = state.shopTotal === null ? state.shops.length === (state.type === null ? 10 : 5) : state.page * SHOP_PAGE_SIZE < state.shopTotal;
      state.loading = false;
      renderShops();
    } catch (error) {
      if (requestId !== state.requestId || state.view !== "discover") return;
      state.loading = false;
      $("#shop-grid").replaceChildren();
      $("#result-summary").textContent = "暂时无法加载店铺";
      status("这次发现，稍等一下。", error.message, true);
      updatePager();
    }
  }
  function noteCard(note) {
    const card = $("#note-template").content.firstElementChild.cloneNode(true);
    card.classList.add("tone-" + ((Number(note.id) % 5) + 1));
    $$("[data-note]", card).forEach(button => { button.dataset.note = note.id; });
    $(".note-cover", card).setAttribute("aria-label", "阅读" + note.title);
    $(".note-cover-symbol", card).innerHTML = icon("book");
    $(".note-title", card).textContent = note.title;
    $(".note-excerpt", card).textContent = plainText(note.content) || "点开笔记，看看邻里的发现。";
    $(".note-author", card).textContent = text(note.name, "邻里探店人");
    $("[data-like]", card).dataset.like = note.id;
    $(".like-count", card).textContent = text(note.liked, "0");
    $(".like-button", card).classList.toggle("liked", !!note.isLike);
    $(".like-button", card).setAttribute("aria-label", "点赞：" + note.title);
    fillIcons(card);
    return card;
  }
  function renderNotes() {
    $("#notes-grid").replaceChildren(...state.notes.map(noteCard));
    $("#result-status").hidden = true;
    $("#result-summary").textContent = "本页 " + state.notes.length + " 篇故事 · 来自邻里的真实分享";
    if (!state.notes.length) status("邻里的故事，等你发现。", "这里暂时没有更多探店笔记。");
    updatePager();
  }
  function renderInspiration(notes) {
    const root = $("#inspiration-list");
    root.replaceChildren();
    notes.slice(0, 2).forEach(note => {
      const button = make("button", "inspiration-card");
      button.dataset.note = note.id;
      const symbol = make("span");
      symbol.innerHTML = icon("book");
      const body = make("div");
      body.append(make("strong", "", note.title), make("small", "", text(note.name, "邻里探店人") + " · " + text(note.liked, "0") + " 次心动"));
      button.append(symbol, body);
      root.append(button);
    });
    if (!notes.length) root.append(make("p", "muted", "邻里的探店故事正在等待分享。"));
  }
  let inspirationNotes = [];
  async function loadNotes(forSidebar = false) {
    const requestId = forSidebar ? null : ++state.requestId;
    if (!forSidebar) startLoading();
    try {
      const rows = await api("/blog/hot?current=" + (forSidebar ? 1 : state.page));
      const notes = Array.isArray(rows) ? rows : [];
      if (forSidebar) { inspirationNotes = notes; renderInspiration(notes); return; }
      if (requestId !== state.requestId || state.view !== "notes") return;
      state.notes = notes;
      state.hasNext = notes.length === 10;
      state.loading = false;
      renderNotes();
    } catch (error) {
      if (forSidebar) { $("#inspiration-list").replaceChildren(make("p", "muted", "探店故事暂时无法加载。")); return; }
      if (requestId !== state.requestId || state.view !== "notes") return;
      state.loading = false;
      $("#notes-grid").replaceChildren();
      $("#result-summary").textContent = "暂时无法加载笔记";
      status("邻里的发现，稍后再见。", error.message, true);
      updatePager();
    }
  }
  function setView(view) {
    if (!["discover", "notes", "saved"].includes(view)) return;
    ++state.requestId;
    state.loading = false;
    state.view = view;
    state.page = 1;
    state.hasNext = false;
    $$("[data-view]").forEach(button => {
      button.classList.toggle("active", button.dataset.view === view);
      if (button.dataset.view === view) button.setAttribute("aria-current", "page");
      else button.removeAttribute("aria-current");
    });
    $("#hero").hidden = view !== "discover";
    $("#category-section").hidden = view !== "discover";
    $("#shop-grid").hidden = view === "notes";
    $("#notes-grid").hidden = view !== "notes";
    $("#sort-label").hidden = view !== "discover";
    $("#filter-status").hidden = true;
    $("#results-heading").textContent = ({ discover: "值得一逛的好店", notes: "邻里的探店故事", saved: "心动的地方，留着慢慢逛。" })[view];
    $("#section-eyebrow").textContent = ({ discover: "发现身边的好店", notes: "邻里的探店故事", saved: "收藏生活的小美好" })[view];
    if (view === "discover") loadShops();
    else if (view === "notes") loadNotes();
    else renderShops();
  }
  function resetFilters() {
    state.search = "";
    state.type = null;
    $("#search-input").value = "";
    renderCategories();
    setView("discover");
  }
  async function refreshSaved() {
    if (!state.user) { state.saved=loadSaved();updateSavedBadge();return; }
    const account=String(state.user.id),rows=[];
    for(let page=1;;page++) {
      const result=await api("/user/favorites?page="+page+"&size=48",{},true);
      rows.push(...result.data);
      if(rows.length>=result.total || !result.data.length) break;
    }
    if(!state.user || String(state.user.id)!==account) return;
    state.saved=rows;updateSavedBadge();if(state.view==="saved")renderShops();
  }
  async function toggleSaved(id) {
    if (state.user) {
      const saved=state.saved.some(row=>String(row.id)===id);
      try { await api("/shop/"+id+"/favorite",{method:saved?"DELETE":"PUT"});await refreshSaved();toast(saved?"已取消收藏":"已收藏到账号"); }
      catch(error){toast(error.message);}return;
    }
    const existing = state.saved.find(row => String(row.id) === id);
    if (existing) state.saved = state.saved.filter(row => String(row.id) !== id);
    else {
      const shop = state.shops.find(row => String(row.id) === id);
      if (!shop) return;
      const snapshot = {};
      ["id", "name", "typeId", "area", "avgPrice", "score", "comments"].forEach(key => { snapshot[key] = shop[key]; });
      state.saved.unshift(snapshot);
    }
    try { localStorage.setItem("neighborlife.saved", JSON.stringify(state.saved)); }
    catch (_) { toast("当前浏览器无法保存收藏。"); }
    if (state.view === "saved") renderShops();
    updateSavedBadge();
    toast(existing ? "已取消收藏" : "已收藏，留着下次慢慢逛。");
  }
  const showDialog = (id) => { const dialog = $("#" + id); if (!dialog.open) dialog.showModal(); };
  const orderStatusName = value => ({ 1: "待支付", 2: "已支付", 3: "已核销", 4: "已取消", 5: "退款中", 6: "已退款" })[Number(value)] || "状态待确认";
  let orderPage = 1, selectedOrderId = null, orderLoading = false, orderGeneration = 0, orderSignature = "";
  function orderCard(order) {
    const card = make("article", "order-card");
    card.append(make("h3", "", text(order.title, "优惠券订单")), make("p", "", "订单编号：" + order.id));
    if (order.state === "PENDING") { card.append(make("p", "order-status", "订单处理中，正在等待后台创建。")); return card; }
    card.dataset.orderId = order.id;
    card.append(make("p", "order-status", orderStatusName(order.status) + (Number(order.status) === 2 && order.simulatedPayment ? "（模拟）" : "")), make("p", "", (order.historical ? "当前券参考价：¥" : "订单金额：¥") + price(Number(order.payValue) / 100)));
    if (Number(order.status) === 1 && order.lifecycleEnabled && !order.historical) {
      const countdown = make("p", "order-countdown");
      countdown.dataset.deadline = String(Date.now() + Math.max(0, Number(order.remainingSeconds) || 0) * 1000);
      card.append(countdown);
    }
    if (Number(order.status) === 4) card.append(make("p", "muted", Number(order.type) === 1
      ? "该订单已结束。秒杀库存按订单返还，每人限购资格仍保留。" : "该订单已结束，可继续购买普通优惠券。"));
    if (Number(order.status) === 1 && !order.lifecycleEnabled) card.append(make("p", "muted", "订单操作尚未启用，请先完成服务端数据库迁移。"));
    if(order.historical)card.append(make("p","muted","历史订单没有交易快照，保留只读。"));
    const actions = make("div", "order-actions");
    if (Number(order.status) === 1 && order.lifecycleEnabled && !order.historical) {
      if (order.canPay) {
        const payButton = make("button", "button primary", "模拟支付 ¥" + price(Number(order.payValue) / 100));
        payButton.addEventListener("click", () => mutateOrder(order.id, "pay/simulate", actions)); actions.append(payButton);
      }
      const cancelButton = make("button", "button secondary", "取消订单");
      cancelButton.addEventListener("click", () => mutateOrder(order.id, "cancel", actions)); actions.append(cancelButton);
    }
    const refresh = make("button", "text-button", "刷新状态");
    refresh.addEventListener("click", () => loadOrders()); actions.append(refresh);
    if (order.shopId) {
      const shopLink = make("button", "text-button", "查看店铺");
      shopLink.addEventListener("click", () => { $("#orders-dialog").close(); showShop(order.shopId); }); actions.append(shopLink);
    }
    if(order.canRefund){const refund=make("button","button secondary","申请全额模拟退款");refund.addEventListener("click",()=>mutateOrder(order.id,"refunds",actions));actions.append(refund);}
    if(Number(order.status)===2){const owned=make("button","text-button","查看我的持有券");owned.addEventListener("click",()=>window.NeighborBusiness?.owned());actions.append(owned);}
    if(Number(order.status)===3){const review=make("button","text-button","评价这次消费");review.addEventListener("click",()=>window.NeighborBusiness?.review(order));actions.append(review);}
    card.append(actions); return card;
  }
  function updateCountdowns() {
    $$("[data-deadline]", $("#orders-dialog")).forEach(el => {
      const remaining = Math.max(0, Math.ceil((Number(el.dataset.deadline) - Date.now()) / 1000));
      el.textContent = remaining ? "请在 " + Math.floor(remaining / 60) + " 分 " + (remaining % 60) + " 秒内支付" : "支付期限已到，正在确认取消状态…";
    });
  }
  async function mutateOrder(id, action, actions) {
    $$("button", actions).forEach(button => { button.disabled = true; });
    try {
      if(action==="pay/simulate") {
        const key=window.NeighborBusiness.requestKey("pay:"+id);
        const pay=await api("/payments",{method:"POST",headers:{"Idempotency-Key":key},body:JSON.stringify({orderId:id})});
        const result=await api("/mock-payments/"+pay.id+"/confirm",{method:"POST",headers:{"Idempotency-Key":window.NeighborBusiness.requestKey("confirm:"+id)},body:JSON.stringify({result:"SUCCESS"})});
        if(result.status!=="SUCCESS")throw new Error("支付结果待确认，请刷新支付状态");
        const persisted=await api("/voucher-order/"+id);
        toast(Number(persisted.status)===2 ? "模拟支付完成，已发放优惠券，未发生扣款" : "支付结果已收到，请查看订单与退款状态");
      } else {
        const result=await api("/voucher-order/"+id+"/"+action,{method:"POST",headers:{"Idempotency-Key":window.NeighborBusiness.requestKey(action+":"+id)}});
        toast(action==="cancel"?"订单已取消":result.status==="SUCCESS"?"模拟退款完成":"退款已受理，请刷新状态");
      }
    } catch (failure) { toast(failure.message); }
    finally { $$("button", actions).forEach(button => { button.disabled = false; }); await loadOrders(); }
  }
  async function loadOrders() {
    if (orderLoading) return;
    orderLoading = true;
    const generation = ++orderGeneration;
    try {
      const result = selectedOrderId ? { data: [await api("/voucher-order/" + encodeURIComponent(selectedOrderId))], total: 1 }
        : await api("/voucher-order/mine?page=" + orderPage, {}, true);
      if (generation !== orderGeneration) return;
      const rows = Array.isArray(result.data) ? result.data : [];
      const signature = JSON.stringify(rows.map(({ remainingSeconds, ...order }) => order));
      if (signature !== orderSignature) {
        $("#order-list").replaceChildren(...rows.map(orderCard)); orderSignature = signature;
      } else {
        // Preserve focused buttons during polling; only the server-verified remaining time changes.
        $$(".order-card", $("#order-list")).forEach(card => {
          const order = rows.find(row => String(row.id) === card.dataset.orderId);
          const countdown = $("[data-deadline]", card);
          if (order && countdown) countdown.dataset.deadline = String(Date.now() + Math.max(0, Number(order.remainingSeconds) || 0) * 1000);
        });
      }
      if (!rows.length) $("#order-list").append(make("p", "muted", "暂时没有订单，去店铺看看优惠吧。"));
      $("#orders-page").textContent = selectedOrderId ? "当前订单" : "第 " + orderPage + " 页";
      $("#orders-prev").disabled = !!selectedOrderId || orderPage <= 1;
      $("#orders-next").disabled = !!selectedOrderId || orderPage * 10 >= Number(result.total || 0);
      updateCountdowns();
    } catch (failure) { orderSignature = ""; $("#order-list").replaceChildren(make("p", "muted", failure.message)); }
    finally { orderLoading = false; }
  }
  function showOrders(id = null) {
    if (!state.user) { showAccount(); return; }
    selectedOrderId = id; orderPage = 1;
    orderSignature = ""; orderGeneration++;
    $("#order-list").replaceChildren(make("p", "muted", "正在加载订单…"));
    if ($("#shop-dialog").open) $("#shop-dialog").close();
    showDialog("orders-dialog"); loadOrders();
  }
  $("#orders-button").addEventListener("click", () => showOrders());
  $("#orders-refresh").addEventListener("click", () => loadOrders());
  $("#orders-prev").addEventListener("click", () => { if (orderPage > 1) { orderPage--; loadOrders(); } });
  $("#orders-next").addEventListener("click", () => { orderPage++; loadOrders(); });
  setInterval(updateCountdowns, 1000);
  setInterval(() => { if ($("#orders-dialog").open && state.user) loadOrders(); }, 5000);
  function voucherElement(voucher) {
    const seckill = Number(voucher.type) === 1;
    const root = make("article", "voucher " + (seckill ? "seckill-voucher" : "regular-voucher"));
    const pay = Number(voucher.payValue) / 100;
    const faceValue = Number(voucher.actualValue) / 100;
    const amount = make("div", "voucher-price");
    amount.append(make("span", "voucher-price-label", seckill ? "秒杀价" : "优惠价"), make("strong", "", "¥" + price(pay)), make("small", "", "券面额 ¥" + price(faceValue)));
    const info = make("div", "voucher-info");
    info.append(make("span", "voucher-badge", seckill ? "限时秒杀" : "普通优惠券"), make("h4", "", text(voucher.title, "店铺代金券")));
    info.append(make("p", "voucher-offer", "付 " + price(pay) + " 元，消费时可抵扣 " + price(faceValue) + " 元" + (faceValue > pay ? "，省 " + price(faceValue - pay) + " 元" : "")));
    if (voucher.subTitle) info.append(make("p", "", text(voucher.subTitle)));
    info.append(make("p","voucher-rules",voucher.validUntil ? "使用有效期至 "+new Date(voucher.validUntil).toLocaleString("zh-CN") : "支付后 "+(voucher.validDays||30)+" 天内有效"));
    if (voucher.rules) info.append(make("p", "voucher-rules", plainText(voucher.rules)));
    const expired = Number(voucher.status) === 3 || (voucher.endTime && Date.parse(voucher.endTime) < Date.now());
    const upcoming = voucher.beginTime && Date.parse(voucher.beginTime) > Date.now();
    const orderStatus = make("p", "voucher-status", "");
    if (seckill) {
      const stock = Number(voucher.stock);
      const hasStock = Number.isFinite(stock) && stock > 0;
      const formatTime = value => {
        const date = new Date(value);
        return Number.isNaN(date.getTime()) ? "" : date.toLocaleString("zh-CN", { month: "long", day: "numeric", hour: "2-digit", minute: "2-digit", hour12: false });
      };
      if (voucher.beginTime && voucher.endTime) info.append(make("p", "voucher-time", formatTime(voucher.beginTime) + " 至 " + formatTime(voucher.endTime)));
      const stockLine = make("p", "voucher-stock", (Number.isFinite(stock) ? "剩余 " + Math.max(0, stock) + " 份" : "库存信息暂不可用") + " · 每人限购一张");
      info.append(stockLine);
      const submit = make("button", "button secondary voucher-submit", expired ? "活动已结束" : upcoming ? "即将开抢" : !hasStock ? "已售罄" : "立即抢购");
      submit.disabled = !!(expired || upcoming || !hasStock || voucher.ready===false);
      if(voucher.ready===false){submit.textContent="活动准备中";orderStatus.textContent="库存准备完成后可购买";}
      if (expired) orderStatus.textContent = "活动已结束";
      else if (upcoming) orderStatus.textContent = "活动尚未开始";
      else if (!hasStock) orderStatus.textContent = "暂无可抢库存";
      submit.addEventListener("click", () => submitSeckill(voucher, submit, orderStatus, stockLine));
      info.append(submit, orderStatus);
    } else {
      const available = Number(voucher.status) === 1 && voucher.ready !== false && (!voucher.validUntil || Date.parse(voucher.validUntil)>Date.now());
      const submit = make("button", "button secondary voucher-submit", available ? "立即购买" : "暂不可购买");
      submit.disabled = !available;
      submit.addEventListener("click", () => submitNormalVoucher(voucher, submit, orderStatus));
      info.append(make("p", "voucher-kind-note", "可多次购买，每次购买一张，使用条件请查看券说明。"), submit, orderStatus);
    }
    root.append(amount, info);
    return root;
  }
  async function refreshVoucherStock(voucher, stockLine) {
    try {
      const rows = await api("/voucher/list/" + encodeURIComponent(voucher.shopId));
      const current = rows.find(row => String(row.id) === String(voucher.id));
      const stock = current ? Number(current.stock) : NaN;
      if (!Number.isFinite(stock)) throw new Error("库存信息暂不可用");
      stockLine.textContent = "剩余 " + Math.max(0, stock) + " 份 · 每人限购一张";
      return stock;
    } catch (_) {
      stockLine.textContent = "库存刷新失败，请重新打开店铺确认。";
      return null;
    }
  }
  function submitSeckill(voucher, button, statusLine, stockLine) {
    return submitVoucherOrder(voucher, button, statusLine, stockLine);
  }
  function submitNormalVoucher(voucher, button, statusLine) {
    return submitVoucherOrder(voucher, button, statusLine);
  }
  async function submitVoucherOrder(voucher, button, statusLine, stockLine) {
    if (button.disabled) return;
    if (!state.user) { showAccount(); return; }
    const seckill = Number(voucher.type) === 1;
    button.disabled = true;
    statusLine.textContent = "正在提交订单…";
    let acceptedOrderId;
    try {
      const pendingKey = "neighborlife.normal-purchase:" + state.user.id + ":" + voucher.id;
      const headers = {};
      if (!seckill) {
        let requestId = sessionStorage.getItem(pendingKey);
        if (!requestId) {
          requestId = Array.from(crypto.getRandomValues(new Uint8Array(16)), value => value.toString(16).padStart(2, "0")).join("");
          sessionStorage.setItem(pendingKey, requestId);
        }
        headers["Idempotency-Key"] = requestId;
      }
      const orderId = await api("/voucher-order/" + (seckill ? "seckill/" : "normal/") + encodeURIComponent(voucher.id), { method: "POST", headers });
      if (typeof orderId !== "string" || !/^\d+$/.test(orderId)) throw new Error("订单编号异常，请刷新确认。");
      acceptedOrderId = orderId;
      if (!seckill) {
        sessionStorage.removeItem(pendingKey);
        statusLine.parentElement.querySelectorAll(".voucher-order-link").forEach(link => link.remove());
      }
      const openOrder = make("button", "text-button voucher-order-link", seckill ? "查看订单 / 去支付" : "查看最新订单 / 去支付");
      openOrder.addEventListener("click", () => showOrders(orderId)); statusLine.after(openOrder);
      statusLine.textContent = "订单处理中，编号：" + orderId;
      for (let attempt = 0; attempt < 20; attempt++) {
        const order = await api("/voucher-order/" + encodeURIComponent(orderId));
        if (order.state === "CREATED") {
          button.textContent = seckill ? "已抢购" : "再买一张";
          const status = ({ 1: "待支付", 2: "已支付", 3: "已核销", 4: "已取消", 5: "退款中", 6: "已退款" })[Number(order.status)] || "请查询订单状态";
          statusLine.textContent = "订单已创建，" + status + "。编号：" + orderId;
          if (stockLine) await refreshVoucherStock(voucher, stockLine);
          return;
        }
        await new Promise(resolve => setTimeout(resolve, 1000));
      }
      statusLine.textContent = "订单仍在处理中，请稍后查看。编号：" + orderId;
    } catch (error) {
      statusLine.textContent = acceptedOrderId ? "订单已受理，请稍后查询。编号：" + acceptedOrderId : error.message;
      button.disabled = !!acceptedOrderId || error.message.includes("重复下单");
      if (button.disabled) button.textContent = "已提交";
      if (stockLine && error.message.includes("库存不足")) {
        const stock = await refreshVoucherStock(voucher, stockLine);
        if (stock === 0) { button.disabled = true; button.textContent = "已售罄"; }
      }
    } finally {
      if (!seckill) {
        button.disabled = false;
        button.textContent = acceptedOrderId ? "再买一张" : "重试购买";
      }
    }
  }
  async function showShop(id) {
    state.activeShop = id;
    const root = $("#shop-detail");
    const placeholder = make("div", "detail-info");
    const loadingTitle = make("h2", "", "正在打开这家店…");
    loadingTitle.id = "shop-detail-name";
    placeholder.append(loadingTitle, make("p", "muted", "好生活，就在附近。"));
    root.replaceChildren(placeholder);
    showDialog("shop-dialog");
    const results = await Promise.allSettled([api("/shop/" + encodeURIComponent(id)), api("/voucher/list/" + encodeURIComponent(id))]);
    if (state.activeShop !== id) return;
    if (results[0].status === "rejected") { loadingTitle.textContent = "店铺暂时无法打开"; $(".muted", placeholder).textContent = results[0].reason.message; return; }
    const shop = results[0].value;
    if (!shop) { loadingTitle.textContent = "店铺暂时不存在"; return; }
    root.replaceChildren();
    const banner = make("header", "detail-banner");
    const symbol = make("span", "cover-symbol"); symbol.innerHTML = icon(categoryIcon(typeName(shop.typeId)));
    const heading = make("div");
    const title = make("h2", "", shop.name); title.id = "shop-detail-name";
    const summary = make("div", "detail-summary");
    summary.append(make("strong", "", shop.score ? "★ " + (Number(shop.score) / 10).toFixed(1) : "暂无评分"), make("span", "", shop.avgPrice == null ? "人均未提供" : "人均 ¥" + price(shop.avgPrice)), make("span", "", text(shop.comments, "0") + " 条评价"));
    heading.append(make("p", "eyebrow", typeName(shop.typeId) + " / " + text(shop.area, "同城")), title, summary);
    banner.append(symbol, heading);
    const info = make("section", "detail-info");
    const details = make("dl");
    [["地址", text(shop.address, "商家暂未提供")], ["营业时间", text(shop.openHours, "商家暂未提供")], ["商圈", text(shop.area, "商家暂未提供")]].forEach(([label, value]) => details.append(make("dt", "", label), make("dd", "", value)));
    info.append(make("h3", "", "这家店的小档案"), details);
    if (shop.address) {
      const copy = make("button", "text-button", "复制店铺地址");
      addIcon(copy, "pin");
      copy.addEventListener("click", async () => { try { await navigator.clipboard.writeText(shop.address); toast("地址已复制"); } catch (_) { toast("请从店铺详情中手动复制地址。"); } });
      info.append(copy);
    }
    const vouchers = make("section", "voucher-section");
    vouchers.append(make("h3", "", "店铺专属优惠"));
    if (results[1].status === "fulfilled") {
      const rows = Array.isArray(results[1].value) ? results[1].value : [];
      if (rows.length) {
        [[1, "限时秒杀"], [0, "普通优惠券"]].forEach(([type, title]) => {
          const group = rows.filter(voucher => Number(voucher.type) === type);
          if (!group.length) return;
          const section = make("div", "voucher-group");
          section.append(make("h4", "voucher-group-title", title), ...group.map(voucherElement));
          vouchers.append(section);
        });
      }
      else vouchers.append(make("p", "voucher-empty", "这家店暂时没有发布优惠券，先收藏起来吧。"));
    } else vouchers.append(make("p", "voucher-empty", "优惠信息暂时无法加载。"));
    root.append(banner, info, vouchers);
    document.dispatchEvent(new CustomEvent("neighbor:shop",{detail:{shop,root:info}}));
  }
  async function showNote(id) {
    let note;try {note=await api("/blog/"+encodeURIComponent(id));}catch(error){toast(error.message);return;}
    const root = $("#note-detail");
    const header = make("header", "note-detail-header");
    const title = make("h2", "", note.title); title.id = "note-detail-title";
    const footer = make("div", "note-footer");
    const like = make("button", "like-button" + (note.isLike ? " liked" : ""));
    like.dataset.like = note.id;
    like.setAttribute("aria-label", "点赞这篇笔记");
    addIcon(like, "heart"); like.append(make("span", "like-count", text(note.liked, "0")));
    footer.append(make("span", "", text(note.name, "邻里探店人") + " · " + text(note.createTime).slice(0, 10)), like);
    header.append(make("p", "eyebrow", "NEIGHBORHOOD STORIES"), title, footer);
    root.replaceChildren(header, make("div", "note-content", plainText(note.content) || "这篇笔记暂时没有正文。"));
    if (note.shopId) {
      const link = make("button", "button secondary note-shop-link", "看看笔记里的这家店"); addIcon(link, "arrow");
      link.addEventListener("click", () => { $("#note-dialog").close(); showShop(String(note.shopId)); });
      root.append(link);
    }
    showDialog("note-dialog");
    document.dispatchEvent(new CustomEvent("neighbor:note",{detail:{note,root}}));
  }
  async function likeNote(id, button) {
    if (!state.user) { showAccount(); return; }
    button.disabled = true;
    try {
      const before=await api("/blog/"+encodeURIComponent(id));
      await api("/blog/"+encodeURIComponent(id)+"/like",{method:"PUT",body:JSON.stringify({liked:!before.isLike})});
      const persisted = await api("/blog/" + encodeURIComponent(id));
      const matches = [...state.notes, ...inspirationNotes].filter(note => String(note.id) === id);
      const reference = matches[0];
      if (reference) {
        const liked = !!persisted.isLike;
        const count = Math.max(0, Number(persisted.liked) || 0);
        matches.forEach(note => { note.isLike = liked; note.liked = count; });
        $$("[data-like]").filter(el => el.dataset.like === id).forEach(el => { el.classList.toggle("liked", liked); $(".like-count", el).textContent = count; });
      }
    } catch (error) { toast(error.message); }
    finally { button.disabled = false; }
  }
  function updateAccount() {
    $("#account-label").textContent = state.user ? text(state.user.nickName, "我的账号") : "登录 / 注册";
    $("#login-panel").hidden = !!state.user;
    $("#profile-panel").hidden = !state.user;
    $("#profile-name").textContent = state.user ? text(state.user.nickName, "欢迎回来") : "欢迎回来";
    $("#account-dialog").setAttribute("aria-labelledby", state.user ? "profile-name" : "account-title");
    document.dispatchEvent(new CustomEvent("neighbor:account",{detail:state.user}));
    if(state.user)refreshSaved().catch(error=>toast(error.message));
  }
  function showAccount() { updateAccount(); $("#login-message").textContent = ""; showDialog("account-dialog"); }
  let codeTimer;
  $("#send-code").addEventListener("click", async () => {
    const phone = $("#phone-input").value.trim();
    if (!/^1[3-9]\d{9}$/.test(phone)) { $("#login-message").textContent = "请输入正确的 11 位手机号。"; $("#phone-input").focus(); return; }
    const button = $("#send-code");
    button.disabled = true;
    try {
      const response=await api("/user/code?phone=" + encodeURIComponent(phone), { method: "POST" });
      $("#login-message").textContent = response?.developmentCode ? "本地模拟验证码："+response.developmentCode+"（不发送短信）" : "验证码已生成，请按服务端配置获取。";
      let seconds = 60; button.textContent = seconds + " 秒后重试";
      clearInterval(codeTimer);
      codeTimer = setInterval(() => { seconds--; button.textContent = seconds + " 秒后重试"; if (seconds <= 0) { clearInterval(codeTimer); button.disabled = false; button.textContent = "获取验证码"; } }, 1000);
    } catch (error) { $("#login-message").textContent = error.message; button.disabled = false; }
  });
  $("#login-form").addEventListener("submit", async event => {
    event.preventDefault();
    const button = $("#login-submit");
    button.disabled = true;
    $("#login-message").textContent = "正在登录…";
    try {
      const token = await api("/user/login", { method: "POST", body: JSON.stringify({ phone: $("#phone-input").value.trim(), code: $("#code-input").value.trim() }) });
      if (typeof token !== "string" || !token) throw new Error("登录状态异常，请重新尝试。");
      sessionStorage.setItem("neighborlife.token", token);
      state.user = await api("/user/me");
      updateAccount();
      $("#account-dialog").close();
      $("#code-input").value = "";
      toast("欢迎回来，邻居。");
    } catch (error) { $("#login-message").textContent = error.message; }
    finally { button.disabled = false; }
  });
  $("#account-button").addEventListener("click", showAccount);
  $("#clear-session").addEventListener("click", async () => { try{await api("/user/logout",{method:"POST"});clearAuth();$("#account-dialog").close();toast("已退出，服务端会话已撤销");}catch(error){toast(error.message);} });
  $("#view-saved").addEventListener("click", () => { $("#account-dialog").close(); setView("saved"); });
  $$("[data-view]").forEach(button => button.addEventListener("click", () => { if (state.view !== button.dataset.view) setView(button.dataset.view); }));
  $("#search-form").addEventListener("submit", event => {
    event.preventDefault();
    state.search = $("#search-input").value.trim();
    state.type = null;
    state.page = 1;
    renderCategories();
    if (state.view !== "discover") setView("discover"); else loadShops();
  });
  $("#clear-filters").addEventListener("click", resetFilters);
  $("#sort-select").addEventListener("change",()=>{state.page=1;if(state.view==="discover")loadShops();else renderShops();});
  $("#all-notes").addEventListener("click", () => setView("notes"));
  $("#explore-button").addEventListener("click", () => { resetFilters(); $("#results-heading").scrollIntoView({ behavior: "smooth", block: "start" }); });
  $("#retry-button").addEventListener("click", () => state.view === "notes" ? loadNotes() : loadShops());
  $("#previous-page").addEventListener("click", async () => { if (state.loading || state.page <= 1) return; state.page--; await (state.view === "notes" ? loadNotes() : loadShops()); $("#results-heading").scrollIntoView({ behavior: "smooth", block: "start" }); });
  $("#next-page").addEventListener("click", async () => { if (state.loading || !state.hasNext) return; state.page++; await (state.view === "notes" ? loadNotes() : loadShops()); $("#results-heading").scrollIntoView({ behavior: "smooth", block: "start" }); });
  document.addEventListener("click", event => {
    const close = event.target.closest("[data-close]");
    if (close) { $("#" + close.dataset.close).close(); return; }
    const save = event.target.closest("[data-save]");
    if (save) { toggleSaved(save.dataset.save); return; }
    const shop = event.target.closest("[data-shop]");
    if (shop) { showShop(shop.dataset.shop); return; }
    const note = event.target.closest("[data-note]");
    if (note) { showNote(note.dataset.note); return; }
    const like = event.target.closest("[data-like]");
    if (like && !like.disabled) likeNote(like.dataset.like, like);
  });
  $$("dialog").forEach(dialog => dialog.addEventListener("click", event => {
    if (event.target !== dialog) return;
    const rect = dialog.getBoundingClientRect();
    if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) dialog.close();
  }));
  window.NeighborLife={api,toast,make,state,showShop,showNote,showOrders,showAccount,refreshSaved,loadShops,setView,updateAccount,loadNotes,plainText};
  fillIcons();
  renderCategories();
  updateSavedBadge();
  loadShops();
  loadNotes(true);
  api("/shop-type/list").then(rows => { state.types = Array.isArray(rows) ? rows : []; renderCategories(); if (state.view !== "notes" && !state.loading) renderShops(); }).catch(() => { toast("分类暂时无法加载，仍可搜索店铺。"); });
  if (sessionStorage.getItem("neighborlife.token")) api("/user/me").then(user => { state.user = user || null; updateAccount(); }).catch(clearAuth);
  const requestedShop = new URLSearchParams(window.location.search).get("shop");
  if (requestedShop && /^[1-9]\d*$/.test(requestedShop)) showShop(requestedShop);
})();
