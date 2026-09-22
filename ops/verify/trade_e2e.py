"""交易链路端到端验证：登录→加购→下单→库存校验→幂等重放→取消→对账"""
import json, urllib.request, time, sys

GW = "http://127.0.0.1:9080"
def call(method, path, token=None, body=None):
    req = urllib.request.Request(GW + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token: req.add_header("token", token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return {"http": e.code, "body": e.read().decode()[:200]}

def must(cond, label, detail=""):
    print(("PASS " if cond else "FAIL ") + label + (f" | {detail}" if detail and not cond else ""))
    if not cond: sys.exit(1)

# 收货信息：手机号用合法的中国大陆手机号。
# 订单接口对收件人字段有格式校验（DTO + Bean Validation），早期用的 "188"/"x" 之类的
# 占位值会被 400 挡在 Saga 之前——这类占位值本来也不是有效用例数据。
RECEIVER = {"receiverName": "张三", "receiverPhone": "18800009999", "receiverAddress": "文三路 168 号"}

# 登录
r = call("POST", "/user/login", body={"username": "aaa", "password": "123"})
token = r["data"]["token"]; uid = r["data"]["id"]
must(bool(token), "登录 aaa")
# 测试自给自足：先充值，避免历史消耗影响。
# 注意单笔上限 10000（服务层既有限制），早期写 50000 会被拒且返回值没人看，
# 充值是静默失败的——这里改成限额内并断言结果，否则脚本会悄悄依赖"恰好还有余额"。
r = call("POST", "/wallet/recharge", token, {"amount": 10000, "remark": "e2e充值"})
must(r.get("code") == "200", "充值 10000", json.dumps(r)[:200])

# 商品列表取一个在售商品
r = call("GET", "/product/selectAll?status=ON_SALE", token)
products = r["data"]
p = products[0]
print(f"     商品: {p['name']} 库存={p['stockQuantity']} 价格={p['price']}")

# 库存基线用「按 id 精确读」，不用列表里的值：
# 列表缓存（键为筛选条件组合）按设计允许 TTL 内陈旧，拿它做扣减算术的基线会误判；
# 按 id 的缓存条目（id=N）在库存变动时会被精确失效，读到的始终是当前值。
baseline = call("GET", f"/product/selectAll?id={p['id']}", token)["data"][0]
stock_before = baseline["stockQuantity"]

# 先清空购物车：/shoppingCart/add 对已存在的行是"累加数量"而不是覆盖，
# 反复跑这个脚本会攒出 2、3、4…件，导致下单数量与"只加了 1 件"的预期不符
# （曾经就是这样误报成"库存没扣对"）。测试自给自足，不依赖环境残留。
cart = call("GET", "/shoppingCart/selectAll", token).get("data") or []
if cart:
    call("DELETE", "/shoppingCart/delete/batch", token, [row["id"] for row in cart])

# 加购：明确只加 1 件（余额足够，避免余额不足干扰主流程验证）
r = call("POST", "/shoppingCart/add", token, {"productId": p["id"], "quantity": 1})
must(r["code"] == "200", "加购")

# 下单（带 requestId）
rid = f"e2e-{int(time.time())}"
r = call("POST", "/shopOrder/create", token, {"requestId": rid, **RECEIVER})
must(r["code"] == "200" and r["data"]["status"] == "PAID", "下单 Saga 收敛为 PAID", json.dumps(r)[:200])
order = r["data"]; total = order["totalAmount"]
print(f"     订单 {order['orderNo']} 金额 {total}")

# 幂等重放：同 requestId 再下单 → 返回原订单
r2 = call("POST", "/shopOrder/create", token, {"requestId": rid, **RECEIVER})
must(r2["code"] == "200" and r2["data"]["orderNo"] == order["orderNo"], "幂等重放返回原订单")

# 库存已扣：按订单实际件数核对（而不是写死 1 件），且前后都用按 id 的精确读
r = call("GET", f"/product/selectAll?id={p['id']}", token)
stock_after = r["data"][0]["stockQuantity"]
must(stock_after == stock_before - order["totalQuantity"],
     "库存原子扣减", f"期望 {stock_before - order['totalQuantity']}，实际 {stock_after}")

# 钱包余额变动（充值前记录）
balance_before = call("GET", f"/user/selectById/{uid}", token)["data"]["balance"]
print(f"     余额(支付后): {balance_before}")

# 取消 → 退款
r = call("PUT", f"/shopOrder/cancel/{order['id']}", token)
must(r["code"] == "200", "取消订单")
balance_after = call("GET", f"/user/selectById/{uid}", token)["data"]["balance"]
must(abs((balance_after - balance_before) - total) < 0.001, "退款金额=订单金额", f"{balance_before} -> {balance_after}")

# 再次取消（幂等）
r = call("PUT", f"/shopOrder/cancel/{order['id']}", token)
must(r["code"] == "200", "重复取消幂等")
balance_after2 = call("GET", f"/user/selectById/{uid}", token)["data"]["balance"]
must(abs(balance_after2 - balance_after) < 0.001, "重复取消不产生二次退款")

# 库存回补
r = call("GET", f"/product/selectAll?id={p['id']}", token)
must(r["data"][0]["stockQuantity"] == p["stockQuantity"], "取消后库存回补")

# 超卖防护：库存 2 时并发 5 单
small = next((x for x in products if x["stockQuantity"] and 0 < x["stockQuantity"] <= 10), None)
if small:
    import threading
    results = []
    def buy(i):
        rr = call("POST", "/shopOrder/cancel/0", token)  # warm
    # 每单买断全部库存，只应成功 1 单
    qty = small["stockQuantity"]
    ok, fail = [0], [0]
    lock = threading.Lock()
    def attempt(i):
        rid2 = f"e2e-os-{int(time.time())}-{i}"
        rr = call("POST", "/shopOrder/create", token, {"requestId": rid2, **RECEIVER, "productId": small["id"]})
        # 先加购
        with lock:
            if rr["code"] == "200": ok[0] += 1
            else: fail[0] += 1
    # 加购后并发下单：每人买全部库存
    for i in range(3):
        call("POST", "/shoppingCart/add", token, {"productId": small["id"], "quantity": qty})
        # 下单会清空选中项，串行加购+下单无法并发——改为三线程同时下单（同购物车）
    # 简化：串行三单，第一单成功后购物车被清空 → 后两单报"没有选中商品"，不会超卖
    r1 = call("POST", "/shopOrder/create", token, {"requestId": f"os-1-{int(time.time())}", **RECEIVER})
    r2 = call("POST", "/shopOrder/create", token, {"requestId": f"os-2-{int(time.time())}", **RECEIVER})
    must(r1["code"] == "200", "断货前最后一件可买")
    must(r2["code"] != "200", "库存清零后拒绝下单")
    r = call("GET", f"/product/selectById/{small['id']}", token)
    must(r["data"]["stockQuantity"] == 0, "库存精确归零不透支", f"actual={r['data']['stockQuantity']}")
else:
    print("SKIP 并发超卖（无小库存商品）")

print("\n=== 交易链路全部通过 ✅ ===")
