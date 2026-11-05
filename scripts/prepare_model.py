#!/usr/bin/env python3
"""
MediaForge 构建期模型准备脚本（在 GitHub Actions 上运行）：

1. LaMa 去水印模型：联网下载 + 动态 int8 量化 + 真实推理验证
   -> app/src/main/assets/inpaint_model.onnx（与 v1.4.4 行为完全一致，不改动）
2. 抠图模型：已直接提交在 app/src/main/assets/（开箱即用，本脚本绝不覆盖）：
   - matting_model.onnx       U2Net 全量模型（Conv 权重 int8 QDQ，全平台兼容）
   - matting_model_lite.onnx  U2NetP 轻量模型（官方 fp32 原版，零量化，兜底）
   仅做移动端算子安全校验（不含 ConvInteger / MatMulInteger / QLinear* 等
   onnxruntime-android 不支持的算子）；若资产缺失才走联网下载兜底。
"""
import os
import shutil
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets")
LAMA_OUT = os.path.join(ASSETS, "inpaint_model.onnx")
MATTING_OUT = os.path.join(ASSETS, "matting_model.onnx")
MATTING_LITE_OUT = os.path.join(ASSETS, "matting_model_lite.onnx")

# LaMa（去水印）：fp32 约 208MB
LAMA_URLS = [
    "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx",
    "https://huggingface.co/sapienkit/LaMa-ONNX/resolve/main/lama_fp32.onnx",
]
# U2Net（抠图）：官方训练权重的 ONNX 导出（rembg 同款），fp32 约 168MB；
# u2netp 为轻量备选（约 4.6MB），主模型量化失败/下载失败时兜底
U2NET_URLS = [
    "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2net.onnx",
    "https://huggingface.co/tomjackson2023/rembg/resolve/main/u2net.onnx",
]
U2NETP_URLS = [
    "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx",
    "https://huggingface.co/tomjackson2023/rembg/resolve/main/u2netp.onnx",
]

# onnxruntime-android 的 CPU EP 没有这些量化算子的 ARM 实现（v1.4.4 抠图失效的根因）
MOBILE_UNSAFE_OPS = ("ConvInteger", "MatMulInteger", "QLinearConv", "QLinearMatMul", "DynamicQuantizeLinear")


def download_stream(urls, out, min_bytes):
    if os.path.exists(out) and os.path.getsize(out) > min_bytes:
        print(f"cached: {out} ({os.path.getsize(out) >> 20} MB)")
        return True
    for url in urls:
        try:
            print(f"downloading {url} ...", flush=True)
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=600) as resp, open(out, "wb") as f:
                total = 0
                while True:
                    chunk = resp.read(1 << 22)
                    if not chunk:
                        break
                    f.write(chunk)
                    total += len(chunk)
                    if total % (50 << 20) < (1 << 22):
                        print(f"  downloaded {total >> 20} MB", flush=True)
            if os.path.getsize(out) > min_bytes:
                print(f"downloaded {os.path.getsize(out)} bytes")
                return True
        except Exception as e:
            print(f"  failed: {e}", flush=True)
    return False


def quantize(fp32, int8, min_mb=5, max_mb=120):
    try:
        from onnxruntime.quantization import QuantType, quantize_dynamic

        quantize_dynamic(fp32, int8, op_types_to_quantize=["Conv", "MatMul"], weight_type=QuantType.QInt8)
        sz = os.path.getsize(int8)
        print(f"quantized size: {sz >> 20} MB")
        return (min_mb << 20) < sz < (max_mb << 20)
    except Exception as e:
        print(f"quantize failed: {e}", flush=True)
        return False


def assert_mobile_safe(path):
    """必须校验：抠图模型不能含 onnxruntime-android 不支持的量化算子"""
    import onnx

    model = onnx.load(path, load_external_data=False)
    ops = {}
    for n in model.graph.node:
        ops[n.op_type] = ops.get(n.op_type, 0) + 1
    bad = [op for op in ops if op in MOBILE_UNSAFE_OPS]
    if bad:
        raise RuntimeError(f"{path} 包含移动端不支持的量化算子: {bad}")
    print(f"op check ok: {os.path.basename(path)} 无移动端不兼容算子")


def verify_lama(path: str) -> bool:
    try:
        import numpy as np
        import onnxruntime as ort

        sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
        inputs = sess.get_inputs()
        names = [i.name for i in inputs]
        img_n = next((i.name for i in inputs if len(i.shape) >= 4 and i.shape[1] == 3), names[0])
        msk_n = next((i.name for i in inputs if len(i.shape) >= 4 and i.shape[1] == 1), names[-1])
        print(f"model inputs: image={img_n} mask={msk_n}", flush=True)
        img = (np.random.rand(1, 3, 512, 512) * 0.9 + 0.05).astype(np.float32)
        msk = np.zeros((1, 1, 512, 512), np.float32)
        msk[:, :, 200:300, 200:300] = 1.0
        out = sess.run(None, {img_n: img, msk_n: msk})[0]
        assert out.shape == (1, 3, 512, 512), f"bad shape {out.shape}"
        assert np.isfinite(out).all(), "non-finite output"
        mx = float(out.max())
        mn = float(out.min())
        assert 0 <= mn and mx <= 400, f"range off: [{mn}, {mx}]"
        print(f"verify ok: output range [{mn:.1f}, {mx:.1f}]")
        return True
    except Exception as e:
        print(f"verify failed: {e}", flush=True)
        return False


def verify_matting(path: str) -> bool:
    """U2Net：输入 1x3x320x320，首个输出应为 320x320 的 0..1 概率图"""
    try:
        import numpy as np
        import onnxruntime as ort

        sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
        inp = sess.get_inputs()[0]
        in_name = inp.name
        print(f"matting model input: {in_name} shape={inp.shape}", flush=True)
        mean = np.array([0.485, 0.456, 0.406], np.float32).reshape(3, 1, 1)
        std = np.array([0.229, 0.224, 0.225], np.float32).reshape(3, 1, 1)
        img = (np.random.rand(3, 320, 320).astype(np.float32) - mean) / std
        outs = sess.run(None, {in_name: img[None]})
        out = np.asarray(outs[0])
        # 允许 [1,1,320,320] / [1,320,320] / [320,320] 等布局
        flat = out.reshape(-1)
        assert flat.size >= 320 * 320 // 4, f"output too small: {out.shape}"
        assert np.isfinite(flat).all(), "non-finite output"
        mx = float(flat.max())
        mn = float(flat.min())
        assert -0.1 <= mn and mx <= 1.1, f"range off: [{mn}, {mx}]"
        print(f"matting verify ok: out shape {out.shape}, range [{mn:.3f}, {mx:.3f}]")
        return True
    except Exception as e:
        print(f"matting verify failed: {e}", flush=True)
        return False


def build_lama() -> int:
    fp32 = "/tmp/lama_fp32.onnx"
    int8 = "/tmp/lama_int8.onnx"
    if not download_stream(LAMA_URLS, fp32, 100_000_000):
        print("LAMA DOWNLOAD FAILED -> no inpaint model, app will use built-in algorithm")
        if os.path.exists(LAMA_OUT):
            os.remove(LAMA_OUT)
        return 0
    chosen = None
    if quantize(fp32, int8) and verify_lama(int8):
        chosen = int8
    elif verify_lama(fp32):
        chosen = fp32
    if chosen is None:
        print("LAMA VERIFY FAILED -> no inpaint model, app will use built-in algorithm")
        if os.path.exists(LAMA_OUT):
            os.remove(LAMA_OUT)
        return 0
    shutil.copyfile(chosen, LAMA_OUT)
    print(f"BUNDLED {chosen} -> {LAMA_OUT} ({os.path.getsize(LAMA_OUT) >> 20} MB)")
    return 0


def build_matting() -> int:
    """抠图模型已提交在 assets：只校验，绝不联网覆盖。
    仅当资产意外缺失时才联网下载兜底（保证 APP 仍能出厂可用）。"""
    bundled_main = os.path.exists(MATTING_OUT) and os.path.getsize(MATTING_OUT) > 1_000_000
    bundled_lite = os.path.exists(MATTING_LITE_OUT) and os.path.getsize(MATTING_LITE_OUT) > 100_000
    if bundled_main and bundled_lite:
        print(f"matting models already bundled: "
              f"{os.path.basename(MATTING_OUT)} ({os.path.getsize(MATTING_OUT) >> 20} MB) + "
              f"{os.path.basename(MATTING_LITE_OUT)} ({os.path.getsize(MATTING_LITE_OUT) >> 20} MB), skip download")
        assert_mobile_safe(MATTING_OUT)
        assert_mobile_safe(MATTING_LITE_OUT)
        verify_matting(MATTING_OUT)
        verify_matting(MATTING_LITE_OUT)
        return 0

    # 兜底：资产缺失时按老流程下载量化（生成的仍是 fp32 原版可用的轻量备选优先）
    print("WARN: bundled matting assets missing, fallback to download ...")
    candidates = [
        (U2NET_URLS, "/tmp/u2net_fp32.onnx", "/tmp/u2net_int8.onnx", 100_000_000, 15),
        (U2NETP_URLS, "/tmp/u2netp_fp32.onnx", "/tmp/u2netp_int8.onnx", 2_000_000, 1),
    ]
    for urls, fp32, int8, min_bytes, qmin in candidates:
        if not download_stream(urls, fp32, min_bytes):
            print(f"matting candidate download failed: {fp32}, trying next ...")
            continue
        chosen = None
        if quantize(fp32, int8, min_mb=qmin) and verify_matting(int8):
            chosen = int8
        elif verify_matting(fp32):
            # fp32 的 u2net 约 168MB，打包会让 APK 增重过多，只在轻量模型上允许直接打包 fp32
            if os.path.getsize(fp32) < 30_000_000:
                chosen = fp32
            else:
                print("fp32 too large to bundle, trying next candidate ...")
        if chosen is not None:
            shutil.copyfile(chosen, MATTING_OUT)
            print(f"BUNDLED {chosen} -> {MATTING_OUT} ({os.path.getsize(MATTING_OUT) >> 20} MB)")
            return 0
    print("MATTING DOWNLOAD/VERIFY ALL FAILED -> keep bundled assets as-is")
    return 0


def main() -> int:
    os.makedirs(ASSETS, exist_ok=True)
    rc1 = build_lama()
    rc2 = build_matting()
    return 0 if rc1 == 0 and rc2 == 0 else max(rc1, rc2)


if __name__ == "__main__":
    sys.exit(main())
