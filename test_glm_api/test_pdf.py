"""
测试智谱文件解析 API：处理 PDF（文本型+图片型）
方案：POST /paas/v4/files/parser/sync（Lite 免费版）
然后用解析结果调用 GLM-4.6V-Flash 生成事项名称
"""
import requests
import base64
import json
import os
import time

API_KEY = "2eec02631a4c434abf0a8213f9d2d1fb.Eic19INvocAjDOUI"
BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
PARSER_URL = f"{BASE_URL}/files/parser/sync"
CHAT_URL = f"{BASE_URL}/chat/completions"

MODEL = "glm-4.6v-flash"

headers = {"Authorization": f"Bearer {API_KEY}"}

SYSTEM_PROMPT = """你是一个证据文件归档助手。用户会提供一份文件的内容，你需要为它生成一个事项名称，用于归类保存。
要求：
1. 名称 2-6 个中文字符，简洁精炼
2. 名称应概括"这是什么类型的证据"
3. 命名风格参考：房屋租赁、电费账单、学费缴纳、劳动合同、购车发票、身份证、银行流水
4. 只回复名称本身，不要加任何前缀、后缀、标点或解释"""


def parse_file(filepath):
    """调用智谱文件解析同步 API（Lite 免费版）"""
    print(f"\n[PARSE] uploading: {filepath}")
    filename = os.path.basename(filepath)
    ext = filename.rsplit(".", 1)[-1].lower()

    with open(filepath, "rb") as f:
        files = {"file": (filename, f, "application/octet-stream")}
        data = {
            "tool_type": "lite-sync",
            "file_type": ext.upper() if ext in ("pdf", "doc", "docx", "ppt", "pptx") else "PDF",
        }
        resp = requests.post(PARSER_URL, headers=headers, files=files, data=data, timeout=120)

    result = resp.json()
    print(f"status: {resp.status_code}")
    print(f"response keys: {list(result.keys())}")

    if resp.status_code == 200:
        content = result.get("data", {}).get("content", "")
        title = result.get("data", {}).get("title", "")
        file_type = result.get("data", {}).get("file_type", "")
        pages = result.get("data", {}).get("pages", 0)
        print(f"title: {title}, pages: {pages}, file_type: {file_type}")
        print(f"content ({len(content)} chars): {content[:500]}...")
        return content
    else:
        print(f"ERROR: {json.dumps(result, ensure_ascii=False)[:500]}")
        return ""


def generate_name(content):
    """用解析出的内容调用 LLM 生成事项名称"""
    if not content or len(content.strip()) < 5:
        print("content too short, skip")
        return ""

    # 截取前 2000 字符避免 token 浪费
    truncated = content[:2000]

    print(f"\n[NAME] generating name from {len(truncated)} chars of content...")
    resp = requests.post(CHAT_URL, headers={**headers, "Content-Type": "application/json"}, json={
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": f"文件内容：\n{truncated}"},
        ],
        "temperature": 0.3,
        "max_tokens": 4000,
    }, timeout=60)

    data = resp.json()
    if resp.status_code == 200:
        content = data["choices"][0]["message"]["content"].strip()
        usage = data.get("usage", {})
        reasoning = usage.get("completion_tokens_details", {}).get("reasoning_tokens", 0)
        comp = usage.get("completion_tokens", 0)
        print(f"tokens={comp}(reasoning={reasoning})")
        print(f">>> [{content}]")
        return content
    else:
        print(f"ERROR: {json.dumps(data, ensure_ascii=False)[:300]}")
        return ""


# ═══════════════════════════════════════════════════════════
# 测试
# ═══════════════════════════════════════════════════════════

# 测试1：用 a.jpg 直接走 vision（现有方案对比）
print("=" * 60)
print("TEST A: 图片 → vision 直接命名")
print("=" * 60)
img_path = "a.jpg"
if os.path.isfile(img_path):
    with open(img_path, "rb") as f:
        img_data = f.read()
    mime = "jpeg"
    b64 = base64.b64encode(img_data).decode("utf-8")
    resp = requests.post(CHAT_URL, headers={**headers, "Content-Type": "application/json"}, json={
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": [
                {"type": "text", "text": "请为这张图片内容生成事项名称。"},
                {"type": "image_url", "image_url": {"url": f"data:image/{mime};base64,{b64}"}},
            ]},
        ],
        "temperature": 0.3,
        "max_tokens": 4000,
    }, timeout=60)
    data = resp.json()
    name = data["choices"][0]["message"]["content"].strip()
    usage = data.get("usage", {})
    print(f"tokens={usage.get('completion_tokens')}(reasoning={usage.get('completion_tokens_details',{}).get('reasoning_tokens')})")
    print(f">>> [{name}]")

time.sleep(3)

# 测试2：用 file parser 解析图片 → 再命名
print("\n" + "=" * 60)
print("TEST B: 图片 → parser 解析 → 命名")
print("=" * 60)
if os.path.isfile(img_path):
    content = parse_file(img_path)
    if content:
        generate_name(content)

time.sleep(3)

# 测试3：如果你有一个测试 PDF，解析它
# （创建一个简单的测试 PDF，或者直接测试 parser 对图片的解析效果）
print("\n" + "=" * 60)
print("TEST C: 纯文本（模拟 PDF 解析结果）→ 命名")
print("=" * 60)
# 模拟一个文本型 PDF 解析出的内容
mock_content = """房屋租赁合同

甲方（出租方）：张三
乙方（承租方）：李四

租赁房屋地址：北京市朝阳区XX路XX号XX室
月租金：人民币3500元整
押金：人民币3500元整（一个月租金）
租赁期限：2026年1月1日至2026年12月31日
付款方式：每季度首月5日前支付当季租金"""
generate_name(mock_content)
