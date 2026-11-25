(() => {
  "use strict";
  const app = window.NeighborLife;
  const { api, make, toast, state } = app;
  const money = cents => "¥" + (Number(cents) / 100).toFixed(2);
  const when = value => value ? new Date(value).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" }) : "不限期";
  let capabilities = {};
  const dialog = make("dialog", "business-dialog");
  dialog.id = "business-dialog";
  dialog.setAttribute("aria-labelledby", "business-title");
  const close = make("button", "dialog-close", "×");
  close.setAttribute("aria-label", "关闭业务窗口");
  close.addEventListener("click", () => dialog.close());
  const content = make("div", "business-content");
  dialog.append(close, content); document.body.append(dialog);

  function open(title) {
    document.querySelectorAll("dialog[open]").forEach(d => d.close());
    const heading = make("h2", "", title); heading.id = "business-title";
    content.replaceChildren(heading);
    dialog.showModal();
    return content;
  }
  function loggedIn() { if (state.user) return true; app.showAccount(); return false; }
  function button(label, action, style = "button secondary") {
    const b = make("button", style, label); b.type = "button";
    b.addEventListener("click", async () => { b.disabled = true; try { await action(); } catch (e) { toast(e.message); } finally { b.disabled = false; } });
    return b;
  }
  // 网络失败时保留同一个请求号。重试支付/退款/核销不能产生第二笔业务记录。
  function requestKey(operation) {
    const name = "neighborlife.request:" + state.user?.id + ":" + operation;
    let key = sessionStorage.getItem(name);
    if (!key) { key = crypto.randomUUID(); sessionStorage.setItem(name, key); }
    return key;
  }
  const post = (path, body, operation) => api(path, { method: "POST", headers: { "Idempotency-Key": requestKey(operation || path) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  function field(form, label, value = "", type = "text", required = true) {
    const row = make("label", "business-field", label);
    const input = make(type === "textarea" ? "textarea" : "input");
    if (type !== "textarea") input.type = type;
    input.value = value ?? ""; input.required = required;
    if (type === "number") { input.min = "0"; input.step = "1"; }
    if (type === "textarea") input.rows = 4;
    row.append(input); form.append(row); return input;
  }
  function submit(form, label, action) {
    const b = make("button", "button primary", label); b.type = "submit";
    const message = make("p", "form-message"); message.setAttribute("role", "status");
    form.append(message, b);
    form.addEventListener("submit", async e => {
      e.preventDefault(); b.disabled = true; message.textContent = "正在提交…";
      try { await action(); message.textContent = "已保存"; } catch (error) { message.textContent = error.message; } finally { b.disabled = false; }
    });
  }
  async function upload(file) {
    const body = new FormData(); body.append("file", file);
    return api("/upload/blog", { method: "POST", body });
  }
  function pictureField(form, label, previous = "") {
    const file = field(form, label, "", "file", false); file.accept = "image/png,image/jpeg";
    return async () => file.files.length ? upload(file.files[0]) : previous;
  }
  function images(root, paths) {
    for (const path of String(paths || "").split(",")) {
      // 展示本系统已验证的上传图片；旧资料未配置图片资源时仍使用既有占位设计。
      if (!/^\/media\/[a-f0-9-]{36}\.(png|jpg)$/.test(path)) continue;
      const img = make("img", "business-image"); img.src = path; img.alt = "上传图片"; img.loading = "lazy"; root.append(img);
    }
  }
  async function paged(root, endpoint, renderer, size = 10, pageName = "page") {
    const list = make("div", "business-list"), pager = make("div", "business-pager");
    let page = 1;
    const label = make("span"), prev = button("上一页", () => { page--; return load(); }), next = button("下一页", () => { page++; return load(); });
    pager.append(prev, label, next); root.append(list, pager);
    async function load() {
      const result = await api(endpoint + (endpoint.includes("?") ? "&" : "?") + pageName + "=" + page, {}, true);
      const rows = Array.isArray(result.data) ? result.data : [];
      list.replaceChildren(...rows.map(renderer));
      if (!rows.length) list.append(make("p", "muted", "暂时没有记录"));
      label.textContent = "第 " + page + " 页"; prev.disabled = page === 1;
      next.disabled = result.total == null ? rows.length < size : page * size >= Number(result.total);
    }
    await load(); return load;
  }
  async function owned() {
    if (!loggedIn()) return;
    const root = open("我的持有券");
    root.append(make("p", "muted", "模拟支付成功后发券。核销码只交给所属店铺的商家；未核销时可按购买规则退款。"));
    await paged(root, "/user-vouchers/mine", voucher => {
      const card = make("article", "business-card");
      card.append(make("h3", "", voucher.title), make("p", "", "券面额 " + money(voucher.actualValue)), make("p", "", ({ UNUSED: "可使用", USED: "已核销", REFUNDING: "退款中", REFUNDED: "已退款", EXPIRED: "已过期" })[voucher.status] || voucher.status), make("p", "", "有效期：" + when(voucher.validUntil)));
      if (voucher.status === "UNUSED") card.append(button("查看核销凭证", async () => {
        const detail = await api("/user-vouchers/" + voucher.id);
        const panel = open("核销凭证"); panel.append(make("h3", "", detail.title), make("p", "", "仅向所属店铺出示，核销后不可退款。"), make("code", "voucher-code", detail.code), make("p", "", detail.rules), make("p", "", "最低消费 " + money(detail.minSpend)), button("返回我的券", owned));
      }));
      card.append(button("查看来源订单", () => { dialog.close(); app.showOrders(voucher.orderId); })); return card;
    });
  }
  function review(order) {
    if (!loggedIn()) return;
    const root = open("评价这次消费"), form = make("form"); root.append(form);
    const score = field(form, "评分（1–5）", 5, "number"); score.min = 1; score.max = 5;
    const words = field(form, "消费评价", "", "textarea"); words.maxLength = 1000;
    submit(form, "发表评价", async () => { await post("/voucher-order/" + order.id + "/review", { score: Number(score.value), content: words.value }); dialog.close(); app.showShop(order.shopId); });
  }
  async function profile() {
    if (!loggedIn()) return;
    const user = await api("/user/me"), root = open("编辑个人资料"), form = make("form"); root.append(form);
    const nickname = field(form, "昵称", user.nickName); nickname.maxLength = 32;
    const city = field(form, "城市", user.city, "text", false); city.maxLength = 32;
    const intro = field(form, "个人简介", user.introduce, "textarea", false); intro.maxLength = 128;
    const getIcon = pictureField(form, "头像（JPEG/PNG，5 MB 以内）", user.icon);
    submit(form, "保存资料", async () => {
      state.user = await api("/user/me", { method: "PATCH", body: JSON.stringify({ nickName: nickname.value, city: city.value, introduce: intro.value, icon: await getIcon() }) });
      app.updateAccount(); toast("个人资料已保存");
    });
  }
  async function signin(month) {
    if (!loggedIn()) return;
    month ||= new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit" }).format(new Date()).slice(0, 7);
    const data = await api("/user/sign/month?month=" + month), root = open("签到日历");
    const picker = make("input"); picker.type = "month"; picker.value = month;
    picker.addEventListener("change", () => signin(picker.value).catch(e => toast(e.message)));
    root.append(picker, make("p", "", "当前连续签到 " + data.continuousDays + " 天"));
    const calendar = make("div", "sign-calendar");
    const count = new Date(Number(month.slice(0, 4)), Number(month.slice(5)), 0).getDate();
    for (let day = 1; day <= count; day++) calendar.append(make("span", data.signedDays.includes(day) ? "signed" : "", String(day)));
    root.append(calendar, button("今日签到", async () => { await post("/user/sign"); await signin(month); }));
  }
  async function blogForm(note) {
    if (!loggedIn()) return;
    const root = open(note ? "编辑探店笔记" : "发布探店笔记"), form = make("form"); root.append(form);
    const title = field(form, "标题", note?.title); title.maxLength = 255;
    const shopId = field(form, "关联店铺编号", note?.shopId || state.activeShop || "", "text"); shopId.pattern = "[1-9][0-9]*"; shopId.disabled = !!note;
    const words = field(form, "正文", note ? app.plainText(note.content) : "", "textarea"); words.maxLength = 10000;
    const getImages = pictureField(form, "图片（JPEG/PNG，5 MB 以内）", note?.images || "");
    let uploaded;
    submit(form, note ? "保存修改" : "发布笔记", async () => {
      uploaded ??= await getImages();
      const body = { shopId: shopId.value, title: title.value, content: words.value, images: uploaded };
      const result = await api(note ? "/blog/" + note.id : "/blog", { method: note ? "PATCH" : "POST", body: JSON.stringify(body) });
      toast("笔记已保存"); dialog.close(); await app.showNote(note?.id || result);
    });
  }
  async function mineNotes() {
    if (!loggedIn()) return;
    const root = open("我的笔记"); root.append(button("发布笔记", () => blogForm()));
    // 原接口的页码叫 current，复用列表组件时在读取处显式转换。
    let page = 1;
    const list = make("div"), controls = make("div", "business-pager"); root.append(list, controls);
    const prev = button("上一页", () => { page--; return load(); }), next = button("下一页", () => { page++; return load(); }); controls.append(prev, next);
    async function load() { const rows = await api("/blog/of/me?current=" + page); list.replaceChildren(...rows.map(n => button(n.title, () => { dialog.close(); return app.showNote(n.id); }, "business-card text-button"))); prev.disabled = page === 1; next.disabled = rows.length < 10; if (!rows.length) list.append(make("p", "muted", "还没有笔记")); }
    await load();
  }
  async function feed() {
    if (!loggedIn()) return;
    const root = open("关注动态"), list = make("div", "business-list"); root.append(list);
    let max = Date.now(), offset = 0;
    const more = button("加载更多", load); root.append(more);
    async function load() {
      const data = await api("/blog/of/follow?lastId=" + max + "&offset=" + offset);
      max = data.minTime; offset = data.offset;
      data.list.forEach(n => list.append(button(n.title + " · " + n.name, () => { dialog.close(); return app.showNote(n.id); }, "business-card text-button")));
      more.hidden = data.list.length === 0; if (!list.children.length) list.append(make("p", "muted", "关注探店作者后，这里会展示他们的笔记"));
    }
    await load();
  }
  document.addEventListener("neighbor:note", async event => {
    const { note, root } = event.detail;
    images(root, note.images);
    const actions = make("div", "business-pager"); root.append(actions);
    actions.append(button("查看作者主页", async () => {
      const person = await api("/community/users/" + note.userId), panel = open(person.nickName + "的主页");
      panel.append(make("p", "", person.fans + " 位粉丝 · 关注了 " + person.followees + " 人"));
      panel.append(button("查看作者笔记", async () => {
        const list = open(person.nickName + "的笔记");
        await paged(list, "/blog/of/user?id=" + note.userId, n => button(n.title, () => { dialog.close(); return app.showNote(n.id); }, "business-card text-button"), 10, "current");
      }));
      if (state.user && String(state.user.id) !== String(note.userId)) {
        let following = person.following;
        const follow = button(following ? "取消关注" : "关注作者", async () => { await api("/follow/" + note.userId + "/" + !following, { method: "PUT" }); following = await api("/follow/or/not/" + note.userId); follow.textContent = following ? "取消关注" : "关注作者"; }); panel.append(follow);
        panel.append(button("查看共同关注", async () => { const rows = await api("/follow/common/" + note.userId); panel.append(make("p", "", rows.length ? rows.map(u => u.nickName).join("、") : "暂时没有共同关注")); }));
      }
    }));
    if (state.user && String(note.userId) === String(state.user.id)) actions.append(button("编辑笔记", () => blogForm(note)), button("删除笔记", async () => { await api("/blog/" + note.id, { method: "DELETE" }); document.querySelector("#note-dialog").close(); await mineNotes(); }));
    const comments = make("section", "business-comments"); root.append(comments); comments.append(make("h3", "", "评论与回复"));
    let reply = 0;
    const form = make("form"), words = field(form, "评论内容", "", "textarea"); words.maxLength = 255;
    const replyStatus = make("p", "muted"); form.append(replyStatus);
    if (state.user) comments.append(form);
    try {
      const refresh = await paged(comments, "/blog/" + note.id + "/comments", c => {
        const row = make("article", "business-card"); row.append(make("p", "", c.name + (Number(c.parentId) > 0 ? " · 回复 #" + c.parentId : "")), make("p", "", c.content));
        if (state.user) row.append(button("回复", () => { reply = c.id; replyStatus.textContent = "正在回复 " + c.name; words.focus(); }));
        if (state.user && String(c.userId) === String(state.user.id)) row.append(button("删除我的评论", async () => { await api("/blog-comments/" + c.id, { method: "DELETE" }); await refresh(); })); return row;
      });
      submit(form, "发表评论", async () => { await post("/blog/" + note.id + "/comments", { content: words.value, parentId: reply }); words.value = ""; reply = 0; replyStatus.textContent = ""; await refresh(); });
    } catch (e) { comments.append(make("p", "muted", e.message)); }
  });
  document.addEventListener("neighbor:shop", async event => {
    const { shop, root } = event.detail; images(root, shop.images);
    const reviews = make("section", "business-comments"); reviews.append(make("h3", "", "真实消费评价")); root.append(reviews);
    try { await paged(reviews, "/shop/" + shop.id + "/reviews", r => { const card = make("article", "business-card"); card.append(make("p", "", r.name + " · " + r.score + " 星"), make("p", "", r.content)); return card; }); } catch (e) { reviews.append(make("p", "muted", e.message)); }
  });
  async function merchant() {
    if (!loggedIn()) return;
    const shops = await api("/merchant/shops"), root = open("商家工作台");
    root.append(make("p", "muted", "仅可维护已授权店铺。模拟交易不发生扣款与结算。"));
    if (!shops.length) root.append(make("p", "", "当前账号还没有店铺授权，请由管理员添加店铺成员"));
    for (const shop of shops) root.append(button(shop.name + (Number(shop.status) === 1 ? " · 营业中" : " · 已下架"), () => manageShop(shop), "business-card text-button"));
    if (capabilities.admin) root.append(button("新增店铺", () => shopForm()));
    root.append(button("核销顾客优惠券", redemption));
  }
  function shopForm(shop) {
    const root = open(shop ? "维护店铺资料" : "新增店铺"), form = make("form"); root.append(form);
    const fields = {};
    [["name", "店铺名称"], ["typeId", "分类编号"], ["address", "地址"], ["area", "商圈"], ["openHours", "营业时间"], ["x", "经度"], ["y", "纬度"], ["avgPrice", "人均价格（元）"]].forEach(([key, label]) => { fields[key] = field(form, label, shop?.[key] ?? ""); });
    const getImages = pictureField(form, "店铺图片", shop?.images || "");
    submit(form, "保存店铺", async () => { const body = { ...(shop ? { id: shop.id } : {}), images: await getImages() }; Object.entries(fields).forEach(([k, input]) => body[k] = ["x", "y", "avgPrice", "typeId"].includes(k) ? Number(input.value) : input.value); await api("/shop", { method: shop ? "PUT" : "POST", body: JSON.stringify(body) }); await merchant(); });
  }
  async function manageShop(shop) {
    const [vouchers, stats] = await Promise.all([api("/merchant/vouchers?shopId=" + shop.id), api("/merchant/shops/" + shop.id + "/stats")]);
    const root = open(shop.name);
    root.append(make("p", "", "有效支付订单 " + stats.paidOrders + " · 已核销 " + stats.redemptions + " · 退款 " + stats.refunds + " · 今日访客约 " + (stats.todayUv ?? "暂不可用")), button("编辑店铺", () => shopForm(shop)), button(Number(shop.status) === 1 ? "下架店铺" : "上架店铺", async () => { await post("/merchant/shops/" + shop.id + "/status", { status: Number(shop.status) === 1 ? 2 : 1 }); await merchant(); }), button("发布普通券", () => voucherForm(shop, 0)), button("发布秒杀券", () => voucherForm(shop, 1)), button("核销记录", async () => { const panel = open("核销记录"); await paged(panel, "/merchant/shops/" + shop.id + "/redemptions", r => make("p", "business-card", r.orderId + " · 消费 " + money(r.consumptionAmount) + " · " + when(r.createTime))); }), button("查看同步任务", async () => { const rows = await api("/merchant/shops/" + shop.id + "/events"), panel = open("店铺同步任务"); rows.forEach(r => panel.append(make("p", "business-card", r.eventType + " · " + ({ 0: "待投递", 1: "已投递", 2: "完成", 3: "阻塞，需管理员处理" })[r.status]))); }));
    for (const voucher of vouchers) {
      const card = make("article", "business-card"); card.append(make("h3", "", voucher.title), make("p", "", money(voucher.payValue) + " / 面额 " + money(voucher.actualValue) + (Number(voucher.type) === 1 ? " · 数据库可用库存 " + voucher.stock : " · 普通券不限购买次数")), make("p", "", Number(voucher.ready) === 0 ? "库存准备中，可查看同步任务" : Number(voucher.status) === 1 ? "可售" : "已下架"), button("编辑规则与价格", () => voucherForm(shop, Number(voucher.type), voucher)), button(Number(voucher.status) === 1 ? "下架优惠券" : "上架优惠券", async () => { await post("/merchant/vouchers/" + voucher.id + "/status", { status: Number(voucher.status) === 1 ? 2 : 1 }); await manageShop(shop); })); root.append(card);
    }
  }
  function voucherForm(shop, type, voucher) {
    const root = open(voucher ? "编辑优惠券" : type === 1 ? "发布秒杀券" : "发布普通券"), form = make("form"); root.append(form);
    root.append(make("p", "muted", "金额单位为分；商品修改只影响新订单。已创建的秒杀库存与活动时间保留。"));
    const title = field(form, "券名称", voucher?.title), rules = field(form, "使用说明", voucher?.rules, "textarea"), price = field(form, "购买价（分）", voucher?.payValue || 8000, "number"), face = field(form, "券面额（分）", voucher?.actualValue || 10000, "number"), days = field(form, "支付后有效天数", voucher?.validDays ?? 30, "number"), min = field(form, "最低消费（分）", voucher?.minSpend || 0, "number");
    const refundable = field(form, "允许未核销全额退款", "", "checkbox", false); refundable.checked = voucher ? Number(voucher.refundAllowed) === 1 : true;
    let stock, begin, end;
    if (type === 1 && !voucher) { stock = field(form, "活动总库存", 100, "number"); begin = field(form, "开抢时间（北京时间）", "", "datetime-local"); end = field(form, "结束时间（北京时间）", "", "datetime-local"); }
    submit(form, voucher ? "保存规则" : "发布优惠券", async () => {
      const body = { shopId: shop.id, title: title.value, rules: rules.value, payValue: Number(price.value), actualValue: Number(face.value), validDays: Number(days.value), validUntil: null, minSpend: Number(min.value), refundAllowed: refundable.checked };
      if (stock) Object.assign(body, { stock: Number(stock.value), beginTime: begin.value, endTime: end.value });
      await api(voucher ? "/merchant/vouchers/" + voucher.id : type === 1 ? "/voucher/seckill" : "/voucher", { method: voucher ? "PATCH" : "POST", body: JSON.stringify(body) }); await manageShop(shop);
    });
  }
  function redemption() {
    if (!loggedIn()) return;
    const root = open("核销优惠券"), form = make("form"); root.append(form);
    const code = field(form, "顾客核销码"), amount = field(form, "本次实际消费金额（分）", "", "number"); amount.min = "1";
    submit(form, "确认核销", async () => { const result = await post("/merchant/redemptions", { code: code.value.trim(), consumptionAmount: Number(amount.value) }, "redeem:" + code.value.trim()); toast("核销成功，记录编号：" + result.id); });
  }
  async function operations() {
    const data = await api("/admin/operations"), root = open("管理员运维与授权"), form = make("form"); root.append(form);
    const user = field(form, "商家用户编号"), shop = field(form, "店铺编号");
    submit(form, "授予店铺管理权限", () => post("/admin/shop-members", { userId: user.value, shopId: shop.value }));
    root.append(button("撤销该店铺授权", () => api("/admin/shop-members?userId=" + encodeURIComponent(user.value) + "&shopId=" + encodeURIComponent(shop.value), { method: "DELETE" })));
    for (const [name, label, path] of [["events", "阻塞可靠事件", "events"], ["notices", "阻塞支付通知", "payment-notices"], ["refunds", "阻塞退款", "refunds"]]) {
      root.append(make("h3", "", label));
      if (!data[name].length) root.append(make("p", "muted", "没有阻塞记录"));
      data[name].forEach(r => { const card = make("article", "business-card"); card.append(make("p", "", r.id + " · " + (r.lastError || "等待处理")), button("修复依赖后重新投递", async () => { await post("/admin/" + path + "/" + r.id + "/retry"); await operations(); })); root.append(card); });
    }
    root.append(make("h3", "", "最近操作审计")); data.audit.forEach(r => root.append(make("p", "", r.action + " · " + r.resourceId + " · 操作人 " + r.operatorId)));
  }
  const entry = make("div", "business-entry"); document.querySelector("#profile-panel").append(entry);
  async function updateEntries() {
    entry.replaceChildren(); capabilities = {};
    if (!state.user) return;
    const account = String(state.user.id);
    entry.append(button("我的持有券", owned), button("签到日历", () => signin()), button("编辑资料", profile), button("我的笔记", mineNotes), button("发布笔记", () => blogForm()), button("关注动态", feed));
    try { const value = await api("/user/capabilities"); if (!state.user || String(state.user.id) !== account) return; capabilities = value; if (value.merchant) entry.append(button("商家工作台", merchant)); if (value.admin) entry.append(button("管理员运维", operations)); } catch (e) { entry.append(make("p", "muted", e.message)); }
    let guest=[];try{guest=JSON.parse(localStorage.getItem("neighborlife.saved") || "[]");}catch(_){guest=[];}
    if (Array.isArray(guest) && guest.length) entry.append(button("将访客收藏加入我的账号", async () => { for (const shop of guest) await api("/shop/" + shop.id + "/favorite", { method: "PUT" }); await app.refreshSaved(); toast("访客收藏已加入账号"); }));
  }
  document.addEventListener("neighbor:account", updateEntries);
  const locate = button("定位附近好店", async () => {
    if (state.location) { state.location = null; locate.textContent = "定位附近好店"; }
    else { const result = await new Promise((resolve, reject) => navigator.geolocation.getCurrentPosition(resolve, () => reject(new Error("定位未获授权，可继续按名称或分类查询")), { timeout: 10000 })); state.location = { x: result.coords.longitude, y: result.coords.latitude }; locate.textContent = "已定位 · 关闭附近筛选"; }
    state.page = 1; if(state.view!=="discover")app.setView("discover");else await app.loadShops();
  }); document.querySelector(".location").replaceWith(locate);
  window.NeighborBusiness = { owned, review, requestKey, merchant, profile, signin, blogForm, feed };
  updateEntries().catch(e => toast(e.message));
})();
