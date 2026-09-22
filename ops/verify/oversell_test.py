"""并发防超卖：库存3，两用户并发各买2件 → 恰好一单成功，库存精确归零"""
import json, urllib.request, threading, time, sys

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
        return {"http": e.code, "body": e.read().decode()[:150]}

def must(cond, label, detail=""):
    print(("PASS " if cond else "FAIL ") + label + (f" | {detail}" if not cond else ""))
    if not cond: sys.exit(1)

admin = call("POST", "/user/login", body={"username": "admin", "password": "admin"})["data"]["token"]

# 注册两个测试用户并充值
users = []
for name in ["race1", "race2"]:
    call("POST", "/user/register", body={"username": name, "password": name + "123"})
    t = call("POST", "/user/login", body={"username": name, "password": name + "123"})["data"]["token"]
    call("POST", "/wallet/recharge", t, {"amount": 100000, "remark": "并发测试"})
    users.append(t)

# 取一个便宜商品，admin 把库存改成 3
products = call("GET", "/product/selectAll?status=ON_SALE", admin)["data"]
p = min(products, key=lambda x: x["price"] if x["price"] else 9e9)
print(f"     目标商品: {p['name']} price={p['price']} → admin 设置库存 3")
p["stockQuantity"] = 3
r = call("PUT", "/product/update", admin, p)
must(r["code"] == "200", "admin 重置库存为 3")

# 两用户并发：各加购 2 件，同时下单
for t in users:
    call("POST", "/shoppingCart/add", t, {"productId": p["id"], "quantity": 2})

results = [None, None]
def buy(i):
    # 收件人字段走 DTO 校验，占位值（"1"）会被 400 挡在下单之前，用合法手机号
    results[i] = call("POST", "/shopOrder/create", users[i],
                      {"requestId": f"race-{i}-{int(time.time()*1000)}", "receiverName": "并发压测",
                       "receiverPhone": "18800009999", "receiverAddress": "并发压测地址"})

threads = [threading.Thread(target=buy, args=(i,)) for i in range(2)]
[t.start() for t in threads]
[t.join() for t in threads]

codes = [r0.get("code") for r0 in results]
ok = sum(1 for c in codes if c == "200")
must(ok == 1, f"并发 2 单只成功 1 单（库存3/需求4）", f"codes={codes} results={[str(r)[:120] for r in results]}")

# 库存精确 = 3-2 = 1
stock = call("GET", f"/product/selectAll?id={p['id']}", admin)["data"][0]["stockQuantity"]
must(stock == 1, "库存精确（3-2=1，无超卖无丢失）", f"actual={stock}")
print("\n=== 并发防超卖验证通过 ✅ ===")
