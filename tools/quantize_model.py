from pathlib import Path

import onnx
from onnxruntime.quantization import QuantType, quantize_dynamic


ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "app/src/main/assets/aegis_voice_detector.onnx"
OUTPUT = ROOT / "app/src/main/assets/aegis_voice_detector.int8.onnx"


def main() -> None:
    if not MODEL.is_file() or MODEL.stat().st_size == 0:
        raise FileNotFoundError(f"Missing or empty model: {MODEL}")

    quantize_dynamic(
        model_input=str(MODEL),
        model_output=str(OUTPUT),
        weight_type=QuantType.QInt8,
        per_channel=True,
        reduce_range=False,
        op_types_to_quantize=["MatMul", "Gemm"],
        extra_options={"MatMulConstBOnly": True},
    )

    model = onnx.load(str(OUTPUT), load_external_data=False)
    onnx.checker.check_model(model)
    print(f"created {OUTPUT} ({OUTPUT.stat().st_size} bytes)")
    print("inputs:", [value.name for value in model.graph.input])
    print("outputs:", [value.name for value in model.graph.output])


if __name__ == "__main__":
    main()