"""
智谱 GLM-4.6V-Flash API 测试 — 提示词调试
目标：生成简短事项分类名称，如"房屋租赁"、"电费账单"、"学费缴纳"
"""
import requests
import base64
import json
import os
import time

API_KEY = "2eec02631a4c434abf0a8213f9d2d1fb.Eic19INvocAjDOUI"
CHAT_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
MODEL = "glm-4.6v-flash"

headers = {
    "Authorization": f"Bearer {API_KEY}",
    "Content-Type": "application/json",
}

# ── 核心提示词 ──
# 目标：把任意文件/图片归纳为一个 2-6 字的分类名称
SYSTEM_PROMPT = """你是一个证据文件归档助手。用户会提供一份文件或一张图片，你需要为它生成一个事项名称，用于归类保存。

要求：
1. 名称 2-6 个中文字符，简洁精炼
2. 名称应概括"这是什么类型的证据"，而非描述截图中的每一个细节
3. 命名风格参考：房屋租赁、电费账单、学费缴纳、劳动合同、购车发票、身份证、银行流水、物业费、维修收据、快递运单、保险保单、体检报告
4. 只回复名称本身，不要加任何前缀、后缀、标点或解释"""


def call_api(payload, label):
    print(f"\n{'=' * 60}")
    print(f"[TEST] {label}")
    print(f"{'=' * 60}")

    resp = requests.post(CHAT_URL, headers=headers, json=payload, timeout=60)
    data = resp.json()

    if resp.status_code == 200 and "choices" in data:
        choice = data["choices"][0]
        content = choice["message"].get("content", "").strip()
        finish = choice.get("finish_reason", "?")
        usage = data.get("usage", {})
        reasoning_tokens = usage.get("completion_tokens_details", {}).get("reasoning_tokens", 0)
        comp_tokens = usage.get("completion_tokens", 0)
        print(f"finish={finish}, tokens={comp_tokens}(reasoning={reasoning_tokens})")
        print(f">>> [{content}]")
        return content
    else:
        err = data.get("error", {}).get("message", str(data)[:200])
        print(f"ERROR: {err}")
        return ""


# ═══════════════════════════════════════════════════════════════
# 测试几个代表性场景
# ═══════════════════════════════════════════════════════════════

# 测试1：文本—租房合同
call_api({
    "model": MODEL,
    "messages": [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": "文件内容：房屋租赁合同，出租方张三，承租方李四，月租金3500元，租期2026年1月至2026年12月，押金一个月。"},
    ],
    "temperature": 0.3,
    "max_tokens": 4000,
}, "test1: 租房合同文本")
time.sleep(3)

# 测试2：文本—电费账单
call_api({
    "model": MODEL,
    "messages": [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": "文件名：2026年7月电费账单.pdf，内容显示7月用电量356度，金额156元。"},
    ],
    "temperature": 0.3,
    "max_tokens": 4000,
}, "test2: 电费账单文本")
time.sleep(3)

# 测试3：文本—购车发票
call_api({
    "model": MODEL,
    "messages": [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": "文件内容：机动车销售统一发票，购买方王五，车辆品牌丰田卡罗拉，价税合计12.8万元。"},
    ],
    "temperature": 0.3,
    "max_tokens": 4000,
}, "test3: 购车发票文本")
time.sleep(3)

# 测试4：文本—劳动合同
call_api({
    "model": MODEL,
    "messages": [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": "文件内容：劳动合同书，甲方某科技有限公司，乙方赵六，职位软件工程师，合同期限三年，月薪15000元。"},
    ],
    "temperature": 0.3,
    "max_tokens": 4000,
}, "test4: 劳动合同文本")
time.sleep(3)

# 测试5：图片—a.jpg（学费缴纳截图）
img_path = "a.jpg"
if os.path.isfile(img_path):
    with open(img_path, "rb") as f:
        img_data = f.read()
    ext = os.path.splitext(img_path)[1].lower().lstrip(".")
    mime_map = {"jpg": "jpeg", "jpeg": "jpeg", "png": "png"}
    mime = mime_map.get(ext, "jpeg")
    b64 = base64.b64encode(img_data).decode("utf-8")

    call_api({
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "请为这张图片内容生成事项名称。"},
                    {"type": "image_url", "image_url": {"url": f"data:image/{mime};base64,{b64}"}},
                ],
            },
        ],
        "temperature": 0.3,
        "max_tokens": 4000,
    }, "test5: 图片 a.jpg")
else:
    print(f"\n[SKIP] a.jpg not found")
