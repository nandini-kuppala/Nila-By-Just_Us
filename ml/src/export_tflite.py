"""
Export both heads to TFLite, and prove the exported graph still agrees with Keras.

Two variants are attempted per model:

  *_mel.tflite    takes a (1, 96, 64) log-mel patch. Always converts.
  *_wave.tflite   takes (1, 15600) raw PCM with the frontend baked in. Depends
                  on RFFT support in the converter; if it fails we say so and
                  ship the mel variant plus the Kotlin frontend.

The wave variant is worth trying for a specific reason: it makes it impossible
for the phone's mel implementation to drift from the training one, which is the
usual way a mobile audio model degrades after deployment without anyone noticing.

Dynamic-range quantisation is applied by default -- roughly 4x smaller, and the
weights land in int8 where the Hexagon NPU wants them, while activations stay
float so we avoid needing a representative dataset for full integer calibration.

The mel graph is exported with its **batch dimension pinned to one**, and the
export asserts that no tensor is left dynamic. That is what makes the NPU
reachable at all -- see [_pin_batch].
"""

import argparse
import json

import numpy as np
import tensorflow as tf

from config import MODELS, REPORTS, PATCH_FRAMES, N_MELS, PATCH_SAMPLES
from models import wrap_with_frontend
from audio import LogMelLayer
from models import PatchNorm


CUSTOM = {"PatchNorm": PatchNorm, "LogMelLayer": LogMelLayer}


def _pin_batch(model):
    """
    Rebuild the graph with batch size 1 instead of `None`.

    ### Why this exists

    A Keras `Input(shape=...)` leaves the batch dimension unspecified, and
    `from_keras_model` carries that straight into the flatbuffer: every tensor
    in the exported graph gets a shape signature beginning `-1`. TFLite copes
    -- it allocates for batch 1 and runs -- and so does XNNPACK, which is why
    nothing ever looked wrong.

    NNAPI does not. It only accepts statically shaped graphs, so the delegate
    fails to apply and the runtime falls back. On an iQOO that meant the cry
    detector never reached the Hexagon NPU at all:

        Attempting to use a delegate that only supports static-sized tensors
        with a graph that has dynamic-sized tensors (tensor#34 is dynamic)
        NNAPI unavailable (Error applying delegate); falling back
        GPU unavailable (unsupported on this device); falling back
        Created TensorFlow Lite XNNPACK delegate for CPU

    The detector runs on one patch at a time and always has, so the batch axis
    was never carrying anything. Pinning it costs nothing and is the whole fix.

    ### Why a clone rather than a concrete function

    The obvious route -- `tf.function(...).get_concrete_function(TensorSpec([1,
    ...]))` and `from_concrete_functions` -- produces a graph of the right
    *shape* and the wrong *weights*: 19 KB whether quantisation is on or off,
    and output that disagrees with Keras by 0.47 or comes back NaN. The
    variables are not captured. Cloning the functional model onto a
    batch-pinned input and copying the weights across keeps the ordinary,
    well-tested `from_keras_model` path, and the exported graph then agrees
    with Keras to the same 1.9e-03 as the dynamic export it replaces.
    """
    batch_shape = (1,) + tuple(model.input_shape[1:])
    pinned = tf.keras.Input(batch_shape=batch_shape, name=model.inputs[0].name)
    clone = tf.keras.models.clone_model(model, input_tensors=pinned)
    clone.set_weights(model.get_weights())
    return clone


def _convert(model, quantise: bool, allow_select_ops: bool, pin_batch: bool = True):
    conv = tf.lite.TFLiteConverter.from_keras_model(
        _pin_batch(model) if pin_batch else model
    )
    if quantise:
        conv.optimizations = [tf.lite.Optimize.DEFAULT]
    ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    if allow_select_ops:
        ops.append(tf.lite.OpsSet.SELECT_TF_OPS)
    conv.target_spec.supported_ops = ops
    conv._experimental_lower_tensor_list_ops = False
    return conv.convert()


def _dynamic_tensors(blob: bytes) -> int:
    """
    How many tensors still carry a `-1` in their shape. Must be zero.

    Asserted on every export rather than checked by hand, because the symptom
    of getting it wrong is not an error: it is a model that runs perfectly on
    the CPU of the one device anybody tests on.
    """
    interp = tf.lite.Interpreter(model_content=blob)
    interp.allocate_tensors()
    return sum(
        1 for d in interp.get_tensor_details()
        if d.get("shape_signature") is not None
        and any(int(s) < 0 for s in d["shape_signature"])
    )


def _run_tflite(blob: bytes, x: np.ndarray) -> np.ndarray:
    interp = tf.lite.Interpreter(model_content=blob)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    interp.set_tensor(inp["index"], x.astype(inp["dtype"]))
    interp.invoke()
    return interp.get_tensor(out["index"])


def export(name: str, quantise: bool, as_name: str | None = None) -> dict:
    out_name = as_name or name
    keras_path = MODELS / f"{name}.keras"
    if not keras_path.exists():
        raise FileNotFoundError(keras_path)

    model = tf.keras.models.load_model(keras_path, custom_objects=CUSTOM,
                                       compile=False)
    rng = np.random.default_rng(0)
    result = {"model": name, "quantised": quantise}

    # ---- mel-input variant (the guaranteed path) -----------------------
    mel_probe = rng.standard_normal((4, PATCH_FRAMES, N_MELS)).astype(np.float32)
    keras_out = model.predict(mel_probe, verbose=0)

    blob = _convert(model, quantise, allow_select_ops=False)
    mel_path = MODELS / f"{out_name}_mel.tflite"
    mel_path.write_bytes(blob)

    tfl_out = np.concatenate([_run_tflite(blob, mel_probe[i:i + 1]) for i in range(4)])
    mel_drift = float(np.max(np.abs(keras_out - tfl_out)))
    mel_dynamic = _dynamic_tensors(blob)
    result["mel"] = {"bytes": len(blob), "max_drift": mel_drift,
                     "agrees": mel_drift < 2e-2, "dynamic_tensors": mel_dynamic,
                     "nnapi_eligible": mel_dynamic == 0}
    print(f"  {out_name}_mel.tflite       {len(blob)/1024:7.1f} KB   "
          f"drift {mel_drift:.2e}  {'OK' if mel_drift < 2e-2 else 'MISMATCH'}"
          f"   {mel_dynamic} dynamic {'(NNAPI can take it)' if mel_dynamic == 0 else '(NNAPI WILL REFUSE)'}")

    # ---- waveform-input variant (preferred if it converts) -------------
    try:
        wrapped = wrap_with_frontend(model, f"{out_name}_wave")
        wave_probe = (rng.standard_normal((4, PATCH_SAMPLES)) * 0.1).astype(np.float32)
        keras_wave = wrapped.predict(wave_probe, verbose=0)

        # The waveform variant keeps its dynamic batch: it is the experimental
        # path, it goes through SELECT_TF_OPS which NNAPI would refuse anyway,
        # and cloning a model with a signal-processing layer in it is a
        # different problem from the one being fixed here.
        blob_w = _convert(wrapped, quantise, allow_select_ops=True, pin_batch=False)
        wave_path = MODELS / f"{out_name}_wave.tflite"
        wave_path.write_bytes(blob_w)

        tfl_wave = np.concatenate([_run_tflite(blob_w, wave_probe[i:i + 1])
                                   for i in range(4)])
        drift = float(np.max(np.abs(keras_wave - tfl_wave)))
        result["wave"] = {"bytes": len(blob_w), "max_drift": drift,
                          "agrees": drift < 2e-2}
        print(f"  {out_name}_wave.tflite      {len(blob_w)/1024:7.1f} KB   "
              f"drift {drift:.2e}  {'OK' if drift < 2e-2 else 'MISMATCH'}")
    except Exception as e:
        result["wave"] = {"error": f"{type(e).__name__}: {e}"[:300]}
        print(f"  {out_name}_wave.tflite      FAILED -- shipping mel input + "
              f"Kotlin frontend\n      {type(e).__name__}: {str(e)[:160]}")

    return result


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    # detect_hn is the shipping detector: same architecture, retrained after a
    # hard-negative mining round. It exports under the plain "detect" asset name
    # so the app does not need to know which round it came from.
    ap.add_argument("--models", nargs="+", default=["detect_hn:detect", "reason"])
    ap.add_argument("--no-quantise", action="store_true")
    a = ap.parse_args()

    print(f"\nExporting TFLite (quantise={'off' if a.no_quantise else 'dynamic range'})")
    out = []
    for spec in a.models:
        src, _, dest = spec.partition(":")
        out.append(export(src, not a.no_quantise, dest or None))
    (REPORTS / "export.json").write_text(json.dumps(out, indent=2))
    print(f"\n  -> reports/export.json")
