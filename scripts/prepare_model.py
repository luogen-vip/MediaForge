#!/usr/bin/env python3
"""
MediaForge 构建期模型准备脚本（在 GitHub Actions 上运行）：

1. 下载 LaMa 图像修复模型（big-lama 的 ONNX 导出，Apache-2.0 开源）
2. 动态 int8 量化（约 208MB -> 约 52MB），手机端更快、内存占用更低
3. 用真实推理验证量化模型可用（形状/数值范围/有限性）
4. 验证通过 → 打包量化版；失败 → 打包 fp32 原版；下载也失败 → 不打包（APP 自动用内置算法兜底）

产物：app/src/main/assets/inpaint_model.onnx
"""
import os
import shutil
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets")
OUT = os.path.join(ASSETS, "inpaint_model.onnx")

URLS = [
    "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx",
    "https://huggingface.co/sapienkit/LaMa-ONNX/resolve/main/lama_fp32.onnx",
]

FP32 = "/tmp/lama_fp32.onnx"
INT8 = "/tmp/lama_int8.onnx"


def download() -> bool:
    if os.path.exists(FP32) and os.path.getsize(FP32) > 100_000_000:
        return True
    for url in URLS:
        try:
            print(f"downloading {url} ...", flush=True)
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=600) as resp, open(FP32, "wb") as f:
                total = 0
                while True:
                    chunk = resp.read(1 << 22)
                    if not chunk:
                        break
                    f.write(chunk)
                    total += len(chunk)
                    if total % (50 << 20) < (1 << 22):
                        print(f"  downloaded {total >> 20} MB", flush=True)
            if os.path.getsize(FP32) > 100_000_000:
                print(f"downloaded {os.path.getsize(FP32)} bytes")
                return True
        except Exception as e:
            print(f"  failed: {e}", flush=True)
    return False


def quantize() -> bool:
    try:
        from onnxruntime.quantization import QuantType, quantize_dynamic

        quantize_dynamic(FP32, INT8, op_types_to_quantize=["Conv", "MatMul"], weight_type=QuantType.QInt8)
        sz = os.path.getsize(INT8)
        print(f"quantized size: {sz >> 20} MB")
        return sz > 10_000_000 and sz < 120_000_000
    except Exception as e:
        print(f"quantize failed: {e}", flush=True)
        return False


def verify(path: str) -> bool:
    try:
        import numpy as np
        import onnxruntime as ort

        sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
        inputs = sess.get_inputs()
        names = [i.name for i in inputs]
        img_n = next(
            (i.name for i in inputs if len(i.shape) >= 4 and i.shape[1] == 3), names[0]
        )
        msk_n = next(
            (i.name for i in inputs if len(i.shape) >= 4 and i.shape[1] == 1), names[-1]
        )
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


def main() -> int:
    os.makedirs(ASSETS, exist_ok=True)
    if not download():
        print("MODEL DOWNLOAD FAILED -> bundle nothing, app will use built-in algorithm")
        if os.path.exists(OUT):
            os.remove(OUT)
        return 0
    chosen = None
    if quantize() and verify(INT8):
        chosen = INT8
    elif verify(FP32):
        chosen = FP32
    else:
        print("BOTH MODELS FAILED -> bundle nothing, app will use built-in algorithm")
        if os.path.exists(OUT):
            os.remove(OUT)
        return 0
    shutil.copyfile(chosen, OUT)
    print(f"BUNDLED {chosen} -> {OUT} ({os.path.getsize(OUT) >> 20} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
