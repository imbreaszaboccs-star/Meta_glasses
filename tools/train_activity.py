"""Train the FocusLens activity MLP from exported data-collection sessions.

    python tools/train_activity.py path/to/export          # folder with <session>/win.csv (the unzipped "Export")
    python tools/train_activity.py --selftest              # synthetic data, checks the JSON round-trip

Only rows with a user label (the scripted activity picked in the app) are used. Prints leave-one-session-out accuracy and a
confusion matrix, then trains on everything and writes focuslens/android/app/src/main/assets/activity_mlp.json
(format: MlpClassifier.kt). Do not commit the model file.
"""
import argparse
import csv
import json
import sys
import tempfile
from pathlib import Path

import numpy as np
from sklearn.neural_network import MLPClassifier

# Must equal Features.NAMES in Imu.kt (MlpClassifier refuses anything else).
NAMES = [
    "pitch_rel_mean", "pitch_std", "roll_mean", "roll_std", "yaw_rate_energy", "gyro_energy",
    "acc_low_energy", "acc_high_energy", "transitions", "hist_low", "hist_mid", "hist_high",
    "nod_score", "step_score", "speech_ratio", "since_pinch_log",
]
ACTS = ["board", "notes", "switching", "still", "looking_around", "nod", "walking"]
OUT = Path(__file__).resolve().parent.parent / "focuslens/android/app/src/main/assets/activity_mlp.json"


def load(root: Path):
    X, y, g = [], [], []
    for f in sorted(root.glob("*/win.csv")):
        with f.open(newline="") as fh:
            rd = csv.DictReader(fh)
            if rd.fieldnames is None or any(n not in rd.fieldnames for n in NAMES):
                sys.exit(f"{f}: columns do not match Features.NAMES")
            for row in rd:
                lab = (row.get("label") or "").strip()
                if not lab:
                    continue
                if lab not in ACTS:
                    sys.exit(f"{f}: unknown label {lab!r}")
                X.append([float(row[n]) for n in NAMES]); y.append(lab); g.append(f.parent.name)
    return np.array(X, dtype=float), np.array(y), np.array(g)


def fit(X, y):
    mean, std = X.mean(0), X.std(0)
    std[std == 0] = 1.0
    clf = MLPClassifier(hidden_layer_sizes=(32,), max_iter=800, random_state=0).fit((X - mean) / std, y)
    return clf, mean, std


def confusion(y_true, y_pred):
    labels = [a for a in ACTS if a in set(y_true) | set(y_pred)]
    idx = {a: i for i, a in enumerate(labels)}
    m = np.zeros((len(labels), len(labels)), dtype=int)
    for t, p in zip(y_true, y_pred):
        m[idx[t], idx[p]] += 1
    w = max(len(a) for a in labels) + 1
    print(" " * w + " ".join(f"{a[:7]:>7}" for a in labels))
    for a, row in zip(labels, m):
        print(f"{a:<{w}}" + " ".join(f"{v:>7}" for v in row))


def loso(X, y, g):
    sessions = sorted(set(g))
    if len(sessions) < 2:
        print("Leave-one-session-out needs at least 2 labelled sessions; skipped.")
        return
    pred = np.empty_like(y)
    for s in sessions:
        te = g == s
        if len(set(y[~te])) < 2:
            pred[te] = y[~te][0]
            continue
        clf, mean, std = fit(X[~te], y[~te])
        pred[te] = clf.predict((X[te] - mean) / std)
    print(f"Leave-one-session-out accuracy: {(pred == y).mean():.3f} ({len(y)} windows, {len(sessions)} sessions)")
    confusion(y, pred)


def export(clf, mean, std, path: Path):
    W = [c.tolist() for c in clf.coefs_]
    b = [i.tolist() for i in clf.intercepts_]
    classes = [str(c) for c in clf.classes_]
    if len(classes) == 2:  # sklearn uses one logistic unit for two classes: softmax([0, z]) is the same thing
        W[-1] = [[0.0, w[0]] for w in W[-1]]
        b[-1] = [0.0, b[-1][0]]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({
        "features": NAMES, "mean": mean.tolist(), "std": std.tolist(),
        "layers": [{"W": w, "b": bb} for w, bb in zip(W, b)], "classes": classes,
    }))


def forward(model: dict, x):
    """Same math as MlpClassifier.kt."""
    h = (np.asarray(x) - model["mean"]) / np.where(np.asarray(model["std"]) == 0, 1, model["std"])
    for i, layer in enumerate(model["layers"]):
        h = h @ np.array(layer["W"]) + layer["b"]
        if i < len(model["layers"]) - 1:
            h = np.maximum(h, 0)
    e = np.exp(h - h.max(-1, keepdims=True))
    return e / e.sum(-1, keepdims=True)


def selftest():
    rng = np.random.default_rng(0)
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        centers = rng.normal(0, 3, (len(ACTS), len(NAMES)))
        for s in range(3):
            d = root / f"session{s}"
            d.mkdir()
            with (d / "win.csv").open("w", newline="") as fh:
                w = csv.writer(fh)
                w.writerow(["t", *NAMES, "heuristic", "model", "label", "att"])
                for k, a in enumerate(ACTS):
                    for t in range(30):
                        w.writerow([t, *(centers[k] + rng.normal(0, 0.5, len(NAMES))), "notes", "", a, "engaged"])
                w.writerow([999, *np.zeros(len(NAMES)), "notes", "", "", "engaged"])  # unlabelled rows are ignored
        X, y, g = load(root)
        assert X.shape == (3 * 30 * len(ACTS), len(NAMES)), X.shape
        loso(X, y, g)
        for subset in (ACTS, ACTS[:2]):  # 7 classes, and the 2-class special case
            keep = np.isin(y, subset)
            clf, mean, std = fit(X[keep], y[keep])
            out = root / "model.json"
            export(clf, mean, std, out)
            model = json.loads(out.read_text())
            assert model["features"] == NAMES and model["classes"] == [str(c) for c in clf.classes_]
            ours = forward(model, X[keep])
            ref = clf.predict_proba((X[keep] - mean) / std)
            assert np.allclose(ours, ref, atol=1e-5), np.abs(ours - ref).max()
            assert (np.array(model["classes"])[ours.argmax(1)] == y[keep]).mean() > 0.95
    print("selftest ok")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("data", nargs="?", type=Path, help="folder with <session>/win.csv")
    ap.add_argument("--out", type=Path, default=OUT)
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    if a.data is None:
        ap.error("data folder required (or --selftest)")
    X, y, g = load(a.data)
    if len(y) == 0:
        sys.exit("no labelled windows found")
    loso(X, y, g)
    clf, mean, std = fit(X, y)
    export(clf, mean, std, a.out)
    print(f"wrote {a.out} ({len(y)} windows, classes: {', '.join(clf.classes_)})")


if __name__ == "__main__":
    main()
