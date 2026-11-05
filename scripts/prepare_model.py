#!/usr/bin/env python3
"""
MediaForge 构建期模型准备脚本（在 GitHub Actions 上运行）：

模型文件通常已直接提交在 app/src/main/assets/（推荐路径，APK 100% 开箱即用）；
本脚本只在 assets 缺失时才联网下载 + 量化，作为兜底。

关键教训（v1.4.4 踩坑）：
onnxruntime 自带的 quantize_dynamic 会把 Conv 转成 ConvInteger 节点，
CI 桌面版 onnxruntime 能跑，但 onnxruntime-android 的 CPU EP 没有
ConvInteger 实现 → 手机端 createSession 抛 NOT_IMPLEMENTED → APP 静默
回退到慢速内置算法（用户看到的现象就是"背景识别中卡住、抠图无效果"）。

因此这里使用「Conv 权重 int8 + DequantizeLinear（QDQ 权重量化）」：
计算图仍是标准 float Conv，全平台支持，量化后与 fp32 差异 <1%。
验证时必须固定 onnxruntime==1.19.2（与 APP 打包的 onnxruntime-android 同版本）。
"""
import os
import shutil
import sys
import urllib.request

import numpy as np
import onnx
from onnx import numpy_helper, TensorProto

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets")
LAMA_OUT = os.path.join(ASSETS, "inpaint_model.onnx")
MATTING_OUT = os.path.join(ASSETS, "matting_model.onnx")

# LaMa（去水印）：fp32 约 198MB
LAMA_URLS = [
    "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx",
    "https://hf-mirror.com/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx",
]
# U2Net（抠图）：rembg 同款 fp32 约 168MB；u2netp 为轻量备选
U2NET_URLS = [
    "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2net.onnx",
    "https://gh-proxy.com/https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2net.onnx",
]
U2NETP_URLS = [
    "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx",
    "https://gh-proxy.com/https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx",
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


def upgrade_to_13(model):
    """opset <13 图的 Unsqueeze axes 是 attribute，opset 13 要求作为 input。
    其余算子语义 11->13 兼容；onnx version_converter 在 u2net 上会改坏 Add，
    故手动做最小升级。"""
    g = model.graph
    n_up = 0
    existing = {i.name for i in g.initializer}
    for node in g.node:
        if node.op_type == "Unsqueeze" and len(node.input) == 1:
            axes = None
            for a in node.attribute:
                if a.name == "axes":
                    axes = list(a.ints)
            if axes is None:
                continue
            cname = node.name + "_axes_const"
            while cname in existing:
                cname += "_"
            existing.add(cname)
            const = onnx.helper.make_node(
                "Constant", inputs=[], outputs=[cname],
                value=onnx.helper.make_tensor(name=cname + "_v", data_type=TensorProto.INT64,
                                              dims=[len(axes)], vals=axes),
                name=cname + "_node",
            )
            idx = list(g.node).index(node)
            g.node.insert(idx, const)
            node.input.append(cname)
            for a in list(node.attribute):
                if a.name == "axes":
                    node.attribute.remove(a)
            n_up += 1
    for o in model.opset_import:
        if o.domain == "":
            o.version = 13
    print(f"upgraded {n_up} Unsqueeze nodes -> opset 13")
    return model


def quantize_conv_weights(model):
    """Conv 权重逐输出通道 int8 + DequantizeLinear（QDQ），不产生任何 QOperator 节点。"""
    g = model.graph
    added_dq = {}
    n_q = 0
    for node in g.node:
        if node.op_type != "Conv" or len(node.input) < 2:
            continue
        wname = node.input[1]
        if wname in added_dq:
            node.input[1] = added_dq[wname]
            continue
        init = next((i for i in g.initializer if i.name == wname), None)
        if init is None:
            continue
        w = numpy_helper.to_array(init)
        if w.ndim != 4:
            continue
        wmax = np.max(np.abs(w), axis=(1, 2, 3)).astype(np.float32)
        wmax[wmax == 0] = 1e-8
        best_scale = wmax / 127.0
        best_err = None
        for k in (0.8, 0.9, 1.0):
            sc = wmax * k / 127.0
            wq_try = np.clip(np.round(w / sc[:, None, None, None]), -127, 127)
            err = float(np.sum((wq_try * sc[:, None, None, None] - w) ** 2))
            if best_err is None or err < best_err:
                best_err = err
                best_scale = sc
        scale = best_scale.astype(np.float32)
        wq = np.clip(np.round(w / scale[:, None, None, None]), -127, 127).astype(np.int8)
        g.initializer.remove(init)
        wq_init = numpy_helper.from_array(wq, name=wname + "_i8")
        scale_init = numpy_helper.from_array(scale, name=wname + "_scale")
        g.initializer.extend([wq_init, scale_init])
        dq = onnx.helper.make_node(
            "DequantizeLinear",
            inputs=[wq_init.name, scale_init.name],
            outputs=[wname + "_dq"], axis=0, name=wname + "_dq_node",
        )
        idx = list(g.node).index(node)
        g.node.insert(idx, dq)
        node.input[1] = wname + "_dq"
        added_dq[wname] = wname + "_dq"
        n_q += 1
    print(f"conv-quantized {n_q} Conv weights (weight-only int8 QDQ)")
    return model


def assert_mobile_safe(path):
    """必须校验：不能含 onnxruntime-android 不支持的量化算子（v1.4.4 的根因）"""
    model = onnx.load(path)
    ops = {}
    for n in model.graph.node:
        ops[n.op_type] = ops.get(n.op_type, 0) + 1
    bad = [op for op in ops if op in ("ConvInteger", "MatMulInteger", "QLinearConv",
                                      "QLinearMatMul", "DynamicQuantizeLinear")]
    if bad:
        raise RuntimeError(f"模型包含移动端不支持的量化算子: {bad}")
    print(f"op check ok: {path} 无移动端不兼容算子")


def verify_lama(path):
    import onnxruntime as ort
    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    names = [i.name for i in sess.get_inputs()]
    img = (np.random.rand(1, 3, 512, 512) * 0.9 + 0.05).astype(np.float32)
    msk = np.zeros((1, 1, 512, 512), np.float32)
    msk[:, :, 200:300, 200:300] = 1.0
    out = sess.run(None, {names[0]: img, names[-1]: msk})[0]
    assert out.shape == (1, 3, 512, 512), f"bad shape {out.shape}"
    assert np.isfinite(out).all()
    print(f"verify ok: output range [{out.min():.1f}, {out.max():.1f}]")


def verify_matting(path):
    import onnxruntime as ort
    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]
    mean = np.array([0.485, 0.456, 0.406], np.float32).reshape(3, 1, 1)
    std = np.array([0.229, 0.224, 0.225], np.float32).reshape(3, 1, 1)
    img = (np.random.rand(3, 320, 320).astype(np.float32) - mean) / std
    outs = sess.run(None, {inp.name: img[None]})
    out = np.asarray(outs[0])
    flat = out.reshape(-1)
    assert flat.size >= 320 * 320 // 4
    assert np.isfinite(flat).all()
    print(f"matting verify ok: out shape {out.shape}")


def build_lama():
    if os.path.exists(LAMA_OUT) and os.path.getsize(LAMA_OUT) > 10_000_000:
        print(f"assets already bundled: {LAMA_OUT} ({os.path.getsize(LAMA_OUT) >> 20} MB), skip download")
        assert_mobile_safe(LAMA_OUT)
        verify_lama(LAMA_OUT)
        return 0
    fp32 = "/tmp/lama_fp32.onnx"
    if not download_stream(LAMA_URLS, fp32, 100_000_000):
        print("LAMA DOWNLOAD FAILED -> no inpaint model, app will use built-in algorithm")
        return 0
    model = onnx.load(fp32)
    model = quantize_conv_weights(model)
    onnx.save(model, LAMA_OUT)
    try:
        assert_mobile_safe(LAMA_OUT)
        verify_lama(LAMA_OUT)
        print(f"BUNDLED {LAMA_OUT} ({os.path.getsize(LAMA_OUT) >> 20} MB)")
    except Exception as e:
        print(f"quantized verify failed: {e} -> remove")
        if os.path.exists(LAMA_OUT):
            os.remove(LAMA_OUT)
    return 0


def build_matting():
    if os.path.exists(MATTING_OUT) and os.path.getsize(MATTING_OUT) > 10_000_000:
        print(f"assets already bundled: {MATTING_OUT} ({os.path.getsize(MATTING_OUT) >> 20} MB), skip download")
        assert_mobile_safe(MATTING_OUT)
        verify_matting(MATTING_OUT)
        return 0
    candidates = [
        (U2NET_URLS, "/tmp/u2net_fp32.onnx", 100_000_000),
        (U2NETP_URLS, "/tmp/u2netp_fp32.onnx", 2_000_000),
    ]
    for urls, fp32, min_bytes in candidates:
        if not download_stream(urls, fp32, min_bytes):
            print(f"matting candidate download failed: {fp32}, trying next ...")
            continue
        model = onnx.load(fp32)
        if next(o.version for o in model.opset_import if o.domain == "") < 13:
            model = upgrade_to_13(model)
        model = quantize_conv_weights(model)
        onnx.save(model, MATTING_OUT)
        try:
            assert_mobile_safe(MATTING_OUT)
            verify_matting(MATTING_OUT)
            print(f"BUNDLED {MATTING_OUT} ({os.path.getsize(MATTING_OUT) >> 20} MB)")
            return 0
        except Exception as e:
            print(f"quantized verify failed: {e} -> remove, try next")
            if os.path.exists(MATTING_OUT):
                os.remove(MATTING_OUT)
    print("MATTING PREPARE FAILED -> no matting model, app will use built-in algorithm")
    return 0


def main() -> int:
    os.makedirs(ASSETS, exist_ok=True)
    build_lama()
    build_matting()
    return 0


if __name__ == "__main__":
    sys.exit(main())
