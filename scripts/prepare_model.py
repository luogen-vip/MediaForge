#!/usr/bin/env python3
"""
MediaForge 构建期模型准备脚本（在 GitHub Actions 上运行）：

1. 下载 LaMa 图像修复模型（big-lama 的 ONNX 导出，Apache-2.0 开源）
   -> app/src/main/assets/inpaint_model.onnx（去水印用）
2. 下载 U2Net 显著性抠图模型（Apache-2.0 开源）
   -> app/src/main/assets/matting_model.onnx（抠图用）
3. 两个模型都做动态 int8 量化（更快、更小），并用真实推理验证可用性
4. 验证通过 -> 打包量化版；失败 -> 打包 fp32 原版（过大则降级备选模型）；
   下载也失败 -> 不打包（APP 自动用内置算法兜底，功能永不失败）
"""
import os
import shutil
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets")
LAMA_OUT = os.path.join(ASSETS, "inpaint_model.onnx")
MATTING_OUT = os.path.join(ASSETS, "matting_model.onnx")

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
    """优先 u2net（效果最强，量化后约 44MB）；不行降级 u2netp（轻量，约 1.5MB）"""
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
    print("MATTING DOWNLOAD/VERIFY ALL FAILED -> no matting model, app will use built-in algorithm")
    if os.path.exists(MATTING_OUT):
        os.remove(MATTING_OUT)
    return 0


def main() -> int:
    os.makedirs(ASSETS, exist_ok=True)
    rc1 = build_lama()
    rc2 = build_matting()
    return 0 if rc1 == 0 and rc2 == 0 else max(rc1, rc2)


if __name__ == "__main__":
    sys.exit(main())
