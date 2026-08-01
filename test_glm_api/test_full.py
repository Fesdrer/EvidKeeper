"""
EvidKeeper AI 命名完整流程测试
图片：base64 → vision
PDF：上传 file-extract → 取文本 → 文本
TXT：直接读取 → 文本
"""
import requests
import base64
import json
import os
import time

API_KEY = "2eec02631a4c434abf0a8213f9d2d1fb.Eic19INvocAjDOUI"
BASE = "https://open.bigmodel.cn/api/paas/v4"
CHAT_URL = f"{BASE}/chat/completions"
FILES_URL = f"{BASE}/files"

MODEL_VISION = "glm-4.6v"       # 付费版 vision（图片）
MODEL_TEXT = "glm-4.6v"         # 付费版文本

HEADERS = {"Authorization": f"Bearer {API_KEY}"}

SYSTEM_PROMPT = """你是一个证据文件归档助手。用户会提供一份文件的内容，你需要为它生成一个事项名称，用于归类保存。
要求：
1. 名称 2-6 个中文字符，简洁精炼
2. 名称应概括"这是什么类型的证据"
3. 命名风格参考：房屋租赁、电费账单、学费缴纳、劳动合同、购车发票、身份证、银行流水、物业费、维修收据
4. 只回复名称本身，不要加任何前缀、后缀、标点或解释"""


def chat_text(text, label=""):
    """文本 → 名称"""
    print(f"\n[NAME:{label}] text len={len(text)}")
    truncated = text[:2000]
    resp = requests.post(CHAT_URL, json={
        "model": MODEL_TEXT,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": f"文件内容：\n{truncated}"},
        ],
        "temperature": 0.3,
        "max_tokens": 4000,
    }, headers={**HEADERS, "Content-Type": "application/json"}, timeout=60)
    data = resp.json()
    if resp.status_code == 200:
        name = data["choices"][0]["message"]["content"].strip()
        usage = data.get("usage", {})
        print(f"tokens={usage.get('completion_tokens')} >>> [{name}]")
        return name
    else:
        print(f"ERROR: {json.dumps(data, ensure_ascii=False)[:300]}")
        return ""


def chat_vision(filepath):
    """图片 base64 → vision → 名称"""
    print(f"\n[VISION] {filepath}")
    with open(filepath, "rb") as f:
        img_data = f.read()
    ext = os.path.splitext(filepath)[1].lower().lstrip(".")
    mime = {"jpg": "jpeg", "jpeg": "jpeg", "png": "png"}.get(ext, "jpeg")
    b64 = base64.b64encode(img_data).decode("utf-8")

    resp = requests.post(CHAT_URL, json={
        "model": MODEL_VISION,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": [
                {"type": "text", "text": "请为这张图片内容生成事项名称。"},
                {"type": "image_url", "image_url": {"url": f"data:image/{mime};base64,{b64}"}},
            ]},
        ],
        "temperature": 0.3,
        "max_tokens": 4000,
    }, headers={**HEADERS, "Content-Type": "application/json"}, timeout=60)
    data = resp.json()
    if resp.status_code == 200:
        name = data["choices"][0]["message"]["content"].strip()
        usage = data.get("usage", {})
        print(f"tokens={usage.get('completion_tokens')} >>> [{name}]")
        return name
    else:
        print(f"ERROR: {json.dumps(data, ensure_ascii=False)[:300]}")
        return ""


def pdf_through_extract(filepath):
    """PDF → 上传 file-extract → 取文本 → 文本 API → 名称"""
    print(f"\n[PDF-EXTRACT] {filepath}")

    # Step 1: 上传文件
    filename = os.path.basename(filepath)
    with open(filepath, "rb") as f:
        resp = requests.post(FILES_URL,
            headers=HEADERS,
            files={"file": (filename, f, "application/pdf")},
            data={"purpose": "file-extract"},
            timeout=60)
    if resp.status_code != 200:
        print(f"upload FAILED: {resp.status_code} {resp.text[:300]}")
        return ""
    file_id = resp.json()["id"]
    print(f"uploaded: {file_id}")

    # Step 2: 获取提取内容
    time.sleep(1)  # 等一下处理
    r2 = requests.get(f"{FILES_URL}/{file_id}/content", headers=HEADERS, timeout=60)
    if r2.status_code != 200:
        print(f"content FAILED: {r2.status_code} {r2.text[:300]}")
        return ""
    content = r2.json().get("content", "")
    print(f"extracted: {len(content)} chars")
    print(f"preview: {content[:300]}...")

    # Step 3: 生成名称
    return chat_text(content, os.path.basename(filepath))


# ═══════════════════════════════════════════════════════════

time.sleep(2)
print("=" * 60)
print("TEST 1: 图片 → vision 直接命名")
chat_vision("a.jpg")

time.sleep(3)
print("\n" + "=" * 60)
print("TEST 2: 图片 → file-extract → 文本 → 命名")
with open("a.jpg", "rb") as f:
    img_data = f.read()
# 上传图片做 file-extract
resp = requests.post(FILES_URL,
    headers=HEADERS,
    files={"file": ("a.jpg", img_data, "image/jpeg")},
    data={"purpose": "file-extract"},
    timeout=60)
if resp.status_code == 200:
    fid = resp.json()["id"]
    print(f"uploaded: {fid}")
    time.sleep(1)
    r2 = requests.get(f"{FILES_URL}/{fid}/content", headers=HEADERS, timeout=60)
    if r2.status_code == 200:
        content = r2.json().get("content", "")
        print(f"extracted: {len(content)} chars")
        chat_text(content, "a.jpg via extract")

time.sleep(3)
print("\n" + "=" * 60)
print("TEST 3: 模拟 PDF 文本 → 命名")
# 模拟一个文本型 PDF 提取结果
chat_text("""房屋租赁合同

甲方（出租方）：张三
乙方（承租方）：李四
租赁房屋地址：北京市朝阳区XX路XX号XX室
月租金：人民币3500元整
租赁期限：2026年1月1日至2026年12月31日
付款方式：每季度首月5日前支付当季租金""", "租房合同PDF")

time.sleep(3)
print("\n" + "=" * 60)
print("TEST 4: 模拟乱码文件名 PDF → 命名")
chat_text("""机动车销售统一发票
发票代码：1234567890
购买方名称：王五
车辆类型：丰田卡罗拉
价税合计：128000元
开票日期：2026年7月15日""", "乱码文件名PDF")

time.sleep(3)
print("\n" + "=" * 60)
print("TEST 5: 模拟扫描件 PDF（图片型）→ 命名")
# file-extract 对图片型 PDF 做 OCR 后返回的文本
chat_text("""校园统一支付平台
学费缴费
学号：2026202321
姓名：赵六
金额：5000.00元
支付时间：2026-07-30 20:32:54
支付状态：支付成功""", "扫描件PDF_OCR结果")
