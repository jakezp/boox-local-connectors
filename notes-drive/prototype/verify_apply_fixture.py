"""Trusted host readback proof for the exact pen/link apply experiment.

This does not acknowledge a device journal. ApplyMain independently checks that
the readback artifact, live component hashes and metadata still match the proof.
The observer must remain closed between its before/after snapshots.
"""

import json
from pathlib import Path

from native_fixture import compare, inspect_archive, require
from prepare_apply_fixture import PARTS, tree_hash


def verify(base, artifacts, job="complete-update"):
    base, artifacts = Path(base), Path(artifacts)
    plan = json.loads((base / "candidate/plan.json").read_text())
    identities = json.loads((base / "candidate/identities.json").read_text())
    source = inspect_archive(artifacts / "B2.note")
    before = inspect_archive(artifacts / "Target-before.note")
    after = inspect_archive(artifacts / "Target-after.note")
    observer = inspect_archive(artifacts / "Observer-before.note")
    require(source["sha256"] == plan["payloadSha256"], "Incoming payload differs")
    comparison = compare(source, after)
    for page in comparison["pages"]:
        require(all(page[key] for key in (
            "pen_data_and_style_equal", "layers_equal", "dimensions_equal",
            "internal_links_remapped")), "Native content readback differs")
    require(after["document_id"] == before["document_id"] == plan["document"],
            "Notebook identity changed")
    require(after["title"] == before["title"], "Local notebook title changed")
    pages = [page["id"] for page in after["pages"]]
    require(pages == [page["id"] for page in before["pages"]],
            "Page identity/order changed")
    all_shapes = [shape for page in after["pages"] for shape in page["shapes"]]
    shapes = {shape["id"]: shape for shape in all_shapes}
    require(len(shapes) == len(all_shapes), "Duplicate shape identity across pages")
    require(set(shapes) == set(identities["shapes"].values()), "Shape identity map differs")
    for old, new in zip(before["pages"], after["pages"]):
        page_shapes = {shape["id"]: shape for shape in new["shapes"]}
        for shape in old["shapes"]:
            require(page_shapes.get(shape["id"]) == shape, "Existing stroke changed")
    links = [shape["link"] for page in observer["pages"]
             for shape in page["shapes"] if shape["type"] == 33]
    require(len(links) == 1 and links[0]["document_id"] == after["document_id"]
            and links[0]["page_id"] in pages, "Inbound link target changed")
    for part in (*PARTS, "note.json"):
        require(tree_hash(base / "observer-snapshot" / part) ==
                tree_hash(base / "observer-after-snapshot" / part),
                "Observer notebook changed")
    snapshot = base / "after-readback-snapshot"
    row = json.loads((snapshot / "note.json").read_text())
    require(row["uniqueId"] == after["document_id"], "Snapshot identity differs")
    proof = {
        "job": job, "payloadSha256": plan["payloadSha256"],
        "nativeReadbackPassed": True, "identitiesPreserved": True,
        "inboundLinksPreserved": True, "readbackSha256": after["sha256"],
        "currentHashes": {part: tree_hash(snapshot / part) for part in PARTS},
        "currentRow": row,
    }
    return proof


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    print(json.dumps(verify(root / "research/apply", root / "tests/artifacts"), indent=2))
